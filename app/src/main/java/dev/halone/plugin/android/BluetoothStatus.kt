package dev.halone.plugin.android

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.halone.plugin.StatusItem

/** Connections and the state broadcast need `BLUETOOTH_CONNECT`; without it the item shows on and off only, polled. */
class BluetoothStatus(
    private val context: Context,
    private val setItem: (StatusItem) -> Unit,
    private val removeItem: (String) -> Unit,
) {
    private enum class State { OFF, ON, CONNECTED }

    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var shown: State? = null
    private var registered = false
    private val poll = object : Runnable {
        override fun run() {
            update()
            if (hasPermission()) listen() else main.postDelayed(this, POLL_MS)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = update()
    }

    fun start() {
        if (adapter == null) return
        listen()
        update()
    }

    fun stop() {
        main.removeCallbacks(poll)
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
        if (shown != null) removeItem(ID)
        shown = null
    }

    private fun listen() {
        main.removeCallbacks(poll)
        if (!hasPermission()) {
            main.postDelayed(poll, POLL_MS)
            return
        }
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        registered = true
    }

    private fun update() {
        val bluetooth = adapter ?: return
        val state = when {
            !runCatching { bluetooth.isEnabled }.getOrDefault(false) -> State.OFF
            hasPermission() && connected(bluetooth) -> State.CONNECTED
            else -> State.ON
        }
        if (state == shown) return
        shown = state
        val label = context.getString(
            when (state) {
                State.OFF -> R.string.status_bluetooth_off
                State.ON -> R.string.status_bluetooth_on
                State.CONNECTED -> R.string.status_bluetooth_connected
            },
        )
        setItem(StatusItem(ID, icon(state), label = label))
    }

    @Suppress("MissingPermission")
    private fun connected(bluetooth: BluetoothAdapter): Boolean = PROFILES.any {
        runCatching { bluetooth.getProfileConnectionState(it) == BluetoothProfile.STATE_CONNECTED }.getOrDefault(false)
    }

    private fun hasPermission() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun icon(state: State): Bitmap {
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = if (state == State.OFF) EMPTY_ALPHA else FULL_ALPHA
            style = Paint.Style.STROKE
            strokeWidth = ICON_PX * 0.08f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        val s = ICON_PX.toFloat()
        val rune = Path().apply {
            moveTo(s * 0.3f, s * 0.3f)
            lineTo(s * 0.7f, s * 0.68f)
            lineTo(s * 0.5f, s * 0.88f)
            lineTo(s * 0.5f, s * 0.12f)
            lineTo(s * 0.7f, s * 0.32f)
            lineTo(s * 0.3f, s * 0.7f)
        }
        canvas.drawPath(rune, paint)
        if (state == State.CONNECTED) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(s * 0.14f, s * 0.5f, s * 0.07f, paint)
            canvas.drawCircle(s * 0.86f, s * 0.5f, s * 0.07f, paint)
        }
        return bitmap
    }

    companion object {
        const val ID = "bluetooth"
        private const val POLL_MS = 5000L
        private const val ICON_PX = 48
        private const val FULL_ALPHA = 255
        private const val EMPTY_ALPHA = 90
        /** `BluetoothProfile.HID_HOST`, hidden: keyboards and mice the phone is connected to. */
        private const val HID_HOST = 4
        private val PROFILES = listOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP, BluetoothProfile.LE_AUDIO, HID_HOST)
    }
}
