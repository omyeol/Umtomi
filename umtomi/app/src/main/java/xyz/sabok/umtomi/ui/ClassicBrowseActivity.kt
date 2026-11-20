/*
 *     Umtomi, Hitomi.la viewer for Android
 *     Copyright (C) 2019  tom5079
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package xyz.sabok.umtomi.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.util.Linkify
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import xyz.sabok.umtomi.util.LocalDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import xyz.quaver.floatingsearchview.FloatingSearchView
import xyz.quaver.floatingsearchview.suggestions.model.SearchSuggestion
import xyz.quaver.floatingsearchview.util.view.MenuView
import xyz.quaver.floatingsearchview.util.view.SearchInputView
import xyz.sabok.umtomi.R
import xyz.sabok.umtomi.adapters.GalleryBlockAdapter
import xyz.sabok.umtomi.databinding.MainActivityBinding
import xyz.sabok.umtomi.favoriteTags
import xyz.sabok.umtomi.favorites
import xyz.sabok.umtomi.histories
import xyz.sabok.umtomi.hitomi.SortMode
import xyz.sabok.umtomi.hitomi.doSearch
import xyz.sabok.umtomi.hitomi.getSuggestionsForQuery
import xyz.sabok.umtomi.searchHistory
import xyz.sabok.umtomi.services.DownloadService
import xyz.sabok.umtomi.types.FavoriteHistorySwitch
import xyz.sabok.umtomi.types.LoadingSuggestion
import xyz.sabok.umtomi.types.NoResultSuggestion
import xyz.sabok.umtomi.types.Suggestion
import xyz.sabok.umtomi.types.Tag
import xyz.sabok.umtomi.types.TagSuggestion
import xyz.sabok.umtomi.ui.dialog.DownloadLocationDialogFragment
import xyz.sabok.umtomi.ui.dialog.GalleryDialog
import xyz.sabok.umtomi.ui.view.MainView
import xyz.sabok.umtomi.ui.view.ProgressCard
import xyz.sabok.umtomi.util.ItemClickSupport
import xyz.sabok.umtomi.util.Preferences
import xyz.sabok.umtomi.util.checkUpdate
import xyz.sabok.umtomi.util.downloader.Cache
import xyz.sabok.umtomi.util.downloader.DownloadManager
import xyz.sabok.umtomi.util.requestNotificationPermission
import xyz.sabok.umtomi.util.restore
import xyz.sabok.umtomi.util.showNotificationPermissionExplanationDialog
import java.util.regex.Pattern
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

val sortModeLookup = mapOf(
    R.id.main_menu_sort_date_added to SortMode.DATE_ADDED,
    R.id.main_menu_sort_date_published to SortMode.DATE_PUBLISHED,
    R.id.main_menu_sort_popular_today to SortMode.POPULAR_TODAY,
    R.id.main_menu_sort_popular_week to SortMode.POPULAR_WEEK,
    R.id.main_menu_sort_popular_month to SortMode.POPULAR_MONTH,
    R.id.main_menu_sort_popular_year to SortMode.POPULAR_YEAR,
    R.id.main_menu_sort_random to SortMode.RANDOM
)

class ClassicBrowseActivity :
    BaseActivity(),
    NavigationView.OnNavigationItemSelectedListener {

    enum class Mode {
        SEARCH,
        HISTORY,
        DOWNLOAD,
        FAVORITE,
        RECOMMENDATIONS
    }


    private val galleries = ArrayList<Int>()

    private var query = ""
        set(value) {
            field = value
            with(findViewById<SearchInputView>(R.id.search_bar_text)) {
                if (text.toString() != value)
                    setText(query, TextView.BufferType.EDITABLE)
            }
        }
    private var queryStack = mutableListOf<String>()

    private var mode = Mode.SEARCH
    private var sortMode = SortMode.DATE_ADDED

    private var galleryIDs: Deferred<List<Int>>? = null
    private var totalItems = 0
    private var loadingJob: Job? = null
    private var currentPage = 0

    private lateinit var binding: MainActivityBinding
    private lateinit var workspace: WorkspaceNavigation

    private val requestNotificationPermssionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) {
                showNotificationPermissionExplanationDialog(this)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        savedInstanceState?.let { state ->
            mode = runCatching { Mode.valueOf(state.getString("mainMode").orEmpty()) }.getOrDefault(Mode.SEARCH)
            sortMode = runCatching { SortMode.valueOf(state.getString("mainSort").orEmpty()) }.getOrDefault(SortMode.DATE_ADDED)
            currentPage = state.getInt("mainPage", 0)
            query = state.getString("mainQuery").orEmpty()
            queryStack = state.getStringArrayList("mainQueryStack")?.toMutableList() ?: mutableListOf()
        }

        if (savedInstanceState == null) {
            intent.getStringExtra("workspace_query")?.take(512)?.let { query = it }
        }

        if (intent.action == Intent.ACTION_VIEW) {
            intent.dataString?.let { url ->
                restore(url,
                    onFailure = {
                        Snackbar.make(
                            binding.contents.recyclerview,
                            R.string.settings_backup_failed,
                            Snackbar.LENGTH_LONG
                        ).show()
                    }, onSuccess = {
                        Snackbar.make(
                            binding.contents.recyclerview,
                            getString(R.string.settings_restore_success, it),
                            Snackbar.LENGTH_LONG
                        ).show()
                    }
                )
            }
        }

        requestNotificationPermission(this, requestNotificationPermssionLauncher, false) {}

        if (Preferences["download_folder", ""].isEmpty())
            DownloadLocationDialogFragment().show(
                supportFragmentManager,
                "Download Location Dialog"
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Preferences["download_folder_ignore_warning", false] &&
            ContextCompat.getExternalFilesDirs(this, null).filterNotNull()
                .map { Uri.fromFile(it).toString() }
                .contains(Preferences["download_folder", ""])
        ) {
            AlertDialog.Builder(this)
                .setTitle(R.string.warning)
                .setMessage(R.string.unaccessible_download_folder)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    DownloadLocationDialogFragment().show(
                        supportFragmentManager,
                        "Download Location Dialog"
                    )
                }.setNegativeButton(R.string.ignore) { _, _ ->
                    Preferences["download_folder_ignore_warning"] = true
                }.show()
        }

        workspace = WorkspaceNavigation(this, binding.workspaceTabs, ::openWorkspaceTab)
        binding.workspaceActions.setOnClickListener { anchor ->
            androidx.appcompat.widget.PopupMenu(this, anchor).apply {
                menu.add(0, 1, 0, R.string.main_settings)
                menu.add(0, 2, 1, R.string.workspace_tab_settings)
                menu.add(0, 3, 2, R.string.workspace_drive_sync)
                menu.add(0, 4, 3, R.string.main_drawer_group_contact_help)
                menu.add(0, 5, 4, R.string.main_drawer_group_contact_github)
                setOnMenuItemClickListener {
                    when (it.itemId) {
                        1 -> startActivity(Intent(this@ClassicBrowseActivity, SettingsActivity::class.java))
                        2 -> workspace.showSettings()
                        3 -> startActivity(Intent(this@ClassicBrowseActivity, DriveSyncActivity::class.java))
                        4 -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.help))))
                        5 -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.github))))
                    }
                    true
                }
            }.show()
        }
        initView()
        binding.contents.resumeReader.setOnClickListener {
            val tabs = xyz.sabok.umtomi.util.ReaderTabStore(this)
            if (tabs.selectedID > 0) {
                startActivity(Intent(this, ReaderActivity::class.java).putExtra("galleryID", tabs.selectedID))
            } else binding.contents.resumeReader.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()

        val tabs = xyz.sabok.umtomi.util.ReaderTabStore(this)
        binding.contents.resumeReader.apply {
            visibility = if (tabs.selectedID > 0) View.VISIBLE else View.GONE
            text = "뷰어로 돌아가기 · ${tabs.all().size}"
            contentDescription = "뷰어로 돌아가기, 열린 작품 ${tabs.all().size}개"
        }

        checkUpdate(this)
    }

    override fun onBackPressed() {
        when {
            binding.drawer.isDrawerOpen(GravityCompat.START) -> binding.drawer.closeDrawer(
                GravityCompat.START
            )

            queryStack.removeLastOrNull() != null && queryStack.isNotEmpty() -> runOnUiThread {
                query = queryStack.last()

                cancelFetch()
                clearGalleries()
                fetchGalleries(query, sortMode)
                loadBlocks()
            }

            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        cancelFetch()
        super.onDestroy()

        (binding.contents.recyclerview.adapter as? GalleryBlockAdapter)?.updateAll = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("mainMode", mode.name)
        outState.putString("mainSort", sortMode.name)
        outState.putInt("mainPage", currentPage)
        outState.putString("mainQuery", query)
        outState.putStringArrayList("mainQueryStack", ArrayList(queryStack))
        super.onSaveInstanceState(outState)
    }

    private fun initView() {
        binding.contents.recyclerview.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                // -height of the search view < translationY < 0
                binding.contents.searchview.translationY =
                    min(
                        max(
                            binding.contents.searchview.translationY - dy,
                            -binding.contents.searchview.binding.querySection.root.height.toFloat()
                        ), 0F
                    )

                if (dy > 0)
                    binding.contents.fab.hideMenuButton(true)
                else if (dy < 0)
                    binding.contents.fab.showMenuButton(true)
            }
        })

        Linkify.addLinks(
            binding.contents.noresult,
            Pattern.compile(getString(R.string.https_text)),
            null,
            null,
            { _, _ -> getString(R.string.https) })

        //NavigationView
        binding.navView.setNavigationItemSelectedListener(this)

        with(binding.contents.cancelFab) {
            setImageResource(R.drawable.cancel)
            setOnClickListener {
                DownloadService.cancel(this@ClassicBrowseActivity)
            }
        }

        with(binding.contents.jumpFab) {
            setImageResource(R.drawable.ic_jump)
            setOnClickListener {
                val perPage = Preferences["per_page", "25"].toInt()
                val editText = EditText(context)

                AlertDialog.Builder(context).apply {
                    setView(editText)
                    setTitle(R.string.main_jump_title)
                    setMessage(
                        getString(
                            R.string.main_jump_message,
                            currentPage + 1,
                            ceil(totalItems / perPage.toDouble()).roundToInt()
                        )
                    )

                    setPositiveButton(android.R.string.ok) { _, _ ->
                        currentPage =
                            (editText.text.toString().toIntOrNull() ?: return@setPositiveButton) - 1

                        runOnUiThread {
                            cancelFetch()
                            clearGalleries()
                            loadBlocks()
                        }
                    }
                }.show()
            }
        }

        with(binding.contents.randomFab) {
            setImageResource(R.drawable.shuffle_variant)
            setOnClickListener {
                lifecycleScope.launch {
                    withTimeoutOrNull(100) {
                        galleryIDs?.await()
                    }
                    .let {
                    if (it?.isEmpty() == false) {
                        val galleryID = it.random()

                        GalleryDialog(this@ClassicBrowseActivity, galleryID).apply {
                            onChipClickedHandler.add {
                                runOnUiThread {
                                    query = it.toQuery()
                                    currentPage = 0

                                    cancelFetch()
                                    clearGalleries()
                                    fetchGalleries(query, sortMode)
                                    loadBlocks()
                                }
                                dismiss()
                            }
                        }.show()
                    }
                    }
                }
            }
        }

        with(binding.contents.idFab) {
            setImageResource(R.drawable.numeric)
            setOnClickListener {
                val editText = EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                }

                AlertDialog.Builder(context).apply {
                    setView(editText)
                    setTitle(R.string.main_open_gallery_by_id)

                    setPositiveButton(android.R.string.ok) { _, _ ->
                        val galleryID =
                            editText.text.toString().toIntOrNull() ?: return@setPositiveButton

                        GalleryDialog(this@ClassicBrowseActivity, galleryID).apply {
                            onChipClickedHandler.add {
                                runOnUiThread {
                                    query = it.toQuery()
                                    currentPage = 0

                                    cancelFetch()
                                    clearGalleries()
                                    fetchGalleries(query, sortMode)
                                    loadBlocks()
                                }
                                dismiss()
                            }
                        }.show()
                    }
                }.show()
            }
        }

        with(binding.contents.view) {
            setOnPageTurnListener(object : MainView.OnPageTurnListener {
                override fun onPrev(page: Int) {
                    currentPage--

                    // disable pageturn until the contents are loaded
                    setCurrentPage(1, false)

                    ViewCompat.animate(binding.contents.searchview)
                        .setDuration(100)
                        .setInterpolator(DecelerateInterpolator())
                        .translationY(0F)

                    cancelFetch()
                    clearGalleries()
                    fetchGalleries(query, sortMode)
                    loadBlocks()
                }

                override fun onNext(page: Int) {
                    currentPage++

                    // disable pageturn until the contents are loaded
                    setCurrentPage(1, false)

                    ViewCompat.animate(binding.contents.searchview)
                        .setDuration(100)
                        .setInterpolator(DecelerateInterpolator())
                        .translationY(0F)

                    cancelFetch()
                    clearGalleries()
                    fetchGalleries(query, sortMode)
                    loadBlocks()
                }
            })
        }

        setupSearchBar()
        setupRecyclerView()
        fetchGalleries(query, sortMode)
        loadBlocks()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupRecyclerView() {
        with(binding.contents.recyclerview) {
            adapter = GalleryBlockAdapter(galleries).apply {
                onChipClickedHandler.add {
                    runOnUiThread {
                        query = it.toQuery()
                        currentPage = 0

                        cancelFetch()
                        clearGalleries()
                        fetchGalleries(query, sortMode)
                        loadBlocks()
                    }
                }
                onDownloadClickedHandler = { position ->
                    val galleryID = galleries[position]

                    requestNotificationPermission(
                        this@ClassicBrowseActivity,
                        requestNotificationPermssionLauncher
                    ) {
                        if (DownloadManager.getInstance(context)
                                .isDownloading(galleryID)
                        ) {     //download in progress
                            DownloadService.cancel(this@ClassicBrowseActivity, galleryID)
                        } else {
                            DownloadManager.getInstance(context).addDownloadFolder(galleryID)
                            DownloadService.download(this@ClassicBrowseActivity, galleryID)
                        }
                    }

                    closeAllItems()
                }

                onDeleteClickedHandler = { position ->
                    val galleryID = galleries[position]
                    DownloadService.delete(this@ClassicBrowseActivity, galleryID)

                    histories.remove(galleryID)

                    if (this@ClassicBrowseActivity.mode != Mode.SEARCH)
                        runOnUiThread {
                            cancelFetch()
                            clearGalleries()
                            fetchGalleries(query, sortMode)
                            loadBlocks()
                        }

                    closeAllItems()
                }
            }
            ItemClickSupport.addTo(this).apply {
                onItemClickListener = listener@{ _, position, v ->
                    if (v !is ProgressCard)
                        return@listener

                    val intent = Intent(this@ClassicBrowseActivity, ReaderActivity::class.java)
                    intent.putExtra("galleryID", galleries[position])

                    //TODO: Maybe sprinkling some transitions will be nice :D
                    startActivity(intent)
                }

                onItemLongClickListener = listener@{ _, position, v ->
                    if (v !is ProgressCard)
                        return@listener false

                    val galleryID = galleries.getOrNull(position) ?: return@listener true

                    GalleryDialog(this@ClassicBrowseActivity, galleryID).apply {
                        onChipClickedHandler.add {
                            runOnUiThread {
                                query = it.toQuery()
                                currentPage = 0

                                cancelFetch()
                                clearGalleries()
                                fetchGalleries(query, sortMode)
                                loadBlocks()
                            }
                            dismiss()
                        }
                    }.show()

                    true
                }
            }
        }
    }

    private var isFavorite = false
    private val defaultSuggestions: List<SearchSuggestion>
        get() = when {
            isFavorite -> {
                favoriteTags.map {
                    TagSuggestion(it.tag, -1, "", it.area ?: "tag")
                } + FavoriteHistorySwitch(getString(R.string.search_show_histories))
            }

            else -> {
                searchHistory.map {
                    Suggestion(it)
                }.takeLast(10) + FavoriteHistorySwitch(getString(R.string.search_show_tags))
            }
        }.reversed()

    private var suggestionJob: Job? = null
    private fun setupSearchBar() {
        with(binding.contents.searchview) {
            val scrollSuggestionToTop = {
                with(binding.suggestionSection.suggestionsList) {
                    lifecycleScope.launch {
                        withTimeout(1000) {
                            val layoutManager = layoutManager as LinearLayoutManager
                            while (layoutManager.findLastVisibleItemPosition() != adapter?.itemCount?.minus(
                                    1
                                )
                            ) {
                                layoutManager.scrollToPosition(adapter?.itemCount?.minus(1) ?: 0)
                                delay(100)
                            }
                        }
                    }
                }
            }

            onMenuStatusChangeListener = object : FloatingSearchView.OnMenuStatusChangeListener {
                override fun onMenuOpened() {
                    (this@ClassicBrowseActivity.binding.contents.recyclerview.adapter as GalleryBlockAdapter).closeAllItems()
                }

                override fun onMenuClosed() {
                    //Do Nothing
                }
            }

            post {
                findViewById<MenuView>(R.id.menu_view).menuItems.firstOrNull {
                    (it as MenuItem).itemId == R.id.main_menu_thin
                }?.let {
                    (it as MenuItem).isChecked = Preferences["thin"]
                }
            }

            onHistoryDeleteClickedListener = {
                searchHistory.remove(it)
                swapSuggestions(defaultSuggestions)
            }
            onFavoriteHistorySwitchClickListener = {
                isFavorite = !isFavorite
                swapSuggestions(defaultSuggestions)
                scrollSuggestionToTop()
            }

            onMenuItemClickListener = {
                onActionMenuItemSelected(it)
            }

            onQueryChangeListener = lambda@{ _, query ->
                this@ClassicBrowseActivity.query = query

                suggestionJob?.cancel()

                if (query.isEmpty() or query.endsWith(' ')) {
                    swapSuggestions(defaultSuggestions)
                    scrollSuggestionToTop()

                    return@lambda
                }

                swapSuggestions(listOf(LoadingSuggestion(getText(R.string.reader_loading).toString())))

                val currentQuery = query.split(" ").last()
                    .replace(Regex("^-"), "")
                    .replace('_', ' ')

                suggestionJob = lifecycleScope.launch(Dispatchers.IO) {
                    val suggestions = kotlin.runCatching {
                        getSuggestionsForQuery(currentQuery).map { TagSuggestion(it) }
                            .toMutableList()
                    }.getOrElse { mutableListOf() }

                    suggestions.filter {
                        val tag = "${it.n}:${it.s.replace(Regex("\\s"), "_")}"
                        favoriteTags.contains(Tag.parse(tag))
                    }.reversed().forEach {
                        suggestions.remove(it)
                        suggestions.add(0, it)
                    }

                    withContext(Dispatchers.Main) {
                        swapSuggestions(
                            if (suggestions.isNotEmpty()) suggestions else listOf(
                                NoResultSuggestion(getText(R.string.main_no_result).toString())
                            )
                        )
                    }
                }
            }

            onFocusChangeListener = object : FloatingSearchView.OnFocusChangeListener {
                override fun onFocus() {
                    if (query.isEmpty() or query.endsWith(' ')) {
                        swapSuggestions(defaultSuggestions)
                        scrollSuggestionToTop()
                    }
                }

                override fun onFocusCleared() {
                    suggestionJob?.cancel()

                    runOnUiThread {
                        cancelFetch()
                        clearGalleries()
                        currentPage = 0
                        fetchGalleries(query, sortMode)
                        loadBlocks()
                    }
                }
            }

            attachNavigationDrawerToMenuButton(this@ClassicBrowseActivity.binding.drawer)
        }
    }

    fun onActionMenuItemSelected(item: MenuItem?) {
        when (item?.itemId) {
            R.id.main_menu_bookshelf -> startActivity(Intent(this, BookshelfActivity::class.java))
            R.id.main_menu_settings -> startActivity(
                Intent(
                    this@ClassicBrowseActivity,
                    SettingsActivity::class.java
                )
            )

            R.id.main_menu_thin -> {
                val thin = !item.isChecked

                item.isChecked = thin
                binding.contents.recyclerview.apply {
                    (adapter as GalleryBlockAdapter).apply {
                        this.thin = thin

                        Preferences["thin"] = thin
                    }

                    adapter = adapter       // Force to redraw
                }
            }

            R.id.main_menu_sort_date_added,
            R.id.main_menu_sort_date_published,
            R.id.main_menu_sort_popular_today,
            R.id.main_menu_sort_popular_week,
            R.id.main_menu_sort_popular_month,
            R.id.main_menu_sort_popular_year,
            R.id.main_menu_sort_random -> {
                sortMode = sortModeLookup[item.itemId]!!
                item.isChecked = true

                runOnUiThread {
                    currentPage = 0

                    cancelFetch()
                    clearGalleries()
                    fetchGalleries(query, sortMode)
                    loadBlocks()
                }
            }
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        binding.drawer.closeDrawers()
        when (item.itemId) {
            R.id.main_drawer_home -> openWorkspaceTab(WorkspaceTab.SEARCH)
            R.id.main_drawer_history -> openWorkspaceTab(WorkspaceTab.HISTORY)
            R.id.main_drawer_downloads -> openWorkspaceTab(WorkspaceTab.DOWNLOADS)
            R.id.main_drawer_favorite -> openWorkspaceTab(WorkspaceTab.FAVORITES)
            R.id.main_drawer_help -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.help))))
            R.id.main_drawer_github -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.github))))
            else -> return false
        }
        return true
    }

    private fun openWorkspaceTab(tab: WorkspaceTab) {
        val next = when (tab) {
            WorkspaceTab.FAVORITES -> {
                startActivity(Intent(this, FavoritesActivity::class.java))
                return
            }
            WorkspaceTab.BOOKSHELF -> {
                startActivity(Intent(this, BookshelfActivity::class.java))
                return
            }
            WorkspaceTab.SEARCH -> Mode.SEARCH
            WorkspaceTab.RECOMMENDATIONS -> Mode.RECOMMENDATIONS
            WorkspaceTab.DOWNLOADS -> Mode.DOWNLOAD
            WorkspaceTab.HISTORY -> Mode.HISTORY
        }
        if (next == mode) {
            binding.contents.recyclerview.scrollToPosition(0)
            binding.contents.searchview.translationY = 0f
            return
        }
        cancelFetch()
        mode = next
        queryStack.clear()
        query = ""
        currentPage = 0
        binding.contents.searchview.translationY = 0f
        binding.contents.recyclerview.scrollToPosition(0)
        clearGalleries()
        fetchGalleries(query, sortMode)
        loadBlocks()
    }

    private fun updateWorkspaceSelection() {
        val selected = when (mode) {
            Mode.SEARCH -> WorkspaceTab.SEARCH
            Mode.HISTORY -> WorkspaceTab.HISTORY
            Mode.DOWNLOAD -> WorkspaceTab.DOWNLOADS
            Mode.FAVORITE -> WorkspaceTab.FAVORITES
            Mode.RECOMMENDATIONS -> WorkspaceTab.RECOMMENDATIONS
        }
        binding.workspaceTitle.setText(selected.title)
        binding.contents.noresult.setText(
            if (mode == Mode.RECOMMENDATIONS) R.string.workspace_no_recommendations else R.string.main_no_result
        )
        workspace.render(selected)
    }

    private fun cancelFetch() {
        galleryIDs?.cancel()
        loadingJob?.cancel()
    }

    private fun clearGalleries() = lifecycleScope.launch {
        galleries.clear()

        with(binding.contents.recyclerview.adapter as GalleryBlockAdapter?) {
            this ?: return@with

            this.notifyDataSetChanged()
        }

        binding.contents.noresult.visibility = View.INVISIBLE
        binding.contents.progressbar.show()
    }

    private fun fetchGalleries(query: String, sortMode: SortMode) {
        updateWorkspaceSelection()
        val defaultQuery: String = Preferences["default_query"]

        if (query.isNotBlank())
            searchHistory.add(query)

        if (query != queryStack.lastOrNull()) {
            queryStack.remove(query)
            queryStack.add(query)
        }

        if (query.isNotEmpty() && mode != Mode.SEARCH) {
            Snackbar.make(binding.contents.recyclerview, R.string.search_all, Snackbar.LENGTH_SHORT)
                .apply {
                    setAction(android.R.string.ok) {
                        cancelFetch()
                        clearGalleries()
                        currentPage = 0
                        mode = Mode.SEARCH
                        queryStack.clear()
                        fetchGalleries(query, sortMode)
                        loadBlocks()
                    }
                }.show()
        }

        galleryIDs = null

        if (galleryIDs?.isActive == true)
            return

        galleryIDs = lifecycleScope.async(Dispatchers.IO) {
            when (mode) {
                Mode.RECOMMENDATIONS -> {
                    val recent = synchronized(histories) { histories.reversed() }
                    val saved = synchronized(favorites) { favorites.reversed() }
                    val suggestions = workspaceRecommendations(recent, saved)
                    val results = if (query.isBlank()) suggestions else {
                        val matching = doSearch(query, sortMode).toSet()
                        suggestions.filter(matching::contains)
                    }
                    results.also { totalItems = it.size }
                }

                Mode.SEARCH -> {
                    doSearch(
                        "$defaultQuery $query",
                        sortMode
                    ).also {
                        totalItems = it.size
                    }
                }

                Mode.HISTORY -> {
                    when {
                        query.isEmpty() -> {
                            histories.reversed().also {
                                totalItems = it.size
                            }
                        }

                        else -> {
                            val result = doSearch(query, SortMode.DATE_ADDED).sorted()
                            histories.reversed().filter { result.binarySearch(it) >= 0 }.also {
                                totalItems = it.size
                            }
                        }
                    }
                }

                Mode.DOWNLOAD -> {
                    val downloads =
                        DownloadManager.getInstance(this@ClassicBrowseActivity).downloadFolderMap.keys.toList()

                    when {
                        query.isEmpty() -> downloads.reversed().also {
                            totalItems = it.size
                        }

                        else -> {
                            val result = doSearch(query, SortMode.DATE_ADDED).sorted()
                            downloads.reversed().filter { result.binarySearch(it) >= 0 }.also {
                                totalItems = it.size
                            }
                        }
                    }
                }

                Mode.FAVORITE -> {
                    when {
                        query.isEmpty() -> favorites.reversed().also {
                            totalItems = it.size
                        }

                        else -> {
                            val result = doSearch(query, SortMode.DATE_ADDED).sorted()
                            favorites.reversed().filter { result.binarySearch(it) >= 0 }.also {
                                totalItems = it.size
                            }
                        }
                    }
                }
            }.toList()
        }
    }

    private fun loadBlocks() {
        val perPage = Preferences["per_page", "25"].toInt()

        loadingJob = lifecycleScope.launch(Dispatchers.IO) {
            val galleryIDs = try {
                galleryIDs!!.await().also {
                    if (it.isEmpty())
                        throw Exception("No result")
                }
            } catch (e: Exception) {
                if (e !is CancellationException)
                    LocalDiagnostics.recordException(e)

                withContext(Dispatchers.Main) {
                    binding.contents.noresult.visibility = View.VISIBLE
                    binding.contents.progressbar.hide()
                }

                return@launch
            }

            withContext(Dispatchers.Main) {
                currentPage = currentPage.coerceIn(0, (galleryIDs.size - 1).coerceAtLeast(0) / perPage)
                binding.contents.view.setCurrentPage(
                    currentPage + 1,
                    galleryIDs.size > (currentPage + 1) * perPage
                )
            }

            galleryIDs.slice(
                currentPage * perPage until min(
                    currentPage * perPage + perPage,
                    galleryIDs.size
                )
            ).chunked(5).let { chunks ->
                for (chunk in chunks)
                    chunk.map { galleryID ->
                        async {
                            Cache.getInstance(this@ClassicBrowseActivity, galleryID).getGalleryBlock()?.let {
                                galleryID
                            }
                        }
                    }.forEach {
                        it.await()?.also {
                            withContext(Dispatchers.Main) {
                                binding.contents.progressbar.hide()

                                galleries.add(it)
                                binding.contents.recyclerview.adapter!!.notifyItemInserted(galleries.size - 1)
                            }
                        }
                    }
            }
        }
    }
}

