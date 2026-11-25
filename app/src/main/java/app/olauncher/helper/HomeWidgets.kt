package app.olauncher.helper

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import app.olauncher.R

internal class HomeWidgetStore(private val context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    val host = AppWidgetHost(context, 1042)
    private val prefs = context.getSharedPreferences("home_widgets", Context.MODE_PRIVATE)
    var id: Int
        get() = prefs.getInt("id", AppWidgetManager.INVALID_APPWIDGET_ID)
        set(value) { prefs.edit { putInt("id", value) } }
    var above: Boolean
        get() = prefs.getBoolean("above", false)
        set(value) { prefs.edit { putBoolean("above", value) } }

    fun remove() {
        val previous = id
        id = AppWidgetManager.INVALID_APPWIDGET_ID
        release(previous)
    }

    fun release(id: Int) {
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) runCatching { host.deleteAppWidgetId(id) }
    }

    fun attach(container: FrameLayout): Boolean {
        val id = id
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return false
        val info = manager?.getAppWidgetInfo(id) ?: return false
        // RemoteViews must inflate platform views, not AppCompat replacements, because Android
        // only allows annotated platform methods to be called by a widget provider.
        val platformViews = object : ContextWrapper(container.context) {
            private val inflater by lazy { LayoutInflater.from(applicationContext).cloneInContext(this) }
            override fun getSystemService(name: String): Any? =
                if (name == Context.LAYOUT_INFLATER_SERVICE) inflater else super.getSystemService(name)
        }
        val widget = host.createView(platformViews, id, info)
        container.removeAllViews()
        val density = container.resources.displayMetrics.density
        val maxHeight = if (container.resources.configuration.screenHeightDp < 500) 100 else 180
        val minHeight = (info.minHeight / density).toInt().coerceIn(48, maxHeight)
        container.addView(widget, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (minHeight * density).toInt()))
        widget.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                val width = ((right - left) / density).toInt()
                val height = ((bottom - top) / density).toInt()
                if (width > 0 && height > 0) widget.updateAppWidgetSize(Bundle(), width, height, width, height)
            }
        }
        return true
    }
}

internal class WidgetSetup(private val fragment: Fragment, private val setupInProgress: (Boolean) -> Unit) {
    private val invalid = AppWidgetManager.INVALID_APPWIDGET_ID
    private val store by lazy { HomeWidgetStore(fragment.requireContext()) }
    private var pending = invalid
    private val bindLauncher = fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && pending != invalid) configureOrFinish()
        else cancel()
    }
    private val configureLauncher = fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && pending != invalid) finish()
        else cancel()
    }

    fun restore(state: Bundle?) { pending = state?.getInt("personal_widget_pending", invalid) ?: invalid }
    fun save(state: Bundle) { state.putInt("personal_widget_pending", pending) }
    fun cancel() {
        if (pending != invalid) store.release(pending)
        pending = invalid
        setupInProgress(false)
    }

    fun manage(): AlertDialog? {
        val context = fragment.requireContext()
        val info = store.id.takeUnless { it == invalid }?.let { store.manager?.getAppWidgetInfo(it) }
        if (info == null) return pick()
        return AlertDialog.Builder(context)
            .setTitle(info.loadLabel(context.packageManager))
            .setItems(arrayOf(context.getString(if (store.above) R.string.personal_widget_move_below else R.string.personal_widget_move_above),
                context.getString(R.string.personal_widget_remove))) { _, choice ->
                if (choice == 0) store.above = !store.above else store.remove()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pick(): AlertDialog? {
        val context = fragment.requireContext()
        val providers = store.manager?.installedProviders.orEmpty()
            .filter { it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 }
            .map { it to it.loadLabel(context.packageManager) }
            .sortedBy { it.second.lowercase() }
        if (providers.isEmpty()) {
            context.showToast(R.string.personal_widget_none)
            return null
        }
        return AlertDialog.Builder(context)
            .setTitle(R.string.personal_widget_add)
            .setItems(providers.map { (_, label) -> label }.toTypedArray()) { _, position -> begin(providers[position].first) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun begin(provider: AppWidgetProviderInfo) {
        cancel()
        val manager = store.manager ?: return
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
        }
        try {
            pending = store.host.allocateAppWidgetId()
            setupInProgress(true)
            if (manager.bindAppWidgetIdIfAllowed(pending, provider.profile, provider.provider, options)) configureOrFinish()
            else bindLauncher.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pending)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, provider.profile)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options)
            })
        } catch (_: RuntimeException) { fail() }
    }

    private fun configureOrFinish() {
        val info = store.manager?.getAppWidgetInfo(pending) ?: return fail()
        val component = info.configure
        val optional = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        if (component == null || optional) {
            finish()
            return
        }
        try {
            configureLauncher.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                setComponent(component)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pending)
            })
        } catch (_: RuntimeException) { fail() }
    }

    private fun finish() {
        if (store.manager?.getAppWidgetInfo(pending) == null) return fail()
        val previous = store.id
        store.id = pending
        pending = invalid
        setupInProgress(false)
        store.release(previous)
    }

    private fun fail() {
        cancel()
        fragment.requireContext().showToast(R.string.personal_widget_failed)
    }
}
