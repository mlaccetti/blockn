package rckt.blockn

import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.net.VpnService
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

private const val ACTIVATE_INTENT = "rckt.blockn.ACTIVATE"
private const val DEACTIVATE_INTENT = "rckt.blockn.DEACTIVATE"

class MainActivity : AppCompatActivity(), CoroutineScope by MainScope() {
  private lateinit var app: BlocknApplication

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

    app = this.application as BlocknApplication
    setContentView(R.layout.main_layout)
    updateUi()

    Log.i(TAG, "Main activity created")

    // Are we being opened by an intent? I.e. a barcode scan/URL elsewhere on the device
    if (intent != null) {
      Log.i(TAG, "Main activity started via intent")
      onNewIntent(intent)
    } else {
      // If not, check if this is a post-install run, and if so configure automatically
      // using the install referrer
      launch {
        val firstRunParams = app.popFirstRunParams()
        if (
          firstRunParams != null &&
          firstRunParams.startsWith("https://rckt.tech/blockn/")
        ) {
          Log.i(TAG, "Main activity started via post-install run")
          launch { validateConnectToVpn() }
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
      intent.component?.className == "rckt.blockn.RemoteControlMainActivity"

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
              "Do you want to share all this device's HTTP traffic with Blockn?" +
                "\n\n" +
                "Only accept this if you trust the source."
            )
            .setPositiveButton("Enable") { _, _ ->
              Log.i(TAG, "Prompt confirmed")
              launch { connectToVpn() }
            }
            .setNegativeButton("Cancel") { _, _ ->
              Log.i(TAG, "Prompt cancelled")
            }
            .show()
        } else {
          Log.i(TAG, "Launching from ACTION_VIEW intent")
          launch { connectToVpn() }
        }
      }

      // RC setup API, used by ADB to enable/disable without prompts.
      // Permission required, checked for via activity-alias in the manifest
      isRCIntent && intent.action == ACTIVATE_INTENT -> {
        app.trackEvent("Setup", "rc-activate")
        launch { connectToVpn() }
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

        buttonContainer.visibility = View.VISIBLE
        buttonContainer.addView(primaryButton(R.string.scan_button, ::launchConnectToVpn))
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
  }

  private fun primaryButton(@StringRes contentId: Int, clickHandler: () -> Unit): Button {
    val button = layoutInflater.inflate(R.layout.primary_button, null) as Button
    button.setText(contentId)
    button.setOnClickListener { clickHandler() }
    return button
  }

  private fun detailText(@StringRes resId: Int): TextView {
    val text = TextView(ContextThemeWrapper(this, R.style.DetailText))
    text.text = getString(resId)
    return text
  }

  private fun launchConnectToVpn() {
    app.trackEvent("Button", "scan-code")
    launch { connectToVpn() }
  }

  private suspend fun validateConnectToVpn() {
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

  private fun resetAfterFailure() {
    app.trackEvent("Button", "try-again")
    mainState = MainState.DISCONNECTED
    updateUi()
  }

  private fun chooseApps() {
    startActivityForResult(
      Intent(this, ApplicationListActivity::class.java).apply {
        putExtra(UNSELECTED_APPS_EXTRA, app.uninterceptedApps.toTypedArray())
      },
      PICK_APPS_REQUEST
    )
  }

  @Deprecated("Deprecated in Java")
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
        Log.i(TAG, "resultOk, selecting apps")
        val unselectedApps = data!!.getStringArrayExtra(UNSELECTED_APPS_EXTRA)!!.toSet()
        if (unselectedApps != app.uninterceptedApps) {
          app.uninterceptedApps = unselectedApps
          if (isVpnActive()) startVpn()
        }
      } else {
        // launch { connectToVpn() }
        Log.i(TAG, "resultOk, cert installed, starting VPN")
        app.trackEvent("Setup", "start-vpn")
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

  private suspend fun connectToVpn() {
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
        validateConnectToVpn()
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

  private fun showVpnKilledAlert() {
    MaterialAlertDialogBuilder(this)
      .setTitle("Blockn was killed")
      .setIcon(R.drawable.ic_exclamation_triangle)
      .setMessage(
        "Blockn interception was shut down automatically by Android. " +
          "This is usually caused by overly strict power management of background processes. " +
          "\n\n" +
          "To fix this, disable battery optimization for Blockn in your settings."
      )
      .setNegativeButton("Ignore") { _, _ -> }
      .setPositiveButton("Go to settings") { _, _ ->
        val batterySettingIntents = listOf(
          Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
          Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
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
          if (tryStartActivity(intent)) break
        }
      }
      .show()
  }

  private fun showActiveVpnFailureAlert() {
    MaterialAlertDialogBuilder(this)
      .setTitle("VPN setup failed")
      .setIcon(R.drawable.ic_exclamation_triangle)
      .setMessage(
        "Blockn could not be configured as a VPN on your device." +
          "\n\n" +
          "This usually means you have an always-on VPN configured, which blocks " +
          "installation of other VPNs. To activate Blockn you'll need to " +
          "deactivate that VPN first."
      )
      .setNegativeButton("Cancel") { _, _ -> }
      .setPositiveButton("Open VPN Settings") { _, _ ->
        startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
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
