package rckt.blockn

import android.app.KeyguardManager
import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat.getSystemService
import com.beust.klaxon.Klaxon
import io.sentry.Sentry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

private val TAG = formatTag("tech.httptoolkit.android.ProxySetup")

class ResponseException(message: String) : ConnectException(message)

suspend fun request(httpClient: OkHttpClient, url: String): String {
  return withContext(Dispatchers.IO) {
    httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
      if (response.code != 200) {
        throw ResponseException("Proxy responded with non-200: ${response.code}")
      }
      response.body!!.string()
    }
  }
}

/**
 * Does the device have a PIN/pattern/password set? Relevant because if not, the cert
 * setup will require the user to add one. This is best guess - not 100% accurate.
 */
fun isDeviceSecured(context: Context): Boolean {
  val keyguardManager = getSystemService(context, KeyguardManager::class.java)

  return when {
    // If we can't get a keyguard manager for some reason, assume there's no pin set
    keyguardManager == null -> false
    // If possible, accurately report device status
    true -> keyguardManager.isDeviceSecure
    // Imperfect but close though: also returns true if the device has a locked SIM card.
    else -> keyguardManager.isKeyguardSecure
  }
}
