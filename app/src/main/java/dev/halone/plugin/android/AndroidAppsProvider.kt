package dev.halone.plugin.android

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import dev.halone.plugin.PanelProviderService
import dev.halone.plugin.PanelSession
import dev.halone.plugin.PluginApp

private class SettingsScreen(val label: Int, val action: String)

private val SETTINGS = mapOf(
    AndroidAppsProvider.WIFI_SETTINGS to SettingsScreen(R.string.wifi_settings, Settings.ACTION_WIFI_SETTINGS),
    AndroidAppsProvider.MOBILE_SETTINGS to SettingsScreen(R.string.mobile_settings, Settings.ACTION_DATA_ROAMING_SETTINGS),
    AndroidAppsProvider.BLUETOOTH_SETTINGS to SettingsScreen(R.string.bluetooth_settings, Settings.ACTION_BLUETOOTH_SETTINGS),
)

class AndroidAppsProvider : PanelProviderService() {
    lateinit var catalog: AppCatalog
        private set
    lateinit var home: HomeApps
        private set
    lateinit var privileged: Privileged
        private set
    lateinit var recents: RecentApps
        private set
    private lateinit var signal: SignalStatus
    private lateinit var bluetooth: BluetoothStatus

    override fun onCreate() {
        super.onCreate()
        running = this
        home = HomeApps(this)
        recents = RecentApps(this)
        privileged = Privileged(this).also { it.start() }
        catalog = AppCatalog(this) {
            recents.retain(catalog.apps.mapTo(HashSet()) { it.id })
            appsChanged()
            listChanged.toList().forEach { it() }
        }.also { it.start() }
        signal = SignalStatus(this, ::setStatusItem, ::removeStatusItem).also { it.start() }
        bluetooth = BluetoothStatus(this, ::setStatusItem, ::removeStatusItem).also { it.start() }
    }

    override fun onDestroy() {
        if (running === this) running = null
        bluetooth.stop()
        signal.stop()
        catalog.stop()
        privileged.stop()
        super.onDestroy()
    }

    val listChanged = LinkedHashSet<() -> Unit>()

    override fun apps(): List<PluginApp> =
        listOf(PluginApp(LIST, getString(R.string.list_name), getString(R.string.list_description), LIST_WIDTH, LIST_HEIGHT, icon = R.drawable.ic_apps)) +
            SETTINGS.map { (id, screen) -> PluginApp(id, getString(screen.label), widthPx = APP_WIDTH, heightPx = APP_HEIGHT, hidden = true) } +
            catalog.apps.map {
                PluginApp(it.id, it.label, widthPx = APP_WIDTH, heightPx = APP_HEIGHT, hidden = it.id !in home, iconVersion = it.updatedAt.toString())
            }

    override fun appIcon(appId: String, sizePx: Int): Bitmap? {
        if (appId == LIST) return super.appIcon(appId, sizePx)
        val component = SETTINGS[appId]?.let { Intent(it.action).resolveActivity(packageManager) } ?: catalog.find(appId)?.component
        return component?.let { catalog.icon(it, sizePx) }
    }

    override fun createSession(appId: String, args: Bundle): PanelSession? {
        if (appId == LIST) return AppListSession(this)
        SETTINGS[appId]?.let { return AppPanelSession(this, getString(it.label), Intent(it.action)) }
        val app = catalog.find(appId) ?: return null
        val launch = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return AppPanelSession(this, app.label, launch, recentId = app.id)
    }

    override fun onStatusItemClicked(id: String) {
        when (id) {
            SignalStatus.WIFI -> launchApp(WIFI_SETTINGS)
            SignalStatus.MOBILE -> launchApp(MOBILE_SETTINGS)
            BluetoothStatus.ID -> launchApp(BLUETOOTH_SETTINGS)
        }
    }

    fun setOnHome(id: String, onHome: Boolean) {
        home.set(id, onHome)
        appsChanged()
        listChanged.toList().forEach { it() }
    }

    companion object {
        /** The provider while Halone has it running, for [PhoneScreenProvider]; main thread. */
        @Volatile
        var running: AndroidAppsProvider? = null
            private set

        const val LIST = "apps"
        const val WIFI_SETTINGS = "wifi-settings"
        const val MOBILE_SETTINGS = "mobile-settings"
        const val BLUETOOTH_SETTINGS = "bluetooth-settings"
        const val LIST_WIDTH = 760
        const val LIST_HEIGHT = 640
        const val APP_WIDTH = 450
        const val APP_HEIGHT = 800
    }
}
