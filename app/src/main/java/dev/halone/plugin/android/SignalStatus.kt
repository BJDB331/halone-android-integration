package dev.halone.plugin.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log
import dev.halone.plugin.StatusItem

/** Wi-Fi and mobile signal meters in the dock, set again only when what they show changes. */
class SignalStatus(
    private val context: Context,
    private val setItem: (StatusItem) -> Unit,
    private val removeItem: (String) -> Unit,
) {
    private data class Shown(val level: Int, val text: String, val label: String)

    private val main = Handler(Looper.getMainLooper())
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.getSystemService(WifiManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val shown = HashMap<String, Shown>()

    private val wifiNetworks = HashMap<Network, Int>()
    private var mobileLevel = NO_SIGNAL
    private var mobileInService = false
    private var networkType = ""
    private var telephonyCallback: TelephonyCallback? = null

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val rssi = (capabilities.transportInfo as? WifiInfo)?.rssi ?: capabilities.signalStrength
            main.post {
                wifiNetworks[network] = wifiLevel(rssi)
                update()
            }
        }

        override fun onLost(network: Network) {
            main.post {
                wifiNetworks.remove(network)
                update()
            }
        }
    }

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            main.post { update() }
        }

        override fun onLost(network: Network) {
            main.post { update() }
        }
    }

    fun start() {
        val wifiRequest = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        connectivity.registerNetworkCallback(wifiRequest, wifiCallback)
        connectivity.registerDefaultNetworkCallback(defaultCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listenToTelephony()
        update()
    }

    fun stop() {
        runCatching { connectivity.unregisterNetworkCallback(wifiCallback) }
        runCatching { connectivity.unregisterNetworkCallback(defaultCallback) }
        telephonyCallback?.let { runCatching { telephony.unregisterTelephonyCallback(it) } }
        telephonyCallback = null
        shown.keys.toList().forEach(removeItem)
        shown.clear()
    }

    private fun listenToTelephony() {
        val callback = object : TelephonyCallback(),
            TelephonyCallback.SignalStrengthsListener,
            TelephonyCallback.ServiceStateListener,
            TelephonyCallback.DisplayInfoListener {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                mobileLevel = signalStrength.level
                update()
            }

            override fun onServiceStateChanged(serviceState: ServiceState) {
                mobileInService = serviceState.state == ServiceState.STATE_IN_SERVICE
                update()
            }

            override fun onDisplayInfoChanged(displayInfo: TelephonyDisplayInfo) {
                networkType = networkTypeName(displayInfo)
                update()
            }
        }
        try {
            telephony.registerTelephonyCallback(context.mainExecutor, callback)
            telephonyCallback = callback
        } catch (e: SecurityException) {
            Log.w(TAG, "telephony callback", e)
            listenWithoutDisplayInfo()
        }
    }

    private fun listenWithoutDisplayInfo() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val callback = object : TelephonyCallback(),
            TelephonyCallback.SignalStrengthsListener,
            TelephonyCallback.ServiceStateListener {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                mobileLevel = signalStrength.level
                update()
            }

            override fun onServiceStateChanged(serviceState: ServiceState) {
                mobileInService = serviceState.state == ServiceState.STATE_IN_SERVICE
                update()
            }
        }
        runCatching { telephony.registerTelephonyCallback(context.mainExecutor, callback) }
            .onSuccess { telephonyCallback = callback }
            .onFailure { Log.w(TAG, "telephony callback without display info", it) }
    }

    private fun update() {
        val wifi = wifiNetworks.values.maxOrNull()
        show(
            WIFI,
            wifi?.let { Shown(it, "", context.getString(R.string.status_wifi, levelName(it, WIFI_LEVELS))) },
        ) { wifiIcon(it.level) }

        val mobile = if (wifi == null && mobileInService && telephony.simState == TelephonyManager.SIM_STATE_READY) mobileLevel.coerceAtLeast(0) else null
        val dataOn = runCatching { telephony.isDataEnabled }.getOrDefault(false)
        val text = if (dataOn) networkType else ""
        show(
            MOBILE,
            mobile?.let { Shown(it, text, context.getString(R.string.status_mobile, levelName(it, MOBILE_LEVELS))) },
        ) { mobileIcon(it.level) }
    }

    private inline fun show(id: String, next: Shown?, icon: (Shown) -> Bitmap) {
        if (shown[id] == next) return
        if (next == null) {
            shown.remove(id)
            removeItem(id)
            return
        }
        shown[id] = next
        setItem(StatusItem(id, icon(next), label = next.label, text = next.text))
    }

    private fun wifiLevel(rssi: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wifiManager.calculateSignalLevel(rssi) * (WIFI_LEVELS - 1) / wifiManager.maxSignalLevel.coerceAtLeast(1)
        } else {
            @Suppress("DEPRECATION")
            WifiManager.calculateSignalLevel(rssi, WIFI_LEVELS)
        }

    private fun levelName(level: Int, levels: Int): String {
        val names = context.resources.getStringArray(R.array.signal_levels)
        return names[(level * (names.size - 1) / (levels - 1)).coerceIn(0, names.size - 1)]
    }

    private fun networkTypeName(info: TelephonyDisplayInfo): String = when (info.overrideNetworkType) {
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA,
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "LTE+"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "LTE+"
        else -> when (info.networkType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA -> "H+"
            TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE -> "E"
            TelephonyManager.NETWORK_TYPE_GPRS -> "G"
            else -> ""
        }
    }

    private fun wifiIcon(level: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val cx = ICON_PX / 2f
        val bottom = ICON_PX * 0.86f
        for (ring in WIFI_LEVELS - 1 downTo 0) {
            val radius = ICON_PX * (0.18f + 0.68f * (ring + 1) / WIFI_LEVELS)
            paint.alpha = if (ring <= level) FULL_ALPHA else EMPTY_ALPHA
            val wedge = Path().apply {
                moveTo(cx, bottom)
                arcTo(RectF(cx - radius, bottom - radius, cx + radius, bottom + radius), 225f, 90f)
                close()
            }
            canvas.save()
            if (ring > 0) {
                val inner = ICON_PX * (0.18f + 0.68f * ring / WIFI_LEVELS) + GAP_PX
                canvas.clipOutPath(Path().apply { addCircle(cx, bottom, inner, Path.Direction.CW) })
            }
            canvas.drawPath(wedge, paint)
            canvas.restore()
        }
        return bitmap
    }

    private fun mobileIcon(level: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val bars = MOBILE_LEVELS - 1
        val gap = ICON_PX * 0.07f
        val width = (ICON_PX * 0.9f - gap * (bars - 1)) / bars
        val left = ICON_PX * 0.05f
        val bottom = ICON_PX * 0.9f
        for (bar in 0 until bars) {
            val height = ICON_PX * 0.8f * (bar + 1) / bars
            paint.alpha = if (bar < level) FULL_ALPHA else EMPTY_ALPHA
            val x = left + bar * (width + gap)
            canvas.drawRoundRect(RectF(x, bottom - height, x + width, bottom), 2f, 2f, paint)
        }
        return bitmap
    }

    companion object {
        const val WIFI = "wifi"
        const val MOBILE = "mobile"
        private const val TAG = "HaloneAndroidApps"
        private const val WIFI_LEVELS = 4
        private const val MOBILE_LEVELS = 5
        private const val NO_SIGNAL = 0
        private const val ICON_PX = 48
        private const val GAP_PX = 3f
        private const val FULL_ALPHA = 255
        private const val EMPTY_ALPHA = 80
    }
}
