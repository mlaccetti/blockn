package rckt.blockn

import android.app.Application
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse
import com.android.installreferrer.api.InstallReferrerStateListener
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.analytics.ktx.logEvent
import com.google.firebase.ktx.Firebase
import io.sentry.Sentry
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val VPN_START_TIME_PREF = "vpn-start-time"
private const val APP_CRASHED_PREF = "app-crashed"
private const val FIRST_RUN_PREF = "is-first-run"

private val isProbablyEmulator =
  Build.FINGERPRINT.startsWith("generic")
    || Build.FINGERPRINT.startsWith("unknown")
    || Build.MODEL.contains("google_sdk")
    || Build.MODEL.contains("Emulator")
    || Build.MODEL.contains("Android SDK built for x86")
    || Build.BOARD == "QC_Reference_Phone"
    || Build.MANUFACTURER.contains("Genymotion")
    || Build.HOST.startsWith("Build")
    || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
    || Build.PRODUCT == "google_sdk"

private val bootTime = (System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime())

class BlocknApplication : Application() {
  private lateinit var analytics: FirebaseAnalytics

  private lateinit var prefs: SharedPreferences
  private var vpnWasKilled: Boolean = false

  var vpnShouldBeRunning: Boolean
    get() {
      return prefs.getLong(VPN_START_TIME_PREF, -1) > bootTime
    }
    set(value) {
      if (value) {
        prefs.edit().putLong(VPN_START_TIME_PREF, System.currentTimeMillis()).apply()
      } else {
        prefs.edit().putLong(VPN_START_TIME_PREF, -1).apply()
      }
    }

  override fun onCreate() {
    super.onCreate()
    prefs = getSharedPreferences("rckt.blockn", MODE_PRIVATE)

    Thread.setDefaultUncaughtExceptionHandler { _, _ ->
      prefs.edit().putBoolean(APP_CRASHED_PREF, true).apply()
    }

    analytics = Firebase.analytics

    // Check if we've been recreated unexpectedly, with no crashes in the meantime:
    val appCrashed = prefs.getBoolean(APP_CRASHED_PREF, false)
    prefs.edit().putBoolean(APP_CRASHED_PREF, false).apply()

    vpnWasKilled = vpnShouldBeRunning && !isVpnActive() && !appCrashed && !isProbablyEmulator
    if (vpnWasKilled) {
      Sentry.captureMessage("VPN killed in the background")
      // The UI will show an alert next time the MainActivity is created.
    }

    Log.i(TAG, "App created")
  }

  /**
   * Check whether the VPN was killed as a sleeping background process, and then
   * reset that state so that future checks (until it's next killed) return false
   */
  fun popVpnKilledState(): Boolean {
    return vpnWasKilled
      .also { this.vpnWasKilled = false }
  }

  /**
   * Grab any first run params, drop them for future usage, and return them.
   * This will return first-run params at most once (per install).
   */
  suspend fun popFirstRunParams(): String? {
    val isFirstRun = prefs.getBoolean(FIRST_RUN_PREF, true)
    prefs.edit().putBoolean(FIRST_RUN_PREF, false).apply()

    val installTime = packageManager.getPackageInfo(packageName, 0).firstInstallTime
    val now = System.currentTimeMillis()
    val timeSinceInstall = now - installTime

    // 15 minutes after install, initial run params expire entirely
    if (!isFirstRun || timeSinceInstall > 1000 * 60 * 15) {
      Log.i(TAG, "No first-run params. 1st run $isFirstRun, $timeSinceInstall since install")
      return null
    }

    // Get & return the actual referrer and return it
    Log.i(TAG, "Getting first run referrer...")
    return suspendCoroutine { cont ->
      val wasResumed = AtomicBoolean()
      val resume = { value: String? ->
        if (wasResumed.getAndSet(true)) {
          cont.resume(value)
        }
      }
      val referrerClient = InstallReferrerClient.newBuilder(this).build()
      referrerClient.startConnection(object : InstallReferrerStateListener {

        override fun onInstallReferrerSetupFinished(responseCode: Int) {
          when (responseCode) {
            InstallReferrerResponse.OK -> {
              val referrer = referrerClient.installReferrer.installReferrer
              Log.i(TAG, "Returning first run referrer: $referrer")
              resume(referrer)
            }
            else -> {
              Log.w(TAG, "Couldn't get install referrer, skipping: $responseCode")
              resume(null)
            }
          }
        }

        override fun onInstallReferrerServiceDisconnected() {
          Log.w(TAG, "Couldn't get install referrer due to disconnection")
          resume(null)
        }
      })
    }
  }

  var uninterceptedApps: Set<String>
    get() {
      val prefs = getSharedPreferences("rckt.blockn", MODE_PRIVATE)
      val packagesSet = prefs.getStringSet("unintercepted-packages", null)
      val allPackages = packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
        .map { pkg -> pkg.packageName }
      return (packagesSet ?: setOf())
        .filter { pkg -> allPackages.contains(pkg) } // Filter, as packages might've been uninstalled
        .toSet()
    }
    set(packageNames) {
      val prefs = getSharedPreferences("rckt.blockn", MODE_PRIVATE)
      prefs.edit().putStringSet("unintercepted-packages", packageNames).apply()
    }

  fun trackScreen(name: String) {
    analytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW) {
      param(FirebaseAnalytics.Param.ITEM_ID, name)
      param(FirebaseAnalytics.Param.ITEM_NAME, name)
      param(FirebaseAnalytics.Param.CONTENT_TYPE, "image")
    }
  }

  fun trackEvent(category: String, action: String) {
    analytics.logEvent(FirebaseAnalytics.Event.LOGIN) {
      param(FirebaseAnalytics.Param.ITEM_ID, category)
      param(FirebaseAnalytics.Param.ITEM_NAME, action)
    }
  }
}
