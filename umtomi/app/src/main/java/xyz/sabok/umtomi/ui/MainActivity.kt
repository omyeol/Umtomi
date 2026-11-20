package xyz.sabok.umtomi.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.sabok.umtomi.R
import xyz.sabok.umtomi.favorites
import xyz.sabok.umtomi.histories
import xyz.sabok.umtomi.hitomi.SortMode
import xyz.sabok.umtomi.hitomi.doSearch
import xyz.sabok.umtomi.hitomi.getSuggestionsForQuery
import xyz.sabok.umtomi.searchHistory
import xyz.sabok.umtomi.services.DownloadService
import xyz.sabok.umtomi.util.Preferences
import xyz.sabok.umtomi.util.ReaderTabStore
import xyz.sabok.umtomi.util.downloader.DownloadManager
import xyz.sabok.umtomi.util.requestNotificationPermission
import xyz.sabok.umtomi.util.restore

/** Browse/navigation state and entry points matching the iOS RootView and BrowseView. */
class MainActivity : BaseActivity() {
    private val style by lazy { PortStyle(this) }
    private lateinit var root: FrameLayout
    private lateinit var workspace: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var readerButton: View
    private lateinit var navigation: WorkspaceNavigation
    private lateinit var favoritesView: PortFavoritesView
    private var headingLabel: TextView? = null

    private data class ScreenEntry(val view: View, val galleryID: Int? = null, val detail: PortGalleryDetail? = null)
    private val screenStack = mutableListOf<ScreenEntry>()

    private var activeTab = WorkspaceTab.SEARCH
    private var sort = SortMode.DATE_ADDED
    private var query = ""
    private var page = 0
    private var entries: List<Int> = emptyList()
    private var entryKey: Pair<String, SortMode>? = null
    private var renderedPageSize = 0
    private var loadJob: Job? = null
    private var suggestionsJob: Job? = null
    private val notify = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeTab = WorkspaceTab.entries.firstOrNull { it.name == savedInstanceState?.getString("tab") } ?: WorkspaceTab.SEARCH
        sort = SortMode.entries.firstOrNull { it.name == savedInstanceState?.getString("sort") } ?: SortMode.DATE_ADDED
        query = savedInstanceState?.getString("query") ?: intent.getStringExtra("workspace_query").orEmpty()
        page = savedInstanceState?.getInt("page") ?: 0

        favoritesView = PortFavoritesView(
            activity = this,
            style = style,
            openGallery = ::showGallery,
            galleryActions = ::galleryActions,
            search = ::searchFor,
            onTitleChanged = { headingLabel?.text = favoritesView.currentTitle() },
        ).apply {
            currentFolderID = savedInstanceState?.getString("fav_folder")
            showingArtists = savedInstanceState?.getBoolean("fav_artists") ?: false
        }

