package xyz.sabok.umtomi.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import xyz.sabok.umtomi.R
import xyz.sabok.umtomi.favorites
import xyz.sabok.umtomi.hitomi.SortMode
import xyz.sabok.umtomi.hitomi.doSearch
import xyz.sabok.umtomi.util.FavoriteFolder
import xyz.sabok.umtomi.util.FavoriteFolders
import xyz.sabok.umtomi.util.Preferences
import xyz.sabok.umtomi.util.downloader.Cache

internal enum class FavoriteSortOrder { ADDED, UPLOAD, NAME }

internal fun sortFavoriteGalleryIDs(
    ids: List<Int>,
    addedNewestFirst: List<Int>,
    order: FavoriteSortOrder,
    ascending: Boolean,
    titlesByID: Map<Int, String> = emptyMap(),
): List<Int> {
    val addedIndex = HashMap<Int, Int>(addedNewestFirst.size)
    addedNewestFirst.forEachIndexed { index, id -> addedIndex[id] = index }
    return when (order) {
        FavoriteSortOrder.ADDED -> ids.sortedWith { a, b ->
            val ia = addedIndex[a] ?: Int.MAX_VALUE
            val ib = addedIndex[b] ?: Int.MAX_VALUE
            val cmp = ia.compareTo(ib)
            val ordered = if (ascending) -cmp else cmp
            if (ordered != 0) ordered else b.compareTo(a)
        }
        FavoriteSortOrder.UPLOAD -> ids.sortedWith { a, b ->
            if (ascending) a.compareTo(b) else b.compareTo(a)
        }
        FavoriteSortOrder.NAME -> ids.sortedWith { a, b ->
            val ta = titlesByID[a]
            val tb = titlesByID[b]
            val cmp = when {
                ta != null && tb != null -> String.CASE_INSENSITIVE_ORDER.compare(ta, tb)
                ta != null -> -1
                tb != null -> 1
                else -> 0
            }
            val ordered = if (cmp != 0) (if (ascending) cmp else -cmp) else (if (ascending) a.compareTo(b) else b.compareTo(a))
            ordered
        }
    }
}

