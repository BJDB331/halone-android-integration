package dev.halone.plugin.android

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku
import rikka.sui.Sui

/** On the phone: what the plugin needs, and a button for each thing that is missing. */
class SetupActivity : Activity() {
    private lateinit var shizukuStatus: TextView
    private lateinit var shizukuButton: Button
    private lateinit var bluetoothStatus: TextView
    private lateinit var bluetoothButton: Button
    private lateinit var launcherStatus: TextView
    private lateinit var launcherButton: Button
    private var bluetoothAsked = false
    private val launcherEntry by lazy { ComponentName(this, "$packageName.Launcher") }

    private val binderReceived = Shizuku.OnBinderReceivedListener { runOnUiThread(::refresh) }
    private val binderDead = Shizuku.OnBinderDeadListener { runOnUiThread(::refresh) }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread(::refresh) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = dp(24)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        column.addView(text(getString(R.string.setup_intro), 16f))

        column.addView(heading(getString(R.string.setup_shizuku_title)))
        shizukuStatus = text("", 15f).also(column::addView)
        shizukuButton = Button(this).also { column.addView(it, buttonParams()) }

        column.addView(heading(getString(R.string.setup_bluetooth_title)))
        bluetoothStatus = text("", 15f).also(column::addView)
        bluetoothButton = Button(this).also { column.addView(it, buttonParams()) }

        column.addView(heading(getString(R.string.setup_launcher_title)))
        launcherStatus = text("", 15f).also(column::addView)
        launcherButton = Button(this).also { column.addView(it, buttonParams()) }

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(column)
        })

        Sui.init(packageName)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        refresh()
    }

    private fun refresh() {
        val (status, action, onClick) = when (Privileged.missingAccess(this)) {
            Privileged.State.NO_SHIZUKU -> Triple<Int, Int, () -> Unit>(R.string.setup_shizuku_missing, R.string.setup_get_shizuku) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_URL)))
            }
            Privileged.State.NOT_RUNNING -> Triple(R.string.setup_shizuku_stopped, R.string.setup_open_shizuku) { openShizuku() }
            Privileged.State.NO_PERMISSION -> Triple(R.string.setup_shizuku_ask, R.string.setup_allow) { Privileged.requestPermission() }
            Privileged.State.DENIED -> Triple(R.string.setup_shizuku_denied, R.string.setup_open_shizuku) { openShizuku() }
            else -> Triple(R.string.setup_shizuku_ready, 0) { }
        }
        shizukuStatus.setText(status)
        show(shizukuButton, action) { onClick() }

        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasBluetooth() -> {
                bluetoothStatus.setText(R.string.setup_bluetooth_ready)
                show(bluetoothButton, 0) {}
            }
            bluetoothAsked && !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT) -> {
                bluetoothStatus.setText(R.string.setup_bluetooth_blocked)
                show(bluetoothButton, R.string.setup_open_settings) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                }
            }
            else -> {
                bluetoothStatus.setText(R.string.setup_bluetooth_ask)
                show(bluetoothButton, R.string.setup_allow) { askBluetooth() }
            }
        }

        refreshLauncher()
    }

    private fun refreshLauncher() {
        val shown = inLauncher()
        launcherStatus.setText(if (shown) R.string.setup_launcher_shown else R.string.setup_launcher_hidden)
        show(launcherButton, if (shown) R.string.setup_hide_launcher else R.string.setup_show_launcher) {
            val state = if (shown) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            packageManager.setComponentEnabledSetting(launcherEntry, state, PackageManager.DONT_KILL_APP)
            refreshLauncher()
        }
    }

    private fun inLauncher() = packageManager.getComponentEnabledSetting(launcherEntry) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    private fun askBluetooth() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        bluetoothAsked = true
        requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), BLUETOOTH_REQUEST)
    }

    private fun hasBluetooth() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun openShizuku() {
        packageManager.getLaunchIntentForPackage(Privileged.SHIZUKU_PACKAGE)?.let(::startActivity)
    }

    private fun show(button: Button, label: Int, onClick: () -> Unit) {
        button.visibility = if (label == 0) View.GONE else View.VISIBLE
        if (label != 0) button.setText(label)
        button.setOnClickListener { onClick() }
    }

    private fun buttonParams() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }

    private fun heading(value: String) = text(value, 20f).apply { setPadding(0, dp(28), 0, dp(8)) }

    private fun text(value: String, sp: Float) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val BLUETOOTH_REQUEST = 1
        const val SHIZUKU_URL = "https://shizuku.rikka.app/"
    }
}
