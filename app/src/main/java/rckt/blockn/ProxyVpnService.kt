package rckt.blockn

import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.view.ContentInfoCompat.Flags
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import io.sentry.Sentry
import rckt.blockn.HttpToolkitApplication
import rckt.blockn.MainActivity
import rckt.blockn.vpn.socket.IProtectSocket
import rckt.blockn.vpn.socket.SocketProtector
import java.io.*

private const val ALL_ROUTES = "0.0.0.0"
private const val VPN_IP_ADDRESS =
  "169.254.61.43" // Random link-local IP, this will be the tunnel's IP

private const val NOTIFICATION_ID = 45456
private const val NOTIFICATION_CHANNEL_ID = "vpn-notifications"

const val START_VPN_ACTION = "tech.httptoolkit.android.START_VPN_ACTION"
const val STOP_VPN_ACTION = "tech.httptoolkit.android.STOP_VPN_ACTION"

const val VPN_STARTED_BROADCAST = "tech.httptoolkit.android.VPN_STARTED_BROADCAST"
const val VPN_STOPPED_BROADCAST = "tech.httptoolkit.android.VPN_STOPPED_BROADCAST"

const val UNINTERCEPTED_APPS_EXTRA = "tech.httptoolkit.android.UNINTERCEPTED_APPS"

private var currentService: ProxyVpnService? = null
fun isVpnActive(): Boolean {
  return if (currentService == null)
    false
  else
    currentService?.isActive() ?: false
}

