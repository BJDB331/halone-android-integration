package dev.halone.plugin.android

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper

/** The phone's launchable apps, kept current as packages come and go. */
class AppCatalog(private val context: Context, private val onChanged: () -> Unit) {
    data class PhoneApp(val component: ComponentName, val label: String, val updatedAt: Long) {
        val id: String get() = component.flattenToShortString()
    }

    private val main = Handler(Looper.getMainLooper())
    private val pm = context.packageManager
    private val reload = Runnable {
        load()
        onChanged()
    }

    @Volatile
    var apps: List<PhoneApp> = emptyList()
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            main.removeCallbacks(reload)
            main.postDelayed(reload, RELOAD_DELAY_MS)
        }
    }

    fun start() {
        load()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    fun stop() {
        main.removeCallbacks(reload)
        context.unregisterReceiver(receiver)
    }

    fun find(id: String): PhoneApp? = apps.firstOrNull { it.id == id }

    fun icon(component: ComponentName, sizePx: Int): Bitmap? {
        val drawable = runCatching { pm.getActivityIcon(component) }.getOrNull() ?: return null
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private fun load() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
            .map { it.activityInfo }
            .filter { !it.packageName.startsWith(HALONE_PACKAGES) }
            .map { info ->
                val updatedAt = runCatching { pm.getPackageInfo(info.packageName, 0).lastUpdateTime }.getOrDefault(0L)
                PhoneApp(ComponentName(info.packageName, info.name), info.loadLabel(pm).toString(), updatedAt)
            }
            .distinctBy { it.id }
            .sortedBy { it.label.lowercase() }
    }

    private companion object {
        const val RELOAD_DELAY_MS = 500L
        const val HALONE_PACKAGES = "dev.halone."
    }
}
