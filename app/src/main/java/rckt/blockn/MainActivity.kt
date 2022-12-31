package rckt.blockn

import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.common.GooglePlayServicesUtil
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.sentry.Sentry
import kotlinx.coroutines.*
import java.net.ConnectException
import java.net.SocketTimeoutException

const val START_VPN_REQUEST = 123
const val PICK_APPS_REQUEST = 499

enum class MainState {
  DISCONNECTED,
  CONNECTING,
  CONNECTED,
  DISCONNECTING,
  FAILED
}

private const val ACTIVATE_INTENT = "tech.httptoolkit.android.ACTIVATE"
private const val DEACTIVATE_INTENT = "tech.httptoolkit.android.DEACTIVATE"

class MainActivity : AppCompatActivity(), CoroutineScope by MainScope() {
  private lateinit var app: HttpToolkitApplication

  private var localBroadcastManager: LocalBroadcastManager? = null
  private val broadcastReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (intent.action == VPN_STARTED_BROADCAST) {
        mainState = MainState.CONNECTED
        updateUi()
      } else if (intent.action == VPN_STOPPED_BROADCAST) {
        mainState = MainState.DISCONNECTED
        updateUi()
      }
    }
  }

  private var mainState: MainState =
    if (isVpnActive()) MainState.CONNECTED else MainState.DISCONNECTED

  // Used to track extremely fast VPN setup failures, indicating setup issues (rather than
  // manual user cancellation). Doesn't matter that it's not properly persistent.
  private var lastPauseTime = -1L;

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    localBroadcastManager = LocalBroadcastManager.getInstance(this)
    localBroadcastManager!!.registerReceiver(broadcastReceiver, IntentFilter().apply {
      addAction(VPN_STARTED_BROADCAST)
      addAction(VPN_STOPPED_BROADCAST)
    })

    app = this.application as HttpToolkitApplication
    setContentView(R.layout.main_layout)
    updateUi()

    Log.i(TAG, "Main activity created")

    // Are we being opened by an intent? I.e. a barcode scan/URL elsewhere on the device
    if (intent != null) {
      onNewIntent(intent)
    } else {
      // If not, check if this is a post-install run, and if so configure automatically
      // using the install referrer
      launch {
        val firstRunParams = app.popFirstRunParams()
        if (
          firstRunParams != null &&
          firstRunParams.startsWith("https://android.httptoolkit.tech/connect/")
        ) {
          launch { connectToVpn() }
        }
      }
    }

    val batteryOptimizationsDisabled =
      (getSystemService(Context.POWER_SERVICE) as PowerManager)
        .isIgnoringBatteryOptimizations(packageName)

    if (app.popVpnKilledState() && !batteryOptimizationsDisabled) {
      // The app was killed last run, probably by battery optimizations: show a warning
      showVpnKilledAlert()
    }
  }

  override fun onResume() {
    super.onResume()
    Log.d(TAG, "onResume")
    app.trackScreen("Main")
  }

  override fun onPause() {
    super.onPause()
    Log.d(TAG, "onPause")
    app.clearScreen()
    this.lastPauseTime = System.currentTimeMillis()
  }

  override fun onDestroy() {
    super.onDestroy()
    Log.d(TAG, "onDestroy")
    localBroadcastManager!!.unregisterReceiver(broadcastReceiver)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)

    // RC intents are intents that have passed the RC permission requirement in the manifest.
    // Implicit intents with the matching actions will always use the RC activity, this check
    // protects against explicit intents targeting MainActivity. RC intents are known to be
    // trustworthy, so are allowed to silently activate/deactivate the VPN connection.
    val isRCIntent =
      intent.component?.className == "tech.httptoolkit.android.RemoteControlMainActivity"

    when {
      // ACTION_VIEW means that somebody had the app installed, and scanned the barcode with
      // a separate barcode app anyway (or opened the QR code URL in a browser)
      intent.action == Intent.ACTION_VIEW -> {
        app.trackEvent("Setup", "action-view")
        if (isVpnConfigured()) {
          Log.i(TAG, "Showing prompt for ACTION_VIEW intent")

          // If we were started from an intent (e.g. another barcode scanner/link), and we
          // had a proxy before (so no prompts required) then confirm before starting the VPN.
          // Without this any QR code you scan could instantly MitM you.
          MaterialAlertDialogBuilder(this)
            .setTitle("Enable Interception")
            .setIcon(R.drawable.ic_exclamation_triangle)
            .setMessage(
              "Do you want to share all this device's HTTP traffic with HTTP Toolkit?" +
                "\n\n" +
                "Only accept this if you trust the source."
            )
            .setPositiveButton("Enable") { _, _ ->
              Log.i(TAG, "Prompt confirmed")
              launch { connectToVpnFrom() }
            }
            .setNegativeButton("Cancel") { _, _ ->
              Log.i(TAG, "Prompt cancelled")
            }
            .show()
        } else {
          Log.i(TAG, "Launching from ACTION_VIEW intent")
          launch { connectToVpnFrom() }
        }
      }

      // RC setup API, used by ADB to enable/disable without prompts.
      // Permission required, checked for via activity-alias in the manifest
      isRCIntent && intent.action == ACTIVATE_INTENT -> {
        app.trackEvent("Setup", "rc-activate")
        launch { connectToVpnFrom() }
      }
      isRCIntent && intent.action == DEACTIVATE_INTENT -> {
        app.trackEvent("Setup", "rc-deactivate")
        disconnect()
      }

      intent.action == "android.intent.action.MAIN" -> {
        // The app is being opened - nothing to do here
        app.trackEvent("Setup", "ui-opened")
      }

      else -> Log.w(
        TAG, "Ignoring unknown intent. Action ${
          intent.action
        }, data: ${
          intent.data
        }${
          if (isRCIntent) " (RC)" else ""
        }"
      )
    }
  }

  @MainThread
  private fun updateUi() {
    val statusText = findViewById<TextView>(R.id.statusText)

    val buttonContainer = findViewById<LinearLayout>(R.id.buttonLayoutContainer)
    buttonContainer.removeAllViews()

    val detailContainer = findViewById<LinearLayout>(R.id.statusDetailContainer)
    detailContainer.removeAllViews()

    when (mainState) {
      MainState.DISCONNECTED -> {
        statusText.setText(R.string.disconnected_status)

        detailContainer.addView(detailText(R.string.disconnected_details))

        buttonContainer.visibility = View.VISIBLE
        buttonContainer.addView(primaryButton(R.string.scan_button, ::connectToVPN))
      }
      MainState.CONNECTING -> {
        statusText.setText(R.string.connecting_status)
        buttonContainer.visibility = View.GONE
      }
      MainState.CONNECTED -> {
        val totalAppCount = packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
          .map { app -> app.packageName }
          .toSet()
          .size
        val interceptedAppsCount = totalAppCount - app.uninterceptedApps.size

        statusText.setText(R.string.connected_status)

        detailContainer.addView(
          ConnectionStatusView(
            this,
            totalAppCount,
            interceptedAppsCount,
            ::chooseApps
          )
        )

        buttonContainer.visibility = View.VISIBLE
        buttonContainer.addView(primaryButton(R.string.disconnect_button, ::disconnect))
        buttonContainer.addView(secondaryButton(R.string.test_button, ::testInterception))
      }
      MainState.DISCONNECTING -> {
        statusText.setText(R.string.disconnecting_status)
        buttonContainer.visibility = View.GONE
      }
      MainState.FAILED -> {
        statusText.setText(R.string.failed_status)

        detailContainer.addView(detailText(R.string.failed_details))

        buttonContainer.visibility = View.VISIBLE
        buttonContainer.addView(primaryButton(R.string.try_again_button, ::resetAfterFailure))
      }
    }

    if (buttonContainer.visibility == View.VISIBLE) {
      buttonContainer.addView(secondaryButton(R.string.docs_button, ::openDocs))
    }
  }

  private fun primaryButton(@StringRes contentId: Int, clickHandler: () -> Unit): Button {
    val button = layoutInflater.inflate(R.layout.primary_button, null) as Button
    button.setText(contentId)
    button.setOnClickListener { clickHandler() }
    return button
  }

  private fun secondaryButton(@StringRes contentId: Int, clickHandler: () -> Unit): Button {
    val button = layoutInflater.inflate(R.layout.secondary_button, null) as Button
    button.setText(contentId)
    button.setOnClickListener { clickHandler() }
    return button
  }

  private fun detailText(@StringRes resId: Int): TextView {
    val text = TextView(ContextThemeWrapper(this, R.style.DetailText))
    text.text = getString(resId)
    return text
  }

  private fun connectToVPN() {
    app.trackEvent("Button", "scan-code")
    launch { connectToVpnFrom() }
    // startActivityForResult(Intent(this, ScanActivity::class.java), SCAN_REQUEST)
  }

  private suspend fun connectToVpn() {
    Log.i(TAG, "Connect to VPN")

    this.mainState = MainState.CONNECTING

    withContext(Dispatchers.Main) { updateUi() }

    app.trackEvent("Button", "start-vpn")
    val vpnIntent = VpnService.prepare(this)
    Log.i(TAG, if (vpnIntent != null) "got intent" else "no intent")
    val vpnNotConfigured = vpnIntent != null

    if (vpnNotConfigured) {
      // In this case the VPN needs setup, but the cert is trusted already, so it's
      // a single confirmation. Pretty clear, no need to explain. This happens if the
      // VPN/app was removed from the device in the past, or when using injected system certs.
      startActivityForResult(vpnIntent, START_VPN_REQUEST)
    } else {
      // VPN is trusted & cert setup already, lets get to it.
      onActivityResult(START_VPN_REQUEST, RESULT_OK, null)
    }

  }

  private fun disconnect() {
    mainState = MainState.DISCONNECTING
    updateUi()

    app.trackEvent("Button", "stop-vpn")
    startService(Intent(this, ProxyVpnService::class.java).apply {
      action = STOP_VPN_ACTION
    })
  }

  private suspend fun reconnect() {
    app.trackEvent("Button", "reconnect")

    withContext(Dispatchers.Main) {
      mainState = MainState.CONNECTING
      updateUi()
    }

    try {
      connectToVpn()
    } catch (e: Exception) {
      Log.e(TAG, e.toString())
      e.printStackTrace()

      withContext(Dispatchers.Main) {
        app.trackEvent("Setup", "reconnect-failed")
        mainState = MainState.FAILED
        updateUi()
      }

      // We report errors only that aren't simple connection failures
      if (e !is SocketTimeoutException && e !is ConnectException) {
        Sentry.captureException(e)
      }
    }
  }

  private fun resetAfterFailure() {
    app.trackEvent("Button", "try-again")
    mainState = MainState.DISCONNECTED
    updateUi()
  }

  private fun openDocs() {
    app.trackEvent("Button", "open-docs")
    launchBrowser("httptoolkit.tech/docs/guides/android")
  }

  private fun chooseApps() {
    startActivityForResult(
      Intent(this, ApplicationListActivity::class.java).apply {
        putExtra(UNSELECTED_APPS_EXTRA, app.uninterceptedApps.toTypedArray())
      },
      PICK_APPS_REQUEST
    )
  }

  private fun testInterception() {
    app.trackEvent("Button", "test-interception")

    // If we have a system cert, in theory we could use any browser. In practice though, some
    // (i.e. Firefox) ignore system certs to use their own settings. It's best to try and ensure
    // for testing, we always use a supported browser. This will prioritize the default, if it
    // is supported, so only matters if the default browser is not on our known-good list.
    val testBrowser = getTestBrowserPackage(this)

    val canUseHttps = testBrowser != null

    launchBrowser("amiusing.httptoolkit.tech", canUseHttps, testBrowser)
  }

  private fun launchBrowser(
    uri: String,
    canUseHttps: Boolean = true,
    browserPackage: String? = null
  ) {
    try {
      startActivity(
        Intent(
          Intent.ACTION_VIEW,
          Uri.parse(
            (
              if (canUseHttps) "https" else "http"
              ) + "://" + uri
          )
        ).apply {
          if (browserPackage != null) setPackage(browserPackage)
        }
      )
    } catch (e: ActivityNotFoundException) {
      if (browserPackage != null) {
        // If we tried a specific package, and it failed, try again with the simplest
        // plain HTTP catch-all VIEW intent, and hope something somewhere can handle it.
        launchBrowser(uri, false)
      } else {
        showNoBrowserAlert(uri)
      }
    }
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)

    Log.i(TAG, "onActivityResult")
    Log.i(
      TAG, when (requestCode) {
        START_VPN_REQUEST -> "start-vpn"
        PICK_APPS_REQUEST -> "pick-apps"
        else -> requestCode.toString()
      }
    )

    Log.i(TAG, if (resultCode == RESULT_OK) "ok" else resultCode.toString())
    val resultOk = resultCode == RESULT_OK

    if (resultOk) {
      if (requestCode == PICK_APPS_REQUEST) {
        app.trackEvent("Setup", "picked-apps")
        val unselectedApps = data!!.getStringArrayExtra(UNSELECTED_APPS_EXTRA)!!.toSet()
        if (unselectedApps != app.uninterceptedApps) {
          app.uninterceptedApps = unselectedApps
          if (isVpnActive()) startVpn()
        }
      } else {
        // launch { connectToVpn() }
        app.trackEvent("Setup", "installed-cert-successfully")
        startVpn()
      }
    } else if (
      requestCode == START_VPN_REQUEST &&
      System.currentTimeMillis() - lastPauseTime < 200 && // On Pixel 4a it takes < 50ms
      resultCode == Activity.RESULT_CANCELED
    ) {
      // If another always-on VPN is active, VPN start requests fail instantly as cancelled.
      // We can't check that the VPN is always-on, but given an instant failure that's
      // the likely cause, so we warn about it:
      showActiveVpnFailureAlert()

      // Then go back to the disconnected state:
      mainState = MainState.DISCONNECTED
      updateUi()
    } else {
      Sentry.captureMessage("Non-OK result $resultCode for requestCode $requestCode")
      mainState = MainState.FAILED
      updateUi()
    }
  }

  private fun startVpn() {
    Log.i(TAG, "Starting VPN")
    launch {
      withContext(Dispatchers.Main) {
        mainState = MainState.CONNECTING
        updateUi()
      }
    }

    startService(Intent(this, ProxyVpnService::class.java).apply {
      action = START_VPN_ACTION
      putExtra(UNINTERCEPTED_APPS_EXTRA, app.uninterceptedApps.toTypedArray())
    })

    launch {
      withContext(Dispatchers.Main) {
        mainState = MainState.CONNECTED
        updateUi()
      }
    }
  }

  private suspend fun connectToVpnFrom() {
    Log.i(TAG, "Connecting to VPN")
    if (
      mainState != MainState.DISCONNECTED &&
      mainState != MainState.FAILED
    ) return

    withContext(Dispatchers.Main) {
      mainState = MainState.CONNECTING
      updateUi()
    }

    withContext(Dispatchers.IO) {
      try {
        connectToVpn()
      } catch (e: Exception) {
        Log.e(TAG, e.toString())
        e.printStackTrace()

        withContext(Dispatchers.Main) {
          app.trackEvent("Setup", "connect-failed")
          mainState = MainState.FAILED
          updateUi()
        }

        // We report errors only that aren't simple connection failures
        if (e !is SocketTimeoutException && e !is ConnectException) {
          Sentry.captureException(e)
        }
      }
    }
  }

  private fun isVpnConfigured(): Boolean {
    return VpnService.prepare(this) == null
  }

  private suspend fun promptToUpdate() {
    withContext(Dispatchers.Main) {
      MaterialAlertDialogBuilder(this@MainActivity)
        .setTitle("Updates available")
        .setIcon(R.drawable.ic_info_circle)
        .setMessage("An updated version of HTTP Toolkit is available")
        .setNegativeButton("Ignore") { _, _ -> }
        .setPositiveButton("Update now") { _, _ ->
          // Open the app in the market. That a release is available on github doesn't
          // *strictly* mean that it's available on the Android market right now, but
          // it is imminent, and installing from play means it'll update fully later.
          startActivity(
            Intent(Intent.ACTION_VIEW).apply {
              data = Uri.parse("market://details?id=tech.httptoolkit.android.v1")
            }
          )
        }
        .show()
    }
  }

  private fun showVpnKilledAlert() {
    MaterialAlertDialogBuilder(this)
      .setTitle("HTTP Toolkit was killed")
      .setIcon(R.drawable.ic_exclamation_triangle)
      .setMessage(
        "HTTP Toolkit interception was shut down automatically by Android. " +
          "This is usually caused by overly strict power management of background processes. " +
          "\n\n" +
          "To fix this, disable battery optimization for HTTP Toolkit in your settings."
      )
      .setNegativeButton("Ignore") { _, _ -> }
      .setPositiveButton("Go to settings") { _, _ ->
        val batterySettingIntents = listOf(
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
          } else null,
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
          } else null,
          Intent().apply {
            this.component = ComponentName(
              "com.samsung.android.lool",
              "com.samsung.android.sm.ui.battery.BatteryActivity"
            )
          },
          Intent().apply {
            this.component = ComponentName(
              "com.samsung.android.sm",
              "com.samsung.android.sm.ui.battery.BatteryActivity"
            )
          },
          Intent(Settings.ACTION_SETTINGS)
        )

        // Try the intents in order until one of them works
        for (intent in batterySettingIntents) {
          if (intent != null && tryStartActivity(intent)) break
        }
      }
      .show()
  }

  private fun showNoBrowserAlert(uri: String) {
    MaterialAlertDialogBuilder(this)
      .setTitle("No browser available")
      .setIcon(R.drawable.ic_exclamation_triangle)
      .setMessage(
        "HTTP Toolkit could not open a browser on this device. " +
          "This usually means you don't have any browser installed. To visit " +
          uri +
          " please install a browser app."
      )
      .setNeutralButton("OK") { _, _ -> }
      .show()
  }

  private fun showActiveVpnFailureAlert() {
    MaterialAlertDialogBuilder(this)
      .setTitle("VPN setup failed")
      .setIcon(R.drawable.ic_exclamation_triangle)
      .setMessage(
        "HTTP Toolkit could not be configured as a VPN on your device." +
          "\n\n" +
          "This usually means you have an always-on VPN configured, which blocks " +
          "installation of other VPNs. To activate HTTP Toolkit you'll need to " +
          "deactivate that VPN first."
      )
      .setNegativeButton("Cancel") { _, _ -> }
      .setPositiveButton("Open VPN Settings") { _, _ ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
          startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        } else {
          startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
      }
      .show()
  }

  private fun tryStartActivity(intent: Intent): Boolean {
    return try {
      startActivity(intent)
      true
    } catch (e: ActivityNotFoundException) {
      false
    } catch (e: SecurityException) {
      false
    }
  }
}