class ProxyVpnService : VpnService(),
  IProtectSocket {

  private lateinit var app: HttpToolkitApplication

  private var localBroadcastManager: LocalBroadcastManager? = null

  private var vpnInterface: ParcelFileDescriptor? = null
  private var vpnRunnable: ProxyVpnRunnable? = null

  override fun onCreate() {
    super.onCreate()
    currentService = this
  }

  override fun onDestroy() {
    super.onDestroy()
    currentService = null
  }

  override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
    currentService = this
    Log.i(TAG, "onStartCommand called")
    Log.i(TAG, intent.action ?: "no action")

    if (localBroadcastManager == null) {
      localBroadcastManager = LocalBroadcastManager.getInstance(this)
    }
    app = this.application as HttpToolkitApplication

    if (intent.action == START_VPN_ACTION) {
      val uninterceptedApps = intent.getStringArrayExtra(UNINTERCEPTED_APPS_EXTRA)!!.toSet()

      val vpnStarted = if (isActive())
        restartVpn(uninterceptedApps)
      else
        startVpn(uninterceptedApps)

      if (vpnStarted) {
        // If the system briefly kills us for some reason (memory, the user, whatever) whilst
        // running the VPN, it should redeliver the VPN setup intent ASAP.
        return Service.START_REDELIVER_INTENT
      } else {
        // We failed to start somehow - cleanup
        stopVpn()
      }
    } else if (intent.action == STOP_VPN_ACTION) {
      stopVpn()
    }

    // Shouldn't matter (we should've stopped already), but in general: if we're not running a
    // VPN, then the service doesn't need to be sticky.
    return Service.START_NOT_STICKY
  }

  override fun onRevoke() {
    super.onRevoke()
    app.trackEvent("VPN", "vpn-revoked")
    Log.i(TAG, "onRevoke called")
    stopVpn()
  }

  private fun showServiceNotification() {
    val pendingActivityIntent: PendingIntent =
      Intent(this, MainActivity::class.java).let { notificationIntent ->
        PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
      }

    val pendingServiceIntent: PendingIntent =
      Intent(this, ProxyVpnService::class.java).let { notificationIntent ->
        notificationIntent.action = STOP_VPN_ACTION
        PendingIntent.getService(this, 1, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
      }

    val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    val notificationChannel = NotificationChannel(
      NOTIFICATION_CHANNEL_ID,
      "VPN Status",
      NotificationManager.IMPORTANCE_DEFAULT
    )
    notificationManager.createNotificationChannel(notificationChannel)

    val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
      .setContentIntent(pendingActivityIntent)
      .setContentTitle(getString(R.string.vpn_active_notification_title))
      .setContentText(getString(R.string.vpn_active_notification_content))
      .setSmallIcon(R.drawable.ic_transparent_icon)
      .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.ic_transparent_icon))
      .addAction(0, getString(R.string.vpn_active_notification_action), pendingServiceIntent)
      .build()

    startForeground(NOTIFICATION_ID, notification)

  }

  private fun startVpn(
    uninterceptedApps: Set<String>
  ): Boolean {
    val packages = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)

    val allPackageNames = packages.map { pkg -> pkg.packageName }
    val isGenymotion = allPackageNames.any {
      // This check could be stricter (com.genymotion.genyd), but right now it doesn't seem to
      // have any false positives, and it's very flexible to changes in genymotion itself.
        name ->
      name.startsWith("com.genymotion")
    }

    if (this.vpnInterface != null) return false // Already running, do nothing

    app.pauseEvents() // Try not to send events while the VPN is active, it's unnecessary noise
    app.trackEvent("VPN", "vpn-started")
    val vpnInterface = Builder()
      .addAddress(VPN_IP_ADDRESS, 32)
      .addRoute(ALL_ROUTES, 0)
      .setMtu(MAX_PACKET_LEN) // Limit the packet size to the buffer used by ProxyVpnRunnable
      .setBlocking(true) // We use a blocking loop to read in ProxyVpnRunnable

      .apply {
        // We exclude ourselves from interception, so we can still make network requests
        // separately, primarily because otherwise pinging with isReachable is recursive.
        val httpToolkitPackage = packageName

        when {
          isGenymotion -> {
            // For some reason, with Genymotion the whole device crashes if we intercept
            // the whole system, so we have to ensure we *always* explicitly allow
            // every app that we care about.

            val pkgsToIntercept = allPackageNames.filter { name ->
              name != httpToolkitPackage && !uninterceptedApps.contains(name)
            }

            if (!pkgsToIntercept.isEmpty()) {
              pkgsToIntercept.forEach { pkg -> addAllowedApplication(pkg) }
            } else {
              // We can never intercept nothing (or the whole emulator crashes), so
              // instead we intercept a random bit of Genymotions internals, which
              // (AFAICT) doesn't seem to ever send traffic.
              addAllowedApplication("com.genymotion.genyd")
            }
          }
          else -> {
            // In every other case, it's better to list the disallowed apps, rather than
            // adding only the intercepted apps, because that ensures new apps that are
            // installed whilst interception is active get intercepted straight away

            // Don't intercept them explicitly disallowed packages:
            uninterceptedApps
              .filter { app -> allPackageNames.contains(app) }
              .forEach { name ->
                addDisallowedApplication(name)
              }

            // Never intercept HTTP Toolkit (as above - doing so causes problems)
            addDisallowedApplication(httpToolkitPackage)
          }
        }
      }
      .setSession(getString(R.string.app_name))
      .establish()

    // establish() returns null if we no longer have permissions to establish the VPN somehow
    if (vpnInterface == null) {
      return false
    } else {
      this.vpnInterface = vpnInterface
    }

    showServiceNotification()
    SocketProtector.getInstance().setProtector(this)

    // TODO: Should we support *?

    vpnRunnable = ProxyVpnRunnable(
      vpnInterface
    )
    Thread(vpnRunnable, "Vpn thread").start()

    app.vpnShouldBeRunning = true
    return true
  }

  private fun restartVpn(
    uninterceptedApps: Set<String>,
  ): Boolean {
    Log.i(TAG, "VPN stopping for restart...")

    if (vpnRunnable != null) {
      app.trackEvent("VPN", "vpn-stopped-for-restart")
      vpnRunnable!!.stop()
      vpnRunnable = null
    }

    try {
      vpnInterface?.close()
      vpnInterface = null
    } catch (e: IOException) {
      Sentry.captureException(e)
    }

    stopForeground(true)
    return startVpn(uninterceptedApps)
  }

  private fun stopVpn() {
    Log.i(TAG, "VPN stopping...")

    if (vpnRunnable != null) {
      app.trackEvent("VPN", "vpn-stopped")
      app.resumeEvents()

      vpnRunnable!!.stop()
      vpnRunnable = null
    }

    try {
      vpnInterface?.close()
      vpnInterface = null
    } catch (e: IOException) {
      Sentry.captureException(e)
    }

    stopForeground(true)
    localBroadcastManager!!.sendBroadcast(Intent(VPN_STOPPED_BROADCAST))
    stopSelf()

    currentService = null
    app.vpnShouldBeRunning = false
  }

  fun isActive(): Boolean {
    return this.vpnInterface != null
  }

}
