package xyz.sabok.umtomi.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import xyz.sabok.umtomi.R

internal enum class WorkspaceTab(val title: Int, val icon: Int) {
    SEARCH(R.string.workspace_search, android.R.drawable.ic_menu_search),
    RECOMMENDATIONS(R.string.workspace_recommendations, R.drawable.shuffle_variant),
    DOWNLOADS(R.string.main_drawer_downloads, R.drawable.ic_download),
    HISTORY(R.string.main_drawer_history, R.drawable.history),
    FAVORITES(R.string.main_drawer_favorite, R.drawable.ic_star_filled),
    BOOKSHELF(R.string.bookshelf, R.drawable.book_open)
}

internal object WorkspaceTabConfig {
    val defaultOrder = WorkspaceTab.entries.toList()
    val defaultInMore = setOf(WorkspaceTab.RECOMMENDATIONS, WorkspaceTab.DOWNLOADS)

    fun sanitizeOrder(names: List<String>): List<WorkspaceTab> {
        val mapped = names.mapNotNull { id -> WorkspaceTab.entries.firstOrNull { it.name == id } }
        return (mapped + WorkspaceTab.entries).distinct()
    }

    fun sanitizeMore(order: List<WorkspaceTab>, names: Set<String>): Set<WorkspaceTab> {
        val filtered = order.filter { it.name in names }
        return filtered.take((order.size - 1).coerceAtLeast(0)).toSet()
    }

    fun visibleBarTabs(order: List<WorkspaceTab>, moreEnabled: Boolean, inMore: Set<WorkspaceTab>): List<WorkspaceTab> =
        if (moreEnabled) order.filterNot(inMore::contains) else order

    fun overflowTabs(order: List<WorkspaceTab>, moreEnabled: Boolean, inMore: Set<WorkspaceTab>): List<WorkspaceTab> =
        if (moreEnabled) order.filter(inMore::contains) else emptyList()

    fun moveGroupOrder(order: List<WorkspaceTab>, group: List<WorkspaceTab>): List<WorkspaceTab> {
        val members = group.toSet()
        val iterator = group.iterator()
        return order.map { if (it in members && iterator.hasNext()) iterator.next() else it }
    }
}

internal class WorkspaceNavigation(
    private val activity: BaseActivity,
    private val tabs: LinearLayout,
    private val activate: (WorkspaceTab) -> Unit,
) {
    private val preferences = activity.getSharedPreferences("workspace_tabs", Context.MODE_PRIVATE)
    private fun storedOrder() = WorkspaceTabConfig.sanitizeOrder(preferences.getString("order", "").orEmpty().split(','))
    private var order = storedOrder()
    private var moreEnabled = preferences.getBoolean("more_enabled", false)
    private fun storedMore() = WorkspaceTabConfig.sanitizeMore(
        order,
        preferences.getStringSet("in_more", WorkspaceTabConfig.defaultInMore.map { it.name }.toSet()).orEmpty()
    )
    private var inMore = storedMore()
    private var selected = WorkspaceTab.SEARCH

    fun refresh() {
        order = storedOrder()
        moreEnabled = preferences.getBoolean("more_enabled", false)
        inMore = storedMore()
        render()
    }

    fun render(selection: WorkspaceTab = selected) {
        selected = selection
        tabs.removeAllViews()
        val visible = WorkspaceTabConfig.visibleBarTabs(order, moreEnabled, inMore)
        for (tab in visible) {
            append(tab.title, tab.icon, tab == selected, click = { activate(tab) }, longClick = ::showSettings)
        }
        if (moreEnabled) {
            append(R.string.workspace_more, R.drawable.reader_more, selected in inMore, click = ::showMoreSheet, longClick = ::showSettings)
        }
    }

    private fun showMoreSheet() {
        val style = PortStyle(activity)
        val dialog = BottomSheetDialog(activity)
        val container = style.stack().apply {
            background = style.rounded(style.surface, 20)
            setPadding(style.dp(16), style.dp(14), style.dp(16), style.dp(24))
        }
        container.addView(View(activity).apply {
            background = style.rounded(style.divider, 4)
        }, LinearLayout.LayoutParams(style.dp(36), style.dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = style.dp(14)
        })
        container.addView(style.text(activity.getString(R.string.workspace_more), 20f, bold = true).apply {
            setPadding(style.dp(8), 0, style.dp(8), style.dp(10))
        })

        fun addMenuRow(title: Int, icon: Int, active: Boolean = false, onClick: () -> Unit) {
            val row = style.row().apply {
                minimumHeight = style.dp(50)
                setPadding(style.dp(10), style.dp(8), style.dp(10), style.dp(8))
                background = style.ripple(if (active) style.selected else android.graphics.Color.TRANSPARENT, 12)
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    onClick()
                }
            }
            val tint = if (active) style.accent else style.foreground
            row.addView(ImageView(activity).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(tint)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(style.dp(24), style.dp(24)).apply { marginEnd = style.dp(14) })
            row.addView(style.text(activity.getString(title), 16f, tint, bold = active), LinearLayout.LayoutParams(0, -2, 1f))
            container.addView(row, style.matchWrap())
        }

        val entries = WorkspaceTabConfig.overflowTabs(order, moreEnabled, inMore)
        entries.forEach { tab ->
            addMenuRow(tab.title, tab.icon, active = tab == selected) { activate(tab) }
        }
        if (entries.isNotEmpty()) {
            container.addView(View(activity).apply { setBackgroundColor(style.divider) },
                LinearLayout.LayoutParams(-1, style.dp(1)).apply { topMargin = style.dp(8); bottomMargin = style.dp(8) })
        }
        addMenuRow(R.string.main_settings, android.R.drawable.ic_menu_preferences) {
            activity.startActivity(Intent(activity, SettingsActivity::class.java))
        }
        addMenuRow(R.string.workspace_tab_settings, R.drawable.swap_horizontal) { showSettings() }

        dialog.setContentView(container)
        dialog.show()
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    private fun append(title: Int, icon: Int, active: Boolean, click: () -> Unit, longClick: () -> Unit) {
        val accent = ContextCompat.getColor(activity, if (active) R.color.workspace_accent else R.color.workspace_muted)
        val item = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = dp(49)
            setPadding(0, dp(3), 0, dp(3))
            contentDescription = activity.getString(title)
            isSelected = active
            isFocusable = true
            background = RippleDrawable(ColorStateList.valueOf(0x226086AC), null, null)
            setOnClickListener { click() }
            setOnLongClickListener { longClick(); true }
        }
        item.addView(ImageView(activity).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(accent)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setPadding(dp(6), dp(3), dp(6), dp(3))
        }, LinearLayout.LayoutParams(dp(38), dp(26)))
        item.addView(TextView(activity).apply {
            setText(title)
            textSize = 10f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(accent)
            setTypeface(typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        tabs.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    fun showSettings() {
        activity.startActivity(Intent(activity, PortTabBarEditorActivity::class.java))
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
