package rckt.blockn

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.Result
import me.dm7.barcodescanner.zxing.ZXingScannerView
import rckt.blockn.HttpToolkitApplication

const val SCANNED_URL_EXTRA = "tech.httptoolkit.android.SCANNED_URL"

class ScanActivity : AppCompatActivity(), ZXingScannerView.ResultHandler {

  private var app: HttpToolkitApplication? = null

  private var scannerView: ZXingScannerView? = null

  public override fun onCreate(state: Bundle?) {
    super.onCreate(state)

    val canUseCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
    if (canUseCamera != PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 0)
      // Until confirmed, the activity will show as empty, switching to the
      // camera as soon as permission is accepted.
    }
    scannerView = ZXingScannerView(this)
    scannerView!!.setFormats(listOf(BarcodeFormat.QR_CODE))
    setContentView(scannerView)
    app = this.application as HttpToolkitApplication
  }

  public override fun onResume() {
    super.onResume()

    app!!.trackScreen("Scan")
    scannerView!!.setResultHandler(this)
    scannerView!!.startCamera()
  }

  public override fun onPause() {
    super.onPause()

    app!!.clearScreen()
    scannerView!!.stopCamera()
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (grantResults.isNotEmpty() && grantResults[0] == PERMISSION_GRANTED) {
      Log.i(TAG, "Camera permissions granted")
    } else {
      Log.i(TAG, "Camera permissions rejected")
      setResult(Activity.RESULT_CANCELED)
      finish()
    }
  }

  override fun handleResult(rawResult: Result) {
    val url = rawResult.text

    if (!url.startsWith("https://android.httptoolkit.tech/connect/")) {
      Log.v(TAG, "Scanned unrecognized QR code: $url")
      scannerView?.resumeCameraPreview(this)
      return
    }

    Log.v(TAG, "Scanned $url")
    app!!.trackEvent("Setup", "tag-scanned")

    setResult(RESULT_OK, Intent().let { intent ->
      intent.putExtra(SCANNED_URL_EXTRA, url)
    })
    finish()
  }
}
