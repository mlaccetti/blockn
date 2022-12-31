package rckt.blockn

import android.content.pm.PackageInfo
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import rckt.blockn.AppLabelCache
import rckt.blockn.databinding.AppsListBinding

class ApplicationListAdapter(
  private val data: MutableList<PackageInfo>,
  private val isAppWhitelisted: (PackageInfo) -> Boolean,
  private val onCheckChanged: (PackageInfo, Boolean) -> Unit
) : RecyclerView.Adapter<ApplicationListAdapter.AppsViewHolder>() {

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppsViewHolder {
    val binding = AppsListBinding.inflate(LayoutInflater.from(parent.context))
    return AppsViewHolder(binding)
  }

  override fun getItemCount() = data.size

  override fun onBindViewHolder(holder: AppsViewHolder, position: Int) {
    holder.bind(data[position])
  }

  inner class AppsViewHolder(v: View) : RecyclerView.ViewHolder(v) {
    private val packageManager by lazy {
      itemView.context.packageManager
    }

    init {
      itemView.row_app_switch.setOnCheckedChangeListener { _, isChecked ->
        onCheckChanged(data[layoutPosition], isChecked)
      }
    }

    fun bind(packageInfo: PackageInfo) {
      val appInfo = packageInfo.applicationInfo
      itemView.row_app_icon_image.setImageDrawable(appInfo.loadIcon(packageManager))
      itemView.row_app_name.text = AppLabelCache.getAppLabel(packageManager, appInfo)
      itemView.row_app_package_name.text = packageInfo.packageName
      itemView.row_app_switch.isChecked = isAppWhitelisted(packageInfo)
    }
  }
}
