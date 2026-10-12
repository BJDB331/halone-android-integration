package dev.halone.plugin.android

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A message drawn into a panel's surface through a private display of the
 * plugin's own, until the privileged display takes the surface over:
 * [release] disconnects it so another producer can connect.
 */
class NoticeCard(private val context: Context, surface: Surface, widthPx: Int, heightPx: Int, densityDpi: Int) {
    private val display: VirtualDisplay = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
        "halone-android-notice",
        widthPx,
        heightPx,
        densityDpi,
        surface,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
    )
    private val presentation = Presentation(context, display.display, android.R.style.Theme_DeviceDefault_NoActionBar)
    private val title = text(22f, Color.WHITE)
    private val body = text(16f, Color.rgb(0xb8, 0xbc, 0xc6))

    init {
        presentation.setContentView(LinearLayout(presentation.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(PADDING, PADDING, PADDING, PADDING)
            setBackgroundColor(Color.rgb(0x16, 0x18, 0x1d))
            addView(title)
            addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 16 })
        })
        presentation.show()
    }

    fun show(title: String, body: String) {
        this.title.text = title
        this.body.text = body
    }

    fun resize(widthPx: Int, heightPx: Int, densityDpi: Int) = display.resize(widthPx, heightPx, densityDpi)

    fun release() {
        presentation.dismiss()
        display.release()
    }

    private fun text(sp: Float, colour: Int) = TextView(presentation.context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(colour)
        gravity = Gravity.CENTER
    }

    private companion object {
        const val PADDING = 40
    }
}
