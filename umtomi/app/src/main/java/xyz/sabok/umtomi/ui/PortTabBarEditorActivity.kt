package xyz.sabok.umtomi.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import xyz.sabok.umtomi.R
import java.util.Collections

/** Drag-and-drop and one-tap editor for the bottom tab bar and More overflow group. */
class PortTabBarEditorActivity : BaseActivity() {
    private val style by lazy { PortStyle(this) }
    private val preferences by lazy { getSharedPreferences("workspace_tabs", Context.MODE_PRIVATE) }
    private var order: List<WorkspaceTab> = emptyList()
    private var moreEnabled = false
    private var inMore: Set<WorkspaceTab> = emptySet()

    private lateinit var previewBar: LinearLayout
    private lateinit var moreSwitch: SwitchCompat
    private lateinit var list: RecyclerView
    private lateinit var adapter: EditorAdapter
    private lateinit var touchHelper: ItemTouchHelper

    private sealed class Row {
        data class Header(val titleRes: Int, val isMoreHeader: Boolean) : Row()
        data class TabItem(val tab: WorkspaceTab, val inMore: Boolean) : Row()
        data object EmptyMoreHint : Row()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadState()

        val root = style.stack().apply { setBackgroundColor(style.background) }
        val top = style.row().apply {
            setPadding(style.dp(16), style.dp(8), style.dp(16), style.dp(8))
            addView(style.text(getString(R.string.workspace_tab_settings), 26f, bold = true),
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(style.action(getString(R.string.workspace_tab_reset)) { resetToDefaults() }.apply {
                setPadding(style.dp(12), style.dp(6), style.dp(12), style.dp(6))
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = style.dp(8) })
            addView(style.action(getString(R.string.port_settings_done), prominent = true) { finish() }.apply {
                setPadding(style.dp(14), style.dp(6), style.dp(14), style.dp(6))
            })
        }
        root.addView(top, style.matchWrap())

        val previewCard = style.stack().apply {
            background = style.rounded(style.surface, 14)
            setPadding(style.dp(8), style.dp(10), style.dp(8), style.dp(8))
        }
        previewCard.addView(style.text(getString(R.string.workspace_tab_preview), 12f, style.muted, bold = true).apply {
            setPadding(style.dp(6), 0, style.dp(6), style.dp(6))
        })
        previewBar = style.row().apply {
            background = style.rounded(style.barBackground, 10)
            setPadding(style.dp(2), style.dp(4), style.dp(2), style.dp(4))
        }
        previewCard.addView(previewBar, style.matchWrap())
        root.addView(previewCard, style.matchWrap().apply {
            setMargins(style.dp(16), style.dp(4), style.dp(16), style.dp(10))
        })

        val switchCard = style.stack().apply {
            background = style.rounded(style.surface, 14)
            setPadding(style.dp(14), style.dp(4), style.dp(14), style.dp(4))
        }
        moreSwitch = SwitchCompat(this).apply {
            setText(R.string.workspace_enable_more)
            setTextColor(style.foreground)
            textSize = 16f
            minHeight = style.dp(50)
            isChecked = moreEnabled
            setOnCheckedChangeListener { _, checked ->
                if (moreEnabled != checked) {
                    moreEnabled = checked
                    saveState()
                    rebuildRows()
                    renderPreview()
                }
            }
        }
        switchCard.addView(moreSwitch, style.matchWrap())
        root.addView(switchCard, style.matchWrap().apply {
            setMargins(style.dp(16), 0, style.dp(16), style.dp(8))
        })

        root.addView(style.text(getString(R.string.workspace_tab_editor_hint), 13f, style.muted).apply {
            setPadding(style.dp(20), 0, style.dp(20), style.dp(8))
        })

        adapter = EditorAdapter()
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@PortTabBarEditorActivity)
            adapter = this@PortTabBarEditorActivity.adapter
            itemAnimator = null
            setPadding(style.dp(16), 0, style.dp(16), style.dp(24))
            clipToPadding = false
        }
        touchHelper = ItemTouchHelper(DragCallback())
        touchHelper.attachToRecyclerView(list)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        style.applySystemBars(this, root)
        rebuildRows()
        renderPreview()
    }

    private fun loadState() {
        order = WorkspaceTabConfig.sanitizeOrder(preferences.getString("order", "").orEmpty().split(','))
        moreEnabled = preferences.getBoolean("more_enabled", false)
        inMore = WorkspaceTabConfig.sanitizeMore(
            order,
            preferences.getStringSet("in_more", WorkspaceTabConfig.defaultInMore.map { it.name }.toSet()).orEmpty()
        )
    }

    private fun saveState() {
        preferences.edit()
            .putString("order", order.joinToString(",") { it.name })
            .putBoolean("more_enabled", moreEnabled)
            .putStringSet("in_more", inMore.map { it.name }.toSet())
            .apply()
    }

    private fun resetToDefaults() {
        order = WorkspaceTabConfig.defaultOrder
        moreEnabled = false
        inMore = WorkspaceTabConfig.defaultInMore
        moreSwitch.isChecked = false
        saveState()
        rebuildRows()
        renderPreview()
    }

    private fun rebuildRows() {
        val items = mutableListOf<Row>()
        val barTabs = WorkspaceTabConfig.visibleBarTabs(order, moreEnabled, inMore)
        items += Row.Header(R.string.workspace_on_tab_bar, isMoreHeader = false)
        barTabs.forEach { items += Row.TabItem(it, inMore = false) }
        if (moreEnabled) {
            val overflow = WorkspaceTabConfig.overflowTabs(order, moreEnabled, inMore)
            items += Row.Header(R.string.workspace_in_more, isMoreHeader = true)
            if (overflow.isEmpty()) items += Row.EmptyMoreHint
            else overflow.forEach { items += Row.TabItem(it, inMore = true) }
        }
        adapter.rows.clear()
        adapter.rows.addAll(items)
        adapter.notifyDataSetChanged()
    }

    private fun renderPreview() {
        previewBar.removeAllViews()
        val barTabs = WorkspaceTabConfig.visibleBarTabs(order, moreEnabled, inMore)
        barTabs.forEachIndexed { index, tab ->
            appendPreviewItem(tab.title, tab.icon, active = index == 0)
        }
        if (moreEnabled) {
            appendPreviewItem(R.string.workspace_more, R.drawable.reader_more, active = false)
        }
    }

    private fun appendPreviewItem(title: Int, icon: Int, active: Boolean) {
        val tint = if (active) style.accent else style.muted
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, style.dp(4), 0, style.dp(4))
        }
        item.addView(ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(tint)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, LinearLayout.LayoutParams(style.dp(22), style.dp(20)))
        item.addView(TextView(this).apply {
            setText(title)
            textSize = 9.5f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(tint)
            if (active) setTypeface(typeface, Typeface.BOLD)
        }, style.matchWrap())
        previewBar.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun moveWithinSection(tab: WorkspaceTab, inMoreSection: Boolean, delta: Int) {
        val group = (if (inMoreSection) WorkspaceTabConfig.overflowTabs(order, moreEnabled, inMore)
        else WorkspaceTabConfig.visibleBarTabs(order, moreEnabled, inMore)).toMutableList()
        val index = group.indexOf(tab)
        val target = index + delta
        if (index < 0 || target !in group.indices) return
        Collections.swap(group, index, target)
        order = WorkspaceTabConfig.moveGroupOrder(order, group)
        saveState()
        rebuildRows()
        renderPreview()
    }

    private fun toggleMorePlacement(tab: WorkspaceTab, currentlyInMore: Boolean) {
        val barTabs = WorkspaceTabConfig.visibleBarTabs(order, moreEnabled, inMore)
        if (!currentlyInMore && barTabs.size <= 1) return
        inMore = if (currentlyInMore) inMore - tab else inMore + tab
        saveState()
        rebuildRows()
        renderPreview()
    }

    private fun commitFromAdapterRows() {
        val barList = mutableListOf<WorkspaceTab>()
        val moreList = mutableListOf<WorkspaceTab>()
        var readingMore = false
        for (row in adapter.rows) {
            when (row) {
                is Row.Header -> readingMore = row.isMoreHeader
                is Row.TabItem -> if (readingMore) moreList += row.tab else barList += row.tab
                Row.EmptyMoreHint -> Unit
            }
        }
        if (barList.isEmpty() && moreList.isNotEmpty()) {
            barList += moreList.removeAt(0)
        }
        order = (barList + moreList + WorkspaceTab.entries).distinct()
        inMore = if (moreEnabled) moreList.toSet() else inMore
        saveState()
        rebuildRows()
        renderPreview()
    }

    private inner class DragCallback : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        private var dragging = false

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            val pos = viewHolder.bindingAdapterPosition
            if (pos !in adapter.rows.indices || adapter.rows[pos] !is Row.TabItem) return 0
            return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
        }

        override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from !in adapter.rows.indices || to !in adapter.rows.indices) return false
            if (to == 0) return false // Cannot move above the "Tab bar" header.

            val item = adapter.rows[from] as? Row.TabItem ?: return false
            val moreHeaderIndex = adapter.rows.indexOfFirst { it is Row.Header && it.isMoreHeader }
            if (moreHeaderIndex > 0) {
                val barCount = adapter.rows.subList(1, moreHeaderIndex).count { it is Row.TabItem }
                val movingOutOfBar = from < moreHeaderIndex && to >= moreHeaderIndex
                if (movingOutOfBar && barCount <= 1) return false
            }

            if (from < to) {
                for (i in from until to) Collections.swap(adapter.rows, i, i + 1)
            } else {
                for (i in from downTo to + 1) Collections.swap(adapter.rows, i, i - 1)
            }
            adapter.notifyItemMoved(from, to)
            dragging = true
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                viewHolder?.itemView?.alpha = 0.88f
                viewHolder?.itemView?.scaleX = 1.02f
                viewHolder?.itemView?.scaleY = 1.02f
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewHolder.itemView.alpha = 1f
            viewHolder.itemView.scaleX = 1f
            viewHolder.itemView.scaleY = 1f
            if (dragging) {
                dragging = false
                commitFromAdapterRows()
            }
        }

        override fun isLongPressDragEnabled() = true
    }

    private inner class EditorAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        val rows = mutableListOf<Row>()

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Header -> 0
            is Row.TabItem -> 1
            Row.EmptyMoreHint -> 2
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
            0 -> object : RecyclerView.ViewHolder(style.text("", 14f, style.muted, bold = true).apply {
                setPadding(style.dp(6), style.dp(14), style.dp(6), style.dp(6))
                ViewCompat.setAccessibilityHeading(this, true)
            }) {}
            2 -> object : RecyclerView.ViewHolder(style.text(getString(R.string.workspace_more_empty), 14f, style.muted).apply {
                setPadding(style.dp(12), style.dp(12), style.dp(12), style.dp(12))
                background = style.rounded(style.surface, 12)
            }) {}
            else -> TabHolder()
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder.itemView as TextView).setText(row.titleRes)
                is Row.TabItem -> (holder as TabHolder).bind(row)
                Row.EmptyMoreHint -> Unit
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private inner class TabHolder : RecyclerView.ViewHolder(style.row().apply {
        layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = style.dp(6) }
        background = style.rounded(style.surface, 14)
        setPadding(style.dp(10), style.dp(6), style.dp(10), style.dp(6))
        minimumHeight = style.dp(56)
    }) {
        private val root = itemView as LinearLayout
        private val handle = ImageView(this@PortTabBarEditorActivity).apply {
            setImageResource(R.drawable.ic_drag_handle)
            imageTintList = ColorStateList.valueOf(style.muted)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = getString(R.string.workspace_tab_editor_hint)
            setPadding(style.dp(6), style.dp(10), style.dp(8), style.dp(10))
        }
        private val icon = ImageView(this@PortTabBarEditorActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            imageTintList = ColorStateList.valueOf(style.accent)
        }
        private val label = style.text("", 16f, style.foreground, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        private val up = compactButton("↑")
        private val down = compactButton("↓")
        private val transfer = style.text("", 12f, style.accent, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = style.dp(34)
            setPadding(style.dp(10), style.dp(4), style.dp(10), style.dp(4))
            background = style.ripple(style.selected, 10)
        }

        init {
            root.addView(handle, LinearLayout.LayoutParams(style.dp(36), style.dp(44)))
            root.addView(icon, LinearLayout.LayoutParams(style.dp(24), style.dp(24)).apply { marginEnd = style.dp(10) })
            root.addView(label, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = style.dp(6) })
            root.addView(up, LinearLayout.LayoutParams(style.dp(34), style.dp(34)).apply { marginEnd = style.dp(4) })
            root.addView(down, LinearLayout.LayoutParams(style.dp(34), style.dp(34)).apply { marginEnd = style.dp(6) })
            root.addView(transfer, LinearLayout.LayoutParams(-2, -2))
            handle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(this)
                false
            }
        }

        private fun compactButton(text: String) = style.text(text, 15f, style.accent, bold = true).apply {
            gravity = Gravity.CENTER
            background = style.ripple(style.selected, 10)
        }

        fun bind(row: Row.TabItem) {
            val tab = row.tab
            icon.setImageResource(tab.icon)
            label.setText(tab.title)

            val group = if (row.inMore) WorkspaceTabConfig.overflowTabs(order, moreEnabled, inMore)
            else WorkspaceTabConfig.visibleBarTabs(order, moreEnabled, inMore)
            val indexInGroup = group.indexOf(tab)

            up.isEnabled = indexInGroup > 0
            up.alpha = if (up.isEnabled) 1f else 0.3f
            up.contentDescription = getString(R.string.workspace_move_up)
            up.setOnClickListener { moveWithinSection(tab, row.inMore, -1) }

            down.isEnabled = indexInGroup in 0 until (group.size - 1)
            down.alpha = if (down.isEnabled) 1f else 0.3f
            down.contentDescription = getString(R.string.workspace_move_down)
            down.setOnClickListener { moveWithinSection(tab, row.inMore, 1) }

            if (moreEnabled) {
                transfer.visibility = View.VISIBLE
                transfer.setText(if (row.inMore) R.string.workspace_move_to_bar else R.string.workspace_move_to_more)
                val canMove = row.inMore || group.size > 1
                transfer.isEnabled = canMove
                transfer.alpha = if (canMove) 1f else 0.35f
                transfer.setOnClickListener { toggleMorePlacement(tab, row.inMore) }
            } else {
                transfer.visibility = View.GONE
            }
        }
    }
}