private fun isPackageAvailable(context: Context, packageName: String) = try {
  context.packageManager.getPackageInfo(packageName, 0)
  true
} catch (e: PackageManager.NameNotFoundException) {
  false
}

private fun getDefaultBrowserPackage(context: Context): String? {
  val browserIntent = Intent("android.intent.action.VIEW", Uri.parse("http://example.com"))
  val resolveInfo =
    context.packageManager.resolveActivity(browserIntent, PackageManager.MATCH_DEFAULT_ONLY)
  return resolveInfo?.activityInfo?.packageName
}

private fun getTestBrowserPackage(context: Context): String? {
  // A list of browsers that trust the user store by default, and so
  // will work OOTB even if only the user cert is trusted.
  val supportedBrowsers = listOf(
    "com.android.chrome", // Modern Android
    "com.android.browser", // <= Android 2.3
    "com.google.android.browser", // > 2.3, < 4.0.2
    "com.brave.browser", // Brave
    "com.microsoft.emmx", // Edge
    "com.sec.android.app.sbrowser" // Samsung browser
    // FF/Opera/UC Browser & others don't trust user CAs by default, so we avoid them for testing
  )

  // If the default browser is supported, just use that, easy
  val defaultBrowser = getDefaultBrowserPackage(context)
  Log.i("tech.httptoolkit", "Default browser is $defaultBrowser")
  if (supportedBrowsers.contains(defaultBrowser)) {
    return defaultBrowser
  }

  // If not, use the first browser in the list above that's installed, or return null
  return supportedBrowsers.firstOrNull { packageName ->
    isPackageAvailable(context, packageName)
  }
}

private fun isStoreAvailable(context: Context): Boolean =
  isPackageAvailable(context, GooglePlayServicesUtil.GOOGLE_PLAY_STORE_PACKAGE)
