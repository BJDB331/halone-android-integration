package dev.halone.plugin.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.halone.plugin.ViewPanelSession
import java.util.concurrent.Executors

class AppListSession(private val provider: AndroidAppsProvider) : ViewPanelSession() {
    private val main = Handler(Looper.getMainLooper())
    private val iconLoader = Executors.newSingleThreadExecutor()
    private val icons = LruCache<String, Bitmap>(ICON_CACHE)
    private var query = ""
    private var shown: List<AppCatalog.PhoneApp> = emptyList()
    private lateinit var adapter: Adapter
    private lateinit var empty: TextView
    private lateinit var root: FrameLayout
    private var menu: View? = null
    private val onListChanged: () -> Unit = { filter() }
    private val previews = LruCache<String, Bitmap>(RecentApps.MAX)
    private lateinit var recentStrip: View
    private lateinit var recentRow: LinearLayout
    private val onRecentsChanged: () -> Unit = { showRecents() }

    override fun onCreateView(context: Context): View {
        host.setTitle(context.getString(R.string.list_name))
        provider.listChanged += onListChanged

        val search = EditText(context).apply {
            hint = context.getString(R.string.search_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable) {
                    query = s.toString().trim()
                    filter()
                }
            })
        }

        adapter = Adapter(context)
        val grid = GridView(context).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = CELL_WIDTH
            stretchMode = GridView.STRETCH_SPACING_UNIFORM
            verticalSpacing = SPACING
            setPadding(0, SPACING, 0, SPACING)
            clipToPadding = false
            adapter = this@AppListSession.adapter
            setOnItemClickListener { _, _, position, _ -> provider.launchApp(shown[position].id) }
            setOnItemLongClickListener { _, cell, position, _ ->
                showMenu(cell, shown[position])
                true
            }
        }

        empty = TextView(context).apply {
            text = context.getString(R.string.no_apps)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        filter()

        recentRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val recentTitle = TextView(context).apply {
            text = context.getString(R.string.recent_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.argb(0xB3, 0xff, 0xff, 0xff))
            setPadding(0, SPACING, 0, SPACING / 2)
        }
        recentStrip = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(recentTitle)
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(recentRow)
            })
        }
        provider.recents.addListener(onRecentsChanged)
        showRecents()

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(0x16, 0x18, 0x1d))
            setPadding(PADDING, PADDING, PADDING, 0)
            addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(recentStrip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        root = FrameLayout(context).apply { addView(content) }
        return root
    }

    override fun onClose() {
        provider.listChanged -= onListChanged
        provider.recents.removeListener(onRecentsChanged)
        iconLoader.shutdownNow()
        super.onClose()
    }

    /** Recents, newest first, each a still of its window; hidden while searching or when there are none. */
    private fun showRecents() {
        if (!::recentRow.isInitialized) return
        val entries = provider.recents.entries.filter { provider.catalog.find(it.id) != null }
        recentStrip.visibility = if (entries.isEmpty() || query.isNotEmpty()) View.GONE else View.VISIBLE
        recentRow.removeAllViews()
        val context = recentRow.context
        entries.forEach { entry ->
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = GradientDrawable().apply {
                    setColor(Color.rgb(0x22, 0x25, 0x2c))
                    cornerRadius = PREVIEW_RADIUS
                }
                clipToOutline = true
            }
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 0, SPACING, 0)
                val value = TypedValue()
                if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) setBackgroundResource(value.resourceId)
                addView(image, LinearLayout.LayoutParams(PREVIEW_WIDTH, PREVIEW_HEIGHT))
                addView(TextView(context).apply {
                    text = entry.label
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                }, LinearLayout.LayoutParams(PREVIEW_WIDTH, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 4 })
                setOnClickListener { provider.launchApp(entry.id) }
            }
            recentRow.addView(card)
            loadPreview(entry.id, image)
        }
    }

    private fun loadPreview(id: String, into: ImageView) {
        val key = id + "@" + provider.recents.previewVersion(id)
        into.tag = key
        previews.get(key)?.let { return into.setImageBitmap(it) }
        into.setImageDrawable(null)
        iconLoader.execute {
            val bitmap = provider.recents.previewOf(id) ?: provider.catalog.find(id)?.let { provider.catalog.icon(it.component, ICON_SIZE) }
                ?: return@execute
            main.post {
                previews.put(key, bitmap)
                if (into.tag == key) into.setImageBitmap(bitmap)
            }
        }
    }

    private fun filter() {
        val all = provider.catalog.apps
        shown = if (query.isEmpty()) all else all.filter { it.label.contains(query, ignoreCase = true) }
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
        if (::empty.isInitialized) empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        showRecents()
    }

    /** Drawn in the panel's own views: a PopupMenu's separate window gets none of the panel's input. */
    private fun showMenu(anchor: View, app: AppCatalog.PhoneApp) {
        dismissMenu()
        val context = anchor.context
        val onHome = app.id in provider.home
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = MENU_WIDTH
            setPadding(0, MENU_PADDING, 0, MENU_PADDING)
            background = GradientDrawable().apply {
                setColor(Color.rgb(0x2a, 0x2d, 0x34))
                cornerRadius = MENU_RADIUS
            }
            elevation = MENU_ELEVATION
            addView(menuItem(context, R.string.menu_open) { provider.launchApp(app.id) })
            addView(menuItem(context, if (onHome) R.string.menu_remove_home else R.string.menu_add_home) { provider.setOnHome(app.id, !onHome) })
        }
        list.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val anchorAt = IntArray(2).also(anchor::getLocationInWindow)
        val rootAt = IntArray(2).also(root::getLocationInWindow)
        val x = anchorAt[0] - rootAt[0] + anchor.width / 2
        val y = anchorAt[1] - rootAt[1] + anchor.height / 2
        val scrim = FrameLayout(context).apply {
            setOnClickListener { dismissMenu() }
            addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = x.coerceAtMost(root.width - list.measuredWidth).coerceAtLeast(0)
                topMargin = y.coerceAtMost(root.height - list.measuredHeight).coerceAtLeast(0)
            })
        }
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        menu = scrim
    }

    private fun menuItem(context: Context, text: Int, action: () -> Unit) = TextView(context).apply {
        setText(text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(MENU_ITEM_PADDING, MENU_ITEM_PADDING / 2, MENU_ITEM_PADDING, MENU_ITEM_PADDING / 2)
        val value = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) setBackgroundResource(value.resourceId)
        setOnClickListener {
            dismissMenu()
            action()
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun dismissMenu() {
        menu?.let(root::removeView)
        menu = null
    }

    private fun loadIcon(app: AppCatalog.PhoneApp, into: ImageView) {
        into.tag = app.id
        val cached = icons.get(app.id)
        if (cached != null) return into.setImageBitmap(cached)
        into.setImageDrawable(null)
        iconLoader.execute {
            val bitmap = provider.catalog.icon(app.component, ICON_SIZE) ?: return@execute
            main.post {
                icons.put(app.id, bitmap)
                if (into.tag == app.id) into.setImageBitmap(bitmap)
            }
        }
    }

    private inner class Adapter(private val context: Context) : BaseAdapter() {
        override fun getCount() = shown.size

        override fun getItem(position: Int) = shown[position]

        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = convertView as? LinearLayout ?: newCell()
            val app = shown[position]
            loadIcon(app, cell.getChildAt(0) as ImageView)
            (cell.getChildAt(1) as TextView).text = app.label
            return cell
        }

        private fun newCell() = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(SPACING / 2, SPACING / 2, SPACING / 2, SPACING / 2)
            val value = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) setBackgroundResource(value.resourceId)
            addView(ImageView(context), LinearLayout.LayoutParams(ICON_SIZE, ICON_SIZE))
            addView(TextView(context).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 6 })
        }
    }

    private companion object {
        const val PADDING = 20
        const val SPACING = 12
        const val CELL_WIDTH = 104
        const val ICON_SIZE = 56
        const val ICON_CACHE = 300
        const val MENU_WIDTH = 200
        const val MENU_PADDING = 8
        const val MENU_ITEM_PADDING = 32
        const val MENU_RADIUS = 12f
        const val MENU_ELEVATION = 8f
        const val PREVIEW_WIDTH = 96
        const val PREVIEW_HEIGHT = 150
        const val PREVIEW_RADIUS = 10f
    }
}
