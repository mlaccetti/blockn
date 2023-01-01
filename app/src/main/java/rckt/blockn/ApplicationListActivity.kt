package rckt.blockn

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.*
import rckt.blockn.databinding.AppsListBinding
import java.util.*

// Used to both to send and return the current list of selected apps
const val UNSELECTED_APPS_EXTRA = "rckt.blockn.UNSELECTED_APPS_EXTRA"

class ApplicationListActivity : AppCompatActivity(), SwipeRefreshLayout.OnRefreshListener,
  CoroutineScope by MainScope(), PopupMenu.OnMenuItemClickListener, View.OnClickListener {

  private val allApps = ArrayList<PackageInfo>()
  private val filteredApps = ArrayList<PackageInfo>()

  private lateinit var blockedPackages: MutableSet<String>

  private var showSystem = false
  private var showEnabledOnly = false
  private var textFilter = ""

  private lateinit var binding: AppsListBinding

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = AppsListBinding.inflate(layoutInflater)
    setContentView(R.layout.apps_list)

    blockedPackages = intent.getStringArrayExtra(UNSELECTED_APPS_EXTRA)!!.toHashSet()
    val view = binding.root
    setContentView(view)

    binding.appsListRecyclerView.adapter =
      ApplicationListAdapter(
        filteredApps,
        ::isAppEnabled,
        ::setAppEnabled
      )

    binding.appsListSwipeRefreshLayout.setOnRefreshListener(this)
    binding.appsListMoreMenu.setOnClickListener(this)
    binding.appsListFilterEditText.doAfterTextChanged {
      textFilter = it.toString()
      applyFilters()
    }

    onRefresh()
  }

  override fun onRefresh() {
    launch(Dispatchers.Main) {
      if (binding.appsListSwipeRefreshLayout.isRefreshing.not()) {
        binding.appsListSwipeRefreshLayout.isRefreshing = true
      }

      val apps = loadAllApps()
      allApps.clear()
      allApps.addAll(apps)
      applyFilters()

      binding.appsListSwipeRefreshLayout.isRefreshing = false
    }
  }

  private fun applyFilters() {
    filteredApps.clear()
    filteredApps.addAll(allApps.filter(::matchesFilters))
    binding.appsListRecyclerView.adapter?.notifyDataSetChanged()
  }

  private fun matchesFilters(app: PackageInfo): Boolean {
    val appInfo = app.applicationInfo
    val appLabel = AppLabelCache.getAppLabel(packageManager, appInfo)
    val isSystemApp = appInfo.flags and ApplicationInfo.FLAG_SYSTEM == 1

    return (textFilter.isEmpty() || appLabel.contains(textFilter, true)) && // Filter by name
      (showSystem || !isSystemApp) && // Show system apps, if that's active
      (!showEnabledOnly || isAppEnabled(app)) && // Only show enabled apps, if that's active
      app.packageName != packageName // Never show ourselves
  }

  private fun isAppEnabled(app: PackageInfo): Boolean {
    return !blockedPackages.contains(app.packageName)
  }

  private fun setAppEnabled(app: PackageInfo, isEnabled: Boolean) {
    val wasChanged = if (!isEnabled) {
      blockedPackages.add(app.packageName)
    } else {
      blockedPackages.remove(app.packageName)
    }

    if (wasChanged && showEnabledOnly) applyFilters()
  }

  private suspend fun loadAllApps(): List<PackageInfo> =
    withContext(Dispatchers.IO) {
      return@withContext packageManager.getInstalledPackages(PackageManager.MATCH_ALL).apply {
        sortBy { pkg ->
          AppLabelCache.getAppLabel(packageManager, pkg.applicationInfo).uppercase(
            Locale.getDefault()
          )
        }
      }
    }

  override fun onMenuItemClick(item: MenuItem?): Boolean {
    return when (item?.itemId) {
      R.id.action_show_system -> {
        showSystem = showSystem.not()
        applyFilters()
        true
      }
      R.id.action_show_enabled -> {
        showEnabledOnly = showEnabledOnly.not()
        applyFilters()
        true
      }
      R.id.action_toggle_all -> {
        if (blockedPackages.isEmpty()) {
          // If everything is enabled, disable everything
          blockedPackages.addAll(allApps.map { app -> app.packageName })
        } else {
          // Otherwise, re-enable everything
          blockedPackages.removeAll(allApps.map { app -> app.packageName })
        }

        if (showEnabledOnly) {
          applyFilters()
        } else {
          binding.appsListRecyclerView.adapter?.notifyDataSetChanged()
        }
        true
      }
      else -> false
    }
  }

  override fun onClick(v: View?) {
    when (v?.id) {
      R.id.apps_list_more_menu -> {
        PopupMenu(this, binding.appsListMoreMenu).apply {
          this.inflate(R.menu.menu_app_list)
          this.menu.findItem(R.id.action_show_system).isChecked = showSystem
          this.menu.findItem(R.id.action_show_enabled).isChecked = showEnabledOnly
          this.menu.findItem(R.id.action_toggle_all).title = getString(
            if (blockedPackages.isEmpty())
              R.string.disable_all
            else
              R.string.enable_all
          )
          this.setOnMenuItemClickListener(this@ApplicationListActivity)
        }.show()
      }
    }
  }

  @Deprecated("Deprecated in Java")
  override fun onBackPressed() {
    setResult(
      RESULT_OK, Intent().putExtra(
        UNSELECTED_APPS_EXTRA,
        blockedPackages.toTypedArray()
      )
    )
    finish()
  }
}