/** Android port of the iOS FavoritesView (Works/Artists switcher, nested folders, auto-rules). */
internal class PortFavoritesView(
    private val activity: MainActivity,
    private val style: PortStyle,
    private val openGallery: (Int) -> Unit,
    private val galleryActions: (Int) -> Unit,
    private val openArtistOverlay: (String, String) -> Unit,
    private val onTitleChanged: () -> Unit,
) {
    var currentFolderID: String? = null
    var showingArtists = false
    private var ruleJob: Job? = null
    private var childRuleJob: Job? = null
    private var nameSortJob: Job? = null
    private var container: LinearLayout? = null
    private val autoCachePrefs = activity.getSharedPreferences("port_favorite_auto_cache", Context.MODE_PRIVATE)
    private data class AutoCacheEntry(val rule: String, val favoritesHash: String, val matched: List<Int>)
    private val autoMatchCache = mutableMapOf<String, AutoCacheEntry>()

    init {
        loadPersistedAutoCache()
    }

    private fun favoritesSignature(allFavs: List<Int>): String =
        if (allFavs.isEmpty()) "" else "${allFavs.size}:${allFavs.first()}:${allFavs.last()}:${allFavs.hashCode()}"

    private fun loadPersistedAutoCache() {
        for ((folderID, raw) in autoCachePrefs.all) {
            val text = raw as? String ?: continue
            runCatching {
                val obj = JSONObject(text)
                val rule = obj.optString("rule")
                val hash = obj.optString("hash")
                val arr = obj.optJSONArray("matched") ?: JSONArray()
                val ids = (0 until arr.length()).mapNotNull { arr.optInt(it).takeIf { id -> id > 0 } }
                autoMatchCache[folderID] = AutoCacheEntry(rule, hash, ids)
            }
        }
    }

    private fun saveAutoCacheEntry(folderID: String, entry: AutoCacheEntry) {
        autoMatchCache[folderID] = entry
        val json = JSONObject().put("rule", entry.rule).put("hash", entry.favoritesHash).put("matched", JSONArray(entry.matched)).toString()
        autoCachePrefs.edit().putString(folderID, json).apply()
    }

    private fun removeAutoCacheEntry(folderID: String) {
        autoMatchCache.remove(folderID)
        autoCachePrefs.edit().remove(folderID).apply()
    }

    private fun clearAutoCache() {
        autoMatchCache.clear()
        autoCachePrefs.edit().clear().apply()
    }

    private fun sortOrder(): FavoriteSortOrder =
        runCatching { FavoriteSortOrder.valueOf(Preferences["port_fav_sort", "ADDED"]) }.getOrDefault(FavoriteSortOrder.ADDED)

    private fun sortAscending(): Boolean = Preferences["port_fav_sort_asc", false]

    fun currentTitle(): String {
        val id = currentFolderID ?: return activity.getString(R.string.main_drawer_favorite)
        val library = runCatching { FavoriteFolders(activity) }.getOrNull()
        return library?.folders?.firstOrNull { it.id == id }?.name
            ?: activity.getString(R.string.main_drawer_favorite)
    }

    fun handleBack(): Boolean {
        val id = currentFolderID ?: return false
        val library = runCatching { FavoriteFolders(activity) }.getOrNull()
        currentFolderID = library?.folders?.firstOrNull { it.id == id }?.parentID
        onTitleChanged()
        render()
        return true
    }

    fun mount(target: LinearLayout) {
        container = target
        render()
    }

    fun refresh() {
        if (container != null) render()
    }

    fun cancel() {
        ruleJob?.cancel()
        childRuleJob?.cancel()
        nameSortJob?.cancel()
        container = null
    }

    fun render() {
        val host = container ?: return
        ruleJob?.cancel()
        childRuleJob?.cancel()
        nameSortJob?.cancel()
        host.removeAllViews()

        val library = runCatching { FavoriteFolders(activity) }.getOrElse {
            host.addView(style.text(it.localizedMessage ?: activity.getString(R.string.error), 15f, style.muted))
            return
        }
        if (currentFolderID != null && library.folders.none { it.id == currentFolderID }) {
            currentFolderID = null
            onTitleChanged()
        }

        val currentFolder = currentFolderID?.let { id -> library.folders.firstOrNull { it.id == id } }
        val isWorks = currentFolder != null || !showingArtists

        if (currentFolder == null) {
            host.addView(buildSegmentedRow(library), style.matchWrap().apply { bottomMargin = style.dp(10) })
        } else {
            host.addView(buildFolderTopRow(library, currentFolder), style.matchWrap().apply { bottomMargin = style.dp(10) })
        }

        if (isWorks) {
            renderWorks(host, library, currentFolder)
        } else {
            renderArtists(host, library)
        }
    }

    private fun buildSegmentedRow(library: FavoriteFolders): LinearLayout {
        val row = style.row()
        val segment = style.row().apply {
            background = style.rounded(style.surface, 12)
            setPadding(style.dp(3), style.dp(3), style.dp(3), style.dp(3))
        }
        fun tabPill(labelRes: Int, selected: Boolean, onClick: () -> Unit) =
            style.text(activity.getString(labelRes), 14f, if (selected) style.foreground else style.muted, bold = selected).apply {
                gravity = Gravity.CENTER
                minHeight = style.dp(36)
                background = if (selected) style.rounded(style.selected, 9) else null
                setOnClickListener { onClick() }
            }
        segment.addView(tabPill(R.string.port_fav_works, !showingArtists) {
            if (showingArtists) {
                showingArtists = false
                render()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        segment.addView(tabPill(R.string.port_fav_artists, showingArtists) {
            if (!showingArtists) {
                showingArtists = true
                render()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(segment, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = style.dp(8) })
        if (!showingArtists) {
            val sortButton = style.action("⇅") {}.apply {
                contentDescription = activity.getString(R.string.port_fav_sort)
                setOnClickListener { showSortMenu(it) }
            }
            row.addView(sortButton, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = style.dp(6) })
        }
        val plusButton = style.action("＋") {}
        plusButton.contentDescription = activity.getString(
            if (showingArtists) R.string.workspace_add_artist else R.string.port_fav_new_folder
        )
        plusButton.setOnClickListener { view ->
            if (showingArtists) promptAddArtist(library)
            else showWorksMenu(library, null, view)
        }
        row.addView(plusButton)
        return row
    }

    private fun buildFolderTopRow(library: FavoriteFolders, folder: FavoriteFolder): LinearLayout {
        val row = style.row()
        row.addView(style.action("‹  " + activity.getString(R.string.port_back)) { handleBack() })
        row.addView(style.text(library.path(folder), 14f, style.muted, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            setPadding(style.dp(10), 0, style.dp(10), 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val sortButton = style.action("⇅") {}.apply {
            contentDescription = activity.getString(R.string.port_fav_sort)
            setOnClickListener { showSortMenu(it) }
        }
        row.addView(sortButton, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = style.dp(6) })
        val menuButton = style.action("•••") {}
        menuButton.setOnClickListener { showWorksMenu(library, folder, it) }
        menuButton.contentDescription = activity.getString(R.string.workspace_actions)
        row.addView(menuButton)
        return row
    }

    private fun showSortMenu(anchor: View) {
        val currentOrder = sortOrder()
        val currentAsc = sortAscending()
        PopupMenu(activity, anchor).apply {
            menu.add(1, 1, 0, R.string.port_fav_sort_added).apply { isChecked = currentOrder == FavoriteSortOrder.ADDED }
            menu.add(1, 2, 1, R.string.port_fav_sort_upload).apply { isChecked = currentOrder == FavoriteSortOrder.UPLOAD }
            menu.add(1, 3, 2, R.string.port_fav_sort_name).apply { isChecked = currentOrder == FavoriteSortOrder.NAME }
            menu.setGroupCheckable(1, true, true)
            menu.add(2, 4, 3, R.string.port_fav_sort_desc).apply { isChecked = !currentAsc }
            menu.add(2, 5, 4, R.string.port_fav_sort_asc).apply { isChecked = currentAsc }
            menu.setGroupCheckable(2, true, true)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> Preferences["port_fav_sort"] = FavoriteSortOrder.ADDED.name
                    2 -> Preferences["port_fav_sort"] = FavoriteSortOrder.UPLOAD.name
                    3 -> Preferences["port_fav_sort"] = FavoriteSortOrder.NAME.name
                    4 -> Preferences["port_fav_sort_asc"] = false
                    5 -> Preferences["port_fav_sort_asc"] = true
                }
                render()
                true
            }
        }.show()
    }

    private fun showWorksMenu(library: FavoriteFolders, folder: FavoriteFolder?, anchor: View) {
        PopupMenu(activity, anchor).apply {
            menu.add(0, 1, 0, if (folder == null) R.string.port_fav_new_folder else R.string.port_fav_new_subfolder)
            if (folder != null) {
                menu.add(0, 2, 1, R.string.port_fav_edit_folder)
                menu.add(0, 3, 2, R.string.port_fav_delete_folder)
            }
            menu.add(0, 4, 3, R.string.favorites_add_ids)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> openFolderEditor(folderID = null, parentID = folder?.id)
                    2 -> folder?.let { openFolderEditor(folderID = it.id, parentID = it.parentID) }
                    3 -> folder?.let { confirmDeleteFolder(library, it) }
                    4 -> promptAddIDs(library, folder?.id)
                }
                true
            }
        }.show()
    }

    private fun openFolderEditor(folderID: String?, parentID: String?) {
        activity.startActivity(Intent(activity, PortFavoriteFolderEditorActivity::class.java).apply {
            if (folderID != null) putExtra("folder_id", folderID)
            if (parentID != null) putExtra("parent_id", parentID)
        })
    }

    private fun confirmDeleteFolder(library: FavoriteFolders, folder: FavoriteFolder) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.port_fav_delete_folder)
            .setMessage(R.string.favorites_delete_folder_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                val deletedCurrent = folder.id == currentFolderID
                library.delete(folder.id)
                removeAutoCacheEntry(folder.id)
                if (deletedCurrent) {
                    currentFolderID = folder.parentID
                    onTitleChanged()
                }
                render()
            }
            .show()
    }

    private fun promptAddIDs(library: FavoriteFolders, folderID: String?) {
        val input = EditText(activity).apply {
            hint = "12345, 67890"
            setTextColor(style.foreground)
            setHintTextColor(style.muted)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.favorites_add_ids)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val ids = input.text.toString().trim().split(Regex("[,\\s]+"))
                    .mapNotNull { it.toIntOrNull()?.takeIf { id -> id > 0 } }.distinct()
                if (ids.isNotEmpty()) {
                    synchronized(favorites) { favorites.addAll(ids) }
                    folderID?.let { library.addToFolder(it, ids) }
                    clearAutoCache()
                    render()
                }
            }
            .show()
    }

    private fun renderWorks(host: LinearLayout, library: FavoriteFolders, currentFolder: FavoriteFolder?) {
        val allFavs = synchronized(favorites) { favorites.toList().asReversed() }
        val favSet = allFavs.toHashSet()
        val children = library.folders
            .filter { it.parentID == currentFolder?.id }
            .sortedWith(compareBy<FavoriteFolder> { it.name.lowercase() }.thenBy { it.createdAt })

        val manualIDs = if (currentFolder == null) allFavs else currentFolder.manualGalleryIDs.filter { it in favSet }

        val contentStack = style.stack()
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            addView(contentStack)
        }
        host.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        if (currentFolder != null && currentFolder.autoRuleQuery.isNotBlank()) {
            val ruleBox = style.stack().apply {
                background = style.rounded(style.surface, 14)
                setPadding(style.dp(14), style.dp(12), style.dp(14), style.dp(12))
            }
            ruleBox.addView(style.text(activity.getString(R.string.port_fav_auto_sorting), 13f, style.muted, bold = true))
            ruleBox.addView(style.text(currentFolder.autoRuleQuery, 14f, style.foreground).apply {
                typeface = Typeface.MONOSPACE
                setPadding(0, style.dp(4), 0, style.dp(4))
            })
            ruleBox.addView(style.text(activity.getString(R.string.port_fav_auto_sorting_note), 12f, style.muted))
            contentStack.addView(ruleBox, style.matchWrap().apply { bottomMargin = style.dp(12) })
        }

        if (children.isNotEmpty()) {
            contentStack.addView(style.text(
                activity.getString(if (currentFolder == null) R.string.port_fav_folders_section else R.string.port_fav_subfolders_section),
                14f, style.muted, bold = true
            ).apply {
                ViewCompat.setAccessibilityHeading(this, true)
                setPadding(style.dp(4), style.dp(4), 0, style.dp(6))
            })
            val folderMetaLabels = mutableMapOf<String, TextView>()
            for (folder in children) {
                val (row, metaView) = buildFolderRow(library, folder, favSet)
                folderMetaLabels[folder.id] = metaView
                contentStack.addView(row, style.matchWrap().apply { bottomMargin = style.dp(6) })
            }
            val favSignature = favoritesSignature(allFavs)
            val staleAutoChildren = children.filter { child ->
                child.autoRuleQuery.isNotBlank() && allFavs.isNotEmpty() &&
                    autoMatchCache[child.id]?.let { it.rule == child.autoRuleQuery && it.favoritesHash == favSignature } != true
            }
            if (staleAutoChildren.isNotEmpty()) {
                val parentSnapshot = currentFolderID
                childRuleJob = activity.lifecycleScope.launch {
                    for (child in staleAutoChildren) {
                        val terms = RecommendationEngine.tagTerms(child.autoRuleQuery)
                        val matchedSet = mutableSetOf<Int>()
                        withContext(Dispatchers.IO) {
                            for (term in terms) {
                                val ids = runCatching { doSearch(term, SortMode.DATE_ADDED) }.getOrDefault(emptyList())
                                for (id in ids) if (id in favSet) matchedSet += id
                            }
                        }
                        val orderedAuto = allFavs.filter { it in matchedSet }
                        saveAutoCacheEntry(child.id, AutoCacheEntry(child.autoRuleQuery, favSignature, orderedAuto))
                        if (currentFolderID != parentSnapshot) return@launch
                        val childCount = library.folders.count { it.parentID == child.id }
                        val galleryCount = (child.manualGalleryIDs.filter { it in favSet } + orderedAuto).distinct().size
                        folderMetaLabels[child.id]?.text = if (childCount > 0) {
                            activity.getString(R.string.port_fav_folder_counts, galleryCount, childCount)
                        } else {
                            activity.getString(R.string.port_fav_folder_gallery_count, galleryCount)
                        }
                    }
                }
            }
        }

        val worksHeader = style.text(
            activity.getString(if (currentFolder == null) R.string.port_fav_all_section else R.string.port_fav_folder_works_section),
            14f, style.muted, bold = true
        ).apply {
            ViewCompat.setAccessibilityHeading(this, true)
            setPadding(style.dp(4), style.dp(10), 0, style.dp(6))
        }
        val statusText = style.text("", 15f, style.muted).apply {
            gravity = Gravity.CENTER
            setPadding(style.dp(16), style.dp(24), style.dp(16), style.dp(24))
        }

        val adapter = PortGalleryAdapter(
            activity = activity,
            style = style,
            scope = activity.lifecycleScope,
            card = false,
            open = openGallery,
            actions = { galleryID ->
                if (currentFolder != null && galleryID in currentFolder.manualGalleryIDs) {
                    AlertDialog.Builder(activity)
                        .setTitle(activity.getString(R.string.port_gallery_id, galleryID))
                        .setItems(arrayOf(
                            activity.getString(R.string.port_details),
                            activity.getString(R.string.port_fav_remove_from_folder),
                        )) { _, which ->
                            if (which == 0) openGallery(galleryID)
                            else {
                                library.removeFromFolder(currentFolder.id, galleryID)
                                render()
                            }
                        }
                        .show()
                } else {
                    galleryActions(galleryID)
                }
            },
        )
        val worksRecycler = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            this.adapter = adapter
            isNestedScrollingEnabled = false
            itemAnimator = null
            setPadding(0, 0, 0, style.dp(75))
            clipToPadding = false
        }

        fun applyWorks(ids: List<Int>, loadingRule: Boolean = false) {
            nameSortJob?.cancel()
            val order = sortOrder()
            val ascending = sortAscending()
            val initialSorted = if (order == FavoriteSortOrder.NAME) {
                sortFavoriteGalleryIDs(ids, allFavs, FavoriteSortOrder.ADDED, false)
            } else {
                sortFavoriteGalleryIDs(ids, allFavs, order, ascending)
            }

            if (ids.isEmpty() && children.isEmpty()) {
                worksHeader.visibility = View.GONE
                worksRecycler.visibility = View.GONE
                statusText.visibility = View.VISIBLE
                statusText.text = if (loadingRule) {
                    activity.getString(R.string.workspace_favorite_rule_loading)
                } else {
                    val title = activity.getString(if (currentFolder == null) R.string.port_fav_empty_title else R.string.port_fav_folder_empty_title)
                    val desc = activity.getString(if (currentFolder == null) R.string.port_fav_empty_desc else R.string.port_fav_folder_empty_desc)
                    "$title\n\n$desc"
                }
            } else if (ids.isEmpty()) {
                worksHeader.visibility = if (loadingRule) View.VISIBLE else View.GONE
                worksRecycler.visibility = View.GONE
                statusText.visibility = if (loadingRule) View.VISIBLE else View.GONE
                if (loadingRule) statusText.text = activity.getString(R.string.workspace_favorite_rule_loading)
            } else {
                worksHeader.visibility = View.VISIBLE
                worksRecycler.visibility = View.VISIBLE
                statusText.visibility = View.GONE
                adapter.submitList(initialSorted)
                if (order == FavoriteSortOrder.NAME) {
                    val folderSnapshot = currentFolderID
                    nameSortJob = activity.lifecycleScope.launch {
                        val titles = withContext(Dispatchers.IO) {
                            ids.associateWith { id ->
                                runCatching { Cache.getInstance(activity, id).getGalleryInfo()?.title }.getOrNull().orEmpty()
                            }.filterValues { it.isNotBlank() }
                        }
                        if (currentFolderID != folderSnapshot) return@launch
                        adapter.submitList(sortFavoriteGalleryIDs(ids, allFavs, FavoriteSortOrder.NAME, ascending, titles))
                    }
                }
            }
        }

        contentStack.addView(worksHeader)
        contentStack.addView(statusText)
        contentStack.addView(worksRecycler, style.matchWrap())

        val favSignature = favoritesSignature(allFavs)
        if (currentFolder != null && currentFolder.autoRuleQuery.isNotBlank() && allFavs.isNotEmpty()) {
            val cached = autoMatchCache[currentFolder.id]
            if (cached != null && cached.rule == currentFolder.autoRuleQuery && cached.favoritesHash == favSignature) {
                applyWorks((manualIDs + cached.matched.filter { it in favSet }).distinct())
            } else {
                val warmAuto = cached?.takeIf { it.rule == currentFolder.autoRuleQuery }?.matched?.filter { it in favSet }.orEmpty()
                val initialIDs = (manualIDs + warmAuto).distinct()
                applyWorks(initialIDs, loadingRule = initialIDs.isEmpty())
                val targetFolderID = currentFolder.id
                val ruleQuery = currentFolder.autoRuleQuery
                ruleJob = activity.lifecycleScope.launch {
                    try {
                        val terms = RecommendationEngine.tagTerms(ruleQuery)
                        val matchedSet = mutableSetOf<Int>()
                        withContext(Dispatchers.IO) {
                            for (term in terms) {
                                val ids = runCatching { doSearch(term, SortMode.DATE_ADDED) }.getOrDefault(emptyList())
                                for (id in ids) if (id in favSet) matchedSet += id
                            }
                        }
                        val orderedAuto = allFavs.filter { it in matchedSet }
                        saveAutoCacheEntry(targetFolderID, AutoCacheEntry(ruleQuery, favSignature, orderedAuto))
                        if (currentFolderID != targetFolderID) return@launch
                        applyWorks((manualIDs + orderedAuto).distinct())
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (manualIDs.isEmpty()) {
                            statusText.visibility = View.VISIBLE
                            statusText.text = activity.getString(R.string.workspace_favorite_rule_failed)
                        }
                    }
                }
            }
        } else {
            applyWorks(manualIDs)
        }
    }

    private fun buildFolderRow(library: FavoriteFolders, folder: FavoriteFolder, favSet: Set<Int>): Pair<LinearLayout, TextView> {
        val childCount = library.folders.count { it.parentID == folder.id }
        val autoCachedCount = autoMatchCache[folder.id]?.takeIf { it.rule == folder.autoRuleQuery }?.matched.orEmpty()
        val galleryCount = (folder.manualGalleryIDs.filter { it in favSet } + autoCachedCount.filter { it in favSet }).distinct().size

        val row = style.row().apply {
            background = style.ripple(style.surface, 14)
            setPadding(style.dp(12), style.dp(10), style.dp(8), style.dp(10))
            isFocusable = true
            setOnClickListener {
                currentFolderID = folder.id
                onTitleChanged()
                render()
            }
            setOnLongClickListener {
                showFolderContextMenu(library, folder)
                true
            }
        }
        row.addView(ImageView(activity).apply {
            setImageResource(R.drawable.book_open)
            imageTintList = ColorStateList.valueOf(0xFFFFB300.toInt())
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, LinearLayout.LayoutParams(style.dp(26), style.dp(26)).apply { marginEnd = style.dp(12) })

        val info = style.stack()
        info.addView(style.text(folder.name, 16f, style.foreground, bold = true))
        val metaText = if (childCount > 0) {
            activity.getString(R.string.port_fav_folder_counts, galleryCount, childCount)
        } else {
            activity.getString(R.string.port_fav_folder_gallery_count, galleryCount)
        }
        val metaView = style.text(metaText, 12f, style.muted)
        info.addView(metaView)
        if (folder.autoRuleQuery.isNotBlank()) {
            info.addView(style.text(folder.autoRuleQuery, 12f, style.muted).apply {
                typeface = Typeface.MONOSPACE
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(style.text("⋮", 18f, style.muted, bold = true).apply {
            gravity = Gravity.CENTER
            minWidth = style.dp(40)
            minHeight = style.dp(40)
            setOnClickListener { showFolderContextMenu(library, folder) }
        })
        return row to metaView
    }

    private fun showFolderContextMenu(library: FavoriteFolders, folder: FavoriteFolder) {
        AlertDialog.Builder(activity)
            .setTitle(folder.name)
            .setItems(arrayOf(
                activity.getString(R.string.port_fav_edit_folder),
                activity.getString(R.string.port_fav_delete_folder),
            )) { _, which ->
                if (which == 0) openFolderEditor(folder.id, folder.parentID)
                else confirmDeleteFolder(library, folder)
            }
            .show()
    }

    private fun renderArtists(host: LinearLayout, library: FavoriteFolders) {
        val artists = library.artistNames()
        if (artists.isEmpty()) {
            host.addView(style.text(
                activity.getString(R.string.port_fav_artists_empty) + "\n\n" + activity.getString(R.string.port_fav_artists_empty_desc),
                15f, style.muted
            ).apply {
                gravity = Gravity.CENTER
                setPadding(style.dp(20), style.dp(40), style.dp(20), style.dp(40))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            return
        }

        val listStack = style.stack().apply { setPadding(0, 0, 0, style.dp(75)) }
        for (artist in artists) {
            val row = style.row().apply {
                background = style.ripple(style.surface, 14)
                setPadding(style.dp(12), style.dp(10), style.dp(8), style.dp(10))
                isFocusable = true
                setOnClickListener {
                    openArtistOverlay(artist, "artist:${artist.trim().replace(' ', '_')}")
                }
            }
            row.addView(ImageView(activity).apply {
                setImageResource(R.drawable.account_star)
                imageTintList = ColorStateList.valueOf(0xFFFF9800.toInt())
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(style.dp(26), style.dp(26)).apply { marginEnd = style.dp(12) })
            row.addView(style.stack().apply {
                addView(style.text(artist, 16f, style.foreground, bold = true))
                addView(style.text(activity.getString(R.string.port_fav_artist_tap_search), 12f, style.muted))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(style.text("✕", 16f, style.danger, bold = true).apply {
                gravity = Gravity.CENTER
                minWidth = style.dp(40)
                minHeight = style.dp(40)
                contentDescription = activity.getString(R.string.delete)
                setOnClickListener {
                    library.removeArtist(artist)
                    render()
                }
            })
            listStack.addView(row, style.matchWrap().apply { bottomMargin = style.dp(6) })
        }
        host.addView(ScrollView(activity).apply {
            clipToPadding = false
            addView(listStack)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun promptAddArtist(library: FavoriteFolders) {
        val input = EditText(activity).apply {
            isSingleLine = true
            hint = "artist"
            setTextColor(style.foreground)
            setHintTextColor(style.muted)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.workspace_add_artist)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                runCatching { library.addArtist(input.text.toString()) }
                    .onSuccess { render() }
            }
            .show()
    }
}