        root = FrameLayout(this).apply { setBackgroundColor(style.background) }
        workspace = style.stack()
        content = FrameLayout(this)
        workspace.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val tabs = style.row().apply { setBackgroundColor(style.barBackground) }
        workspace.addView(View(this).apply { setBackgroundColor(style.divider) }, LinearLayout.LayoutParams(-1, style.dp(1)))
        workspace.addView(tabs, style.matchWrap())
        root.addView(workspace, FrameLayout.LayoutParams(-1, -1))
        readerButton = style.action(getString(R.string.port_reader), prominent = true, click = ::resumeReader)
        content.addView(readerButton, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(style.dp(16), 0, style.dp(16), style.dp(14))
        })
        navigation = WorkspaceNavigation(this, tabs, ::openTab)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bounds = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bounds.left, bounds.top, bounds.right, maxOf(bounds.bottom, keyboard.bottom))
            insets
        }
        style.applySystemBars(this, root, style.barBackground)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    screenStack.isNotEmpty() -> popScreen()
                    activeTab == WorkspaceTab.FAVORITES && favoritesView.handleBack() -> Unit
                    activeTab != WorkspaceTab.SEARCH -> openTab(WorkspaceTab.SEARCH)
                    query.isNotBlank() -> searchFor("")
                    else -> finish()
                }
            }
        })
        renderTab()
        savedInstanceState?.getInt("gallery")?.takeIf { it > 0 }?.let(::showGallery)

        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("workspace_query")?.let(::searchFor)
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) intent.dataString?.let { uri ->
            restore(uri, { showMessage(getString(R.string.settings_backup_failed)) },
                { showMessage(getString(R.string.settings_restore_success, it)); renderTab() })
        }
    }

    private fun showMessage(message: String) = runOnUiThread {
        if (!isFinishing) android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("tab", activeTab.name)
        outState.putString("sort", sort.name)
        outState.putString("query", query)
        outState.putInt("page", page)
        outState.putInt("gallery", screenStack.lastOrNull { it.galleryID != null }?.galleryID ?: 0)
        outState.putString("fav_folder", favoritesView.currentFolderID)
        outState.putBoolean("fav_artists", favoritesView.showingArtists)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        navigation.refresh()
        showReaderButton()
        screenStack.lastOrNull()?.let { entry ->
            if (entry.galleryID != null) entry.detail?.refreshActions(entry.galleryID)
        }
        if (screenStack.isEmpty() && (activeTab != WorkspaceTab.SEARCH ||
                renderedPageSize != pageSize() ||
                (entryKey != null && entryKey != (searchQuery(query) to sort)))) {
            if (activeTab == WorkspaceTab.FAVORITES) favoritesView.refresh()
            else renderTab()
        }
    }

    private fun showReaderButton() {
        if (!::readerButton.isInitialized) return
        val tabs = ReaderTabStore(this)
        (readerButton as TextView).text = getString(R.string.port_resume, tabs.all().size)
        readerButton.visibility = if (tabs.selectedID > 0) View.VISIBLE else View.GONE
    }

    private fun resumeReader() {
        ReaderTabStore(this).selectedID.takeIf { it > 0 }?.let(::openReader)
    }

    private fun openReader(id: Int) = launch(ReaderActivity::class.java) { putExtra("galleryID", id) }

    private fun openTab(tab: WorkspaceTab) {
        if (activeTab != tab) {
            activeTab = tab
            renderTab()
        } else if (tab == WorkspaceTab.BOOKSHELF) launch(BookshelfActivity::class.java)
    }

    private fun renderTab() {
        loadJob?.cancel()
        suggestionsJob?.cancel()
        favoritesView.cancel()
        if (content.childCount > 1) content.removeViewAt(0)
        navigation.render(activeTab)
        val panel = style.stack().apply {
            setPadding(style.dp(15), style.dp(6), style.dp(15), 0)
        }
        content.addView(panel, 0, FrameLayout.LayoutParams(-1, -1))
        val headingText = when (activeTab) {
            WorkspaceTab.SEARCH -> getString(R.string.app_name)
            WorkspaceTab.FAVORITES -> favoritesView.currentTitle()
            else -> getString(activeTab.title)
        }
        val titleView = style.text(headingText, 32f, bold = true).apply {
            ViewCompat.setAccessibilityHeading(this, true)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        headingLabel = titleView
        val heading = style.row().apply {
            addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
            if (activeTab == WorkspaceTab.RECOMMENDATIONS) {
                addView(style.action("↻") { renderTab() }.apply {
                    contentDescription = getString(R.string.port_refresh)
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = style.dp(8) })
            }
            val actionsBtn = style.action("•••") {}
            actionsBtn.setOnClickListener { showActions(actionsBtn) }
            actionsBtn.contentDescription = getString(R.string.workspace_actions)
            addView(actionsBtn)
        }
        panel.addView(heading, style.matchWrap().apply { bottomMargin = style.dp(12) })

        when (activeTab) {
            WorkspaceTab.SEARCH -> buildSearch(panel)
            WorkspaceTab.RECOMMENDATIONS -> buildRecommendations(panel)
            WorkspaceTab.HISTORY -> showLocal(panel, snapshot(histories))
            WorkspaceTab.DOWNLOADS -> buildDownloads(panel)
            WorkspaceTab.FAVORITES -> {
                val favContainer = style.stack()
                panel.addView(favContainer, LinearLayout.LayoutParams(-1, 0, 1f))
                favoritesView.mount(favContainer)
            }
            WorkspaceTab.BOOKSHELF -> buildShelfIndex(panel)
        }
    }

    private fun buildRecommendations(panel: LinearLayout) {
        val hist = snapshot(histories)
        val favs = snapshot(favorites)
        val list = addList(panel, cards = false)
        if (hist.isEmpty() && favs.isEmpty()) {
            feedback(list, R.string.workspace_no_recommendations)
            return
        }
        load(list, emptyLabel = R.string.workspace_no_recommendations) {
            val count = Preferences["port_recommendation_count", 30].coerceIn(5, 100)
            workspaceRecommendations(
                context = this@MainActivity,
                history = hist,
                favorites = favs,
                maximum = count,
                includedTags = Preferences["port_included_tags", ""],
                excludedTags = Preferences["port_excluded_tags", ""],
            )
        }
    }

    private fun buildDownloads(panel: LinearLayout) {
        val manager = DownloadManager.getInstance(this)
        val allIDs = manager.downloadFolderMap.keys.toList().asReversed()
        val activeIDs = allIDs.filter { manager.isDownloading(it) }
        val savedIDs = allIDs.filterNot { manager.isDownloading(it) }

        if (activeIDs.isEmpty() && savedIDs.isEmpty()) {
            panel.addView(style.text(getString(R.string.port_downloads_empty), 15f, style.muted).apply {
                gravity = Gravity.CENTER
                setPadding(style.dp(16), style.dp(40), style.dp(16), style.dp(40))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            return
        }

        if (activeIDs.isNotEmpty()) {
            panel.addView(style.text(getString(R.string.port_downloads_active), 14f, style.muted, bold = true).apply {
                setPadding(style.dp(4), 0, 0, style.dp(6))
            })
            activeIDs.forEach { gid ->
                val row = style.row().apply {
                    background = style.rounded(style.surface, 12)
                    setPadding(style.dp(12), style.dp(8), style.dp(8), style.dp(8))
                }
                row.addView(style.text(getString(R.string.port_gallery_id, gid), 15f, style.foreground, bold = true),
                    LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(style.action(getString(R.string.port_cancel_download)) {
                    DownloadService.cancel(this, gid)
                    renderTab()
                })
                panel.addView(row, style.matchWrap().apply { bottomMargin = style.dp(6) })
            }
        }

        if (savedIDs.isNotEmpty()) {
            panel.addView(style.text(getString(R.string.port_downloads_saved), 14f, style.muted, bold = true).apply {
                setPadding(style.dp(4), style.dp(8), 0, style.dp(6))
            })
            showLocal(panel, savedIDs)
        }
    }

    private fun buildShelfIndex(panel: LinearLayout) {
        val store = BookshelfStore(this)
        panel.addView(style.action(getString(R.string.port_open_shelves), true) {
            launch(BookshelfActivity::class.java)
        }, style.matchWrap().apply { bottomMargin = style.dp(8) })
        if (store.loadError != null) {
            panel.addView(style.text(store.loadError.orEmpty(), 15f, style.muted))
            return
        }
        val folders = style.stack()
        store.folders.forEach { path ->
            folders.addView(style.action(path.replace("/", "  ›  ")) {
                launch(BookshelfActivity::class.java) { putExtra("port_shelf_folder", path) }
            }.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                background = style.ripple(style.surface, 12)
            }, style.matchWrap().apply { bottomMargin = style.dp(6) })
        }
        panel.addView(ScrollView(this).apply { addView(folders) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun snapshot(set: Set<Int>) = synchronized(set) { set.toList().asReversed() }

    private data class ResultList(val adapter: PortGalleryAdapter, val widget: RecyclerView, val message: LinearLayout)
    private fun addList(panel: LinearLayout, cards: Boolean): ResultList {
        val access = PortGalleryAdapter(this, style, lifecycleScope, cards, ::showGallery, ::galleryActions)
        val list = RecyclerView(this).apply {
            layoutManager = if (cards) {
                GridLayoutManager(this@MainActivity, maxOf(2, resources.configuration.screenWidthDp / 180))
            } else {
                LinearLayoutManager(this@MainActivity)
            }
            adapter = access
            itemAnimator = null
            setPadding(0, 0, 0, style.dp(75))
            clipToPadding = false
        }
        val message = style.stack().apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        panel.addView(FrameLayout(this).apply {
            addView(list, FrameLayout.LayoutParams(-1, -1))
            addView(message, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        return ResultList(access, list, message)
    }

    private fun feedback(list: ResultList, label: Int, pending: Boolean = false, retry: (() -> Unit)? = null) {
        list.message.removeAllViews()
        list.message.visibility = View.VISIBLE
        if (pending) list.message.addView(ProgressBar(this), LinearLayout.LayoutParams(style.dp(28), style.dp(28)))
        list.message.addView(style.text(getString(label), 16f, style.muted).apply { gravity = Gravity.CENTER },
            style.matchWrap().apply { topMargin = style.dp(8) })
        if (retry != null) list.message.addView(style.action(getString(R.string.port_retry), click = retry),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = style.dp(8) })
    }

    private fun showResults(list: ResultList, ids: List<Int>, emptyLabel: Int = R.string.port_empty) {
        list.adapter.submitList(ids)
        if (ids.isEmpty()) feedback(list, emptyLabel)
        else list.message.visibility = View.GONE
    }

    private fun showLocal(panel: LinearLayout, ids: List<Int>) = showResults(addList(panel, cards = false), ids)

    private fun load(list: ResultList, emptyLabel: Int = R.string.port_empty, getIDs: suspend () -> List<Int>) {
        feedback(list, R.string.port_loading, pending = true)
        loadJob = lifecycleScope.launch {
            try {
                showResults(list, getIDs(), emptyLabel)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                feedback(list, R.string.port_load_failed, retry = { load(list, emptyLabel, getIDs) })
            }
        }
    }

    private fun buildSearch(panel: LinearLayout) {
        renderedPageSize = pageSize()
        val row = style.row().apply {
            background = style.rounded(style.surface, 13)
            setPadding(style.dp(10), 0, style.dp(4), 0)
        }
        val field = EditText(this).apply {
            hint = getString(R.string.port_search_hint)
            setText(query)
            setTextColor(style.foreground)
            setHintTextColor(style.muted)
            textSize = 16f
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = null
            minHeight = style.dp(48)
            setPadding(style.dp(6), 0, style.dp(6), 0)
        }
        row.addView(field, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(style.action(getString(R.string.workspace_search)) { searchFor(field.text.toString()) })
        panel.addView(row, style.matchWrap())
        val suggestions = style.stack().apply { visibility = View.GONE }
        panel.addView(suggestions, style.matchWrap())
        fun suggest() {
            suggestionsJob?.cancel()
            suggestions.removeAllViews()
            suggestions.visibility = View.GONE
            if (!field.hasFocus()) return
            val text = field.text.toString()
            suggestionsJob = lifecycleScope.launch {
                val options: List<Pair<String, String>> = if (text.isBlank()) {
                    synchronized(searchHistory) { searchHistory.toList().asReversed().take(4) }.map { it to it }
                } else {
                    delay(250)
                    withContext(Dispatchers.IO) {
                        runCatching {
                            getSuggestionsForQuery(text).take(5).map { suggestion ->
                                val normalized = suggestion.s.replace(' ', '_')
                                val term = if (suggestion.n.isBlank() || suggestion.n == "global") normalized else "${suggestion.n}:$normalized"
                                val last = text.substringAfterLast(' ')
                                val prefix = text.dropLast(last.length)
                                val completed = prefix + if (last.startsWith('-')) "-$term" else term
                                val display = if (suggestion.n.isBlank() || suggestion.n == "global") {
                                    "${suggestion.s} (${suggestion.t})"
                                } else {
                                    "${suggestion.n}:${suggestion.s} (${suggestion.t})"
                                }
                                display to completed
                            }
                        }.getOrDefault(emptyList())
                    }
                }
                if (!field.hasFocus() || field.text.toString() != text) return@launch
                options.forEach { (display, completed) ->
                    suggestions.addView(style.action(display) { searchFor(completed) }.apply {
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    }, style.matchWrap().apply { topMargin = style.dp(3) })
                }
                suggestions.visibility = if (options.isEmpty()) View.GONE else View.VISIBLE
            }
        }
        field.setOnFocusChangeListener { _, _ -> suggest() }
        field.doAfterTextChanged { suggest() }
        field.setOnEditorActionListener { _, action, _ ->
            if (action != EditorInfo.IME_ACTION_SEARCH) false
            else { searchFor(field.text.toString()); true }
        }
        panel.isFocusableInTouchMode = true
        panel.requestFocus()

        val pages = style.row()
        val sortButton = style.action(getString(sortLabel(sort))) {
            val modes = SortMode.entries
            AlertDialog.Builder(this).setTitle(R.string.port_sort)
                .setSingleChoiceItems(modes.map { getString(sortLabel(it)) }.toTypedArray(), modes.indexOf(sort)) { dialog, position ->
                    dialog.dismiss()
                    sort = modes[position]
                    page = 0
                    renderTab()
                }.show()
        }
        val prev = style.action("‹") { if (page > 0) { page--; renderTab() } }
        val next = style.action("›") { page++; renderTab() }
        val position = style.action("—") {
            if (entries.isNotEmpty()) numberInput(R.string.main_jump_title, page + 1) { value ->
                page = (value - 1).coerceIn(0, lastPage())
                renderTab()
            }
        }
        prev.contentDescription = getString(R.string.port_previous)
        next.contentDescription = getString(R.string.port_next)
        pages.addView(sortButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = style.dp(8) })
        pages.addView(prev)
        pages.addView(position, LinearLayout.LayoutParams(-2, -2).apply { marginStart = style.dp(4) })
        pages.addView(next, LinearLayout.LayoutParams(-2, -2).apply { marginStart = style.dp(4) })
        panel.addView(pages, style.matchWrap().apply { topMargin = style.dp(10); bottomMargin = style.dp(4) })
        val resultSummary = style.text("", 12f, style.muted).apply {
            setPadding(style.dp(4), 0, style.dp(4), style.dp(6))
            visibility = View.GONE
        }
        panel.addView(resultSummary, style.matchWrap())
        val list = addList(panel, cards = true)
        prev.isEnabled = false
        next.isEnabled = false
        fun showPage(): List<Int> {
            val last = lastPage()
            page = page.coerceIn(0, last)
            position.text = getString(R.string.port_page, page + 1, last + 1)
            resultSummary.text = getString(R.string.port_results_count, entries.size)
            resultSummary.visibility = View.VISIBLE
            prev.isEnabled = page > 0
            next.isEnabled = page < last
            prev.alpha = if (prev.isEnabled) 1f else 0.42f
            next.alpha = if (next.isEnabled) 1f else 0.42f
            return entries.drop(page * pageSize()).take(pageSize())
        }
        val key = searchQuery(query) to sort
        if (entryKey == key) showResults(list, showPage())
        else load(list) {
            entries = withContext(Dispatchers.IO) { doSearch(key.first, key.second).distinct() }
            entryKey = key
            showPage()
        }
    }

    private fun pageSize(): Int = (Preferences["per_page", "25"].toIntOrNull() ?: 25).coerceAtLeast(1)
    private fun searchQuery(value: String) = listOf(Preferences["default_query", ""], value, persistentFilters())
        .filter(String::isNotBlank).joinToString(" ")

    private fun persistentFilters(): String {
        val included = RecommendationEngine.tagTerms(Preferences["port_included_tags", ""])
        val excluded = RecommendationEngine.tagTerms(Preferences["port_excluded_tags", ""]).map { "-$it" }
        return (included + excluded).joinToString(" ")
    }
    private fun lastPage() = if (entries.isEmpty()) 0 else (entries.size - 1) / pageSize()

    private fun searchFor(text: String) {
        clearScreens()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0)
        query = text.trim().take(512)
        if (query.isNotBlank()) searchHistory.add(query)
        page = 0
        activeTab = WorkspaceTab.SEARCH
        renderTab()
    }

    private fun sortLabel(mode: SortMode) = when (mode) {
        SortMode.DATE_ADDED -> R.string.main_menu_sort_date_added
        SortMode.DATE_PUBLISHED -> R.string.main_menu_sort_date_published
        SortMode.POPULAR_TODAY -> R.string.main_menu_sort_popular_today
        SortMode.POPULAR_WEEK -> R.string.main_menu_sort_popular_week
        SortMode.POPULAR_MONTH -> R.string.main_menu_sort_popular_month
        SortMode.POPULAR_YEAR -> R.string.main_menu_sort_popular_year
        SortMode.RANDOM -> R.string.main_menu_sort_random
    }

    private fun numberInput(title: Int, initial: Int? = null, action: (Int) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            initial?.let { setText(it.toString()); selectAll() }
        }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(input)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val number = input.text.toString().toIntOrNull()
                if (number != null && number > 0) { dialog.dismiss(); action(number) }
                else input.error = getString(R.string.port_positive_number)
            }
        }
        dialog.show()
    }

    private fun showActions(anchor: View) = PopupMenu(this, anchor).apply {
        menu.add(0, 1, 0, R.string.main_settings)
        menu.add(0, 2, 1, R.string.main_open_gallery_by_id)
        menu.add(0, 3, 2, R.string.main_fab_random)
        setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> showSettings()
                2 -> numberInput(R.string.main_open_gallery_by_id, action = ::showGallery)
                3 -> entries.randomOrNull()?.let(::showGallery) ?: showMessage(getString(R.string.port_empty))
            }
            true
        }
    }.show()

    private fun showGallery(id: Int) {
        lateinit var detail: PortGalleryDetail
        detail = PortGalleryDetail(this, style, ::popScreen, ::showGallery, ::pushOverlay, ::openReader, ::showReaderButton)
        pushScreen(ScreenEntry(detail.create(id), galleryID = id, detail = detail))
    }

    private fun pushOverlay(view: View) {
        pushScreen(ScreenEntry(view))
    }

    private fun galleryActions(id: Int) {
        val detail = PortGalleryDetail(this, style, {}, {}, {}, ::openReader, ::showReaderButton)
        AlertDialog.Builder(this).setTitle(getString(R.string.port_gallery_id, id))
            .setItems(intArrayOf(R.string.port_details, R.string.port_read, R.string.port_background_tab, R.string.port_offline_actions)
                .map { getString(it) }.toTypedArray()) { _, item ->
                when (item) {
                    0 -> showGallery(id)
                    1 -> openReader(id)
                    2 -> detail.backgroundTab(id)
                    3 -> detail.downloadActions(id)
                }
            }.show()
    }

    internal fun allowDownloadNotifications() = requestNotificationPermission(this, notify, false) {}

    private fun pushScreen(entry: ScreenEntry) {
        screenStack.lastOrNull()?.view?.visibility = View.GONE
        workspace.visibility = View.GONE
        root.addView(entry.view, FrameLayout.LayoutParams(-1, -1))
        screenStack += entry
    }

    private fun popScreen() {
        if (screenStack.isEmpty()) return
        val current = screenStack.removeAt(screenStack.lastIndex)
        current.detail?.unbind()
        root.removeView(current.view)
        val previous = screenStack.lastOrNull()
        if (previous != null) {
            previous.view.visibility = View.VISIBLE
            previous.galleryID?.let { previous.detail?.refreshActions(it) }
        } else {
            workspace.visibility = View.VISIBLE
            showReaderButton()
            if (activeTab == WorkspaceTab.FAVORITES) favoritesView.refresh()
        }
    }

    private fun clearScreens() {
        while (screenStack.isNotEmpty()) {
            val current = screenStack.removeAt(screenStack.lastIndex)
            current.detail?.unbind()
            root.removeView(current.view)
        }
        workspace.visibility = View.VISIBLE
        showReaderButton()
    }

    private fun showSettings() = launch(SettingsActivity::class.java)

    private fun launch(activity: Class<*>, extras: Intent.() -> Unit = {}) =
        startActivity(Intent(this, activity).apply(extras))

    private fun launchExternal(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
