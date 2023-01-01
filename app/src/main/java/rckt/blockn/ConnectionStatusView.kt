package rckt.blockn

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

private val isLineageOs = Build.HOST.startsWith("lineage")

class ConnectionStatusView(
  context: Context,
  totalAppCount: Int,
  interceptedAppCount: Int,
  changeApps: () -> Unit,
) : LinearLayout(context) {

  init {
    val layout = R.layout.connection_status_none
    LayoutInflater.from(context).inflate(layout, this, true)

    val appInterceptionStatus = findViewById<MaterialCardView>(R.id.interceptedAppsButton)
    appInterceptionStatus.setOnClickListener { _ ->
      if (!isLineageOs) {
        changeApps()
      } else {
        MaterialAlertDialogBuilder(context)
          .setTitle("Not available")
          .setIcon(R.drawable.ic_exclamation_triangle)
          .setMessage(
            """
                        Per-app filtering is not possible on LineageOS, due to a bug in Lineage's VPN implementation.

                        If you'd like this fixed, please upvote the bug in their issue tracker.
                        """.trimIndent()
          )
          .setNegativeButton("Cancel") { _, _ -> }
          .setPositiveButton("View the bug") { _, _ ->
            context.startActivity(
              Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://gitlab.com/LineageOS/issues/android/-/issues/1706")
              )
            )
          }
          .show()
      }
    }

    val appInterceptionStatusText = findViewById<TextView>(R.id.interceptedAppsStatus)
    appInterceptionStatusText.text = context.getString(
      when {
        totalAppCount == interceptedAppCount -> R.string.all_apps
        interceptedAppCount > 10 -> R.string.selected_apps
        else -> R.string.few_apps
      },
      interceptedAppCount,
      if (interceptedAppCount != 1) "s" else ""
    )
  }
}
