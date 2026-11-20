package xyz.sabok.umtomi.ui

import android.content.Intent
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.sabok.umtomi.R
import xyz.sabok.umtomi.favorites
import xyz.sabok.umtomi.hitomi.GalleryInfo
import xyz.sabok.umtomi.hitomi.SortMode
import xyz.sabok.umtomi.hitomi.doSearch
import xyz.sabok.umtomi.services.DownloadService
import xyz.sabok.umtomi.util.FavoriteFolders
import xyz.sabok.umtomi.util.ReaderTabStore
import xyz.sabok.umtomi.util.downloader.Cache
import xyz.sabok.umtomi.util.downloader.DownloadManager

/** Android port of the iOS GalleryDetailView and DetailSearchOverlayView. */
internal class PortGalleryDetail(
    private val activity: MainActivity,
    private val style: PortStyle,
    private val back: () -> Unit,
    private val openGallery: (Int) -> Unit,
    private val openOverlay: (View) -> Unit,
    private val read: (Int) -> Unit,
    private val tabsChanged: () -> Unit,
) {
    private val jobs = mutableListOf<Job>()
    private var favoriteButton: TextView? = null
    private var offlineButton: TextView? = null
    private val manager get() = DownloadManager.getInstance(activity)
    private fun label(id: Int) = activity.getString(id)

    fun create(id: Int): LinearLayout {
        val container = style.stack().apply { setBackgroundColor(style.background) }
        val header = style.row().apply {
            setPadding(style.dp(14), style.dp(8), style.dp(14), style.dp(7))
            addView(style.action("‹  " + label(R.string.port_back), click = back))
            addView(style.text(label(R.string.port_details), 17f, bold = true).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        container.addView(header)
        val content = style.stack().apply {
            setPadding(style.dp(16), 0, style.dp(16), style.dp(24))
        }
        container.addView(ScrollView(activity).apply {
            addView(content)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(ProgressBar(activity), LinearLayout.LayoutParams(style.dp(28), style.dp(28)).apply {
            gravity = Gravity.CENTER
            topMargin = style.dp(22)
        })
        jobs += activity.lifecycleScope.launch {
            val cache = withContext(Dispatchers.IO) { Cache.getInstance(activity, id) }
            val info = try {
                cache.getGalleryInfo()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            content.removeAllViews()
            val cover = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = style.rounded(style.surface, 18)
                clipToOutline = true
                contentDescription = label(R.string.port_cover)
            }
            if (info != null) content.addView(cover, LinearLayout.LayoutParams(-1, style.dp(300)))
            content.addView(style.text(info?.title ?: activity.getString(R.string.port_gallery_id, id), 24f, bold = true),
                style.matchWrap().apply { topMargin = style.dp(14) })
            info?.japanese_title?.takeIf { it.isNotBlank() && info.title != it }?.let {
                content.addView(style.text(it, 15f, style.muted))
            }
            addActions(content, id)
            if (info == null) {
                content.addView(style.text(label(R.string.port_load_failed), 15f, style.muted),
                    style.matchWrap().apply { topMargin = style.dp(16) })
                content.addView(style.action(label(R.string.port_retry)) { openGallery(id) }, style.matchWrap())
            } else {
                addMetadata(content, id, info)
                addRelated(content, info.related)
                runCatching { loadPortCover(activity, cache.getThumbnail(), 640) }.getOrNull()?.let(cover::setImageBitmap)
            }
        }
        return container
    }

    fun refreshActions(id: Int) {
        val favorited = synchronized(favorites) { id in favorites }
        favoriteButton?.setText(if (favorited) R.string.port_favorited else R.string.port_favorite)
        offlineButton?.setText(when {
            manager.isDownloading(id) -> R.string.port_cancel_download
            manager.getDownloadFolder(id) != null -> R.string.port_remove_download
            else -> R.string.port_download
        })
    }

    private fun addActions(content: LinearLayout, id: Int) {
        val row = style.row()
        row.addView(style.action(label(R.string.port_read), prominent = true) {
            AlertDialog.Builder(activity).setItems(arrayOf(label(R.string.port_read), label(R.string.port_background_tab))) { _, item ->
                if (item == 0) read(id) else backgroundTab(id)
            }.show()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        favoriteButton = style.action("") {
            synchronized(favorites) { if (id in favorites) favorites.remove(id) else favorites.add(id) }
            refreshActions(id)
        }.apply {
            setOnLongClickListener { chooseFolders(id); true }
        }
        row.addView(favoriteButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = style.dp(8) })
        content.addView(row, style.matchWrap().apply { topMargin = style.dp(14) })
        offlineButton = style.action("") { downloadActions(id) }
        content.addView(offlineButton, style.matchWrap().apply { topMargin = style.dp(8) })
        content.addView(style.action(label(R.string.port_favorite_folders)) { chooseFolders(id) },
            style.matchWrap().apply { topMargin = style.dp(8) })
        refreshActions(id)
    }

    private fun chooseFolders(id: Int) {
        val store = FavoriteFolders(activity)
        val folders = store.folders
        if (folders.isEmpty()) {
            activity.startActivity(Intent(activity, PortFavoriteFolderEditorActivity::class.java))
            return
        }
        val checked = folders.map { id in it.manualGalleryIDs }.toBooleanArray()
        AlertDialog.Builder(activity).setTitle(R.string.port_favorite_folders)
            .setMultiChoiceItems(folders.map(store::path).toTypedArray(), checked) { _, index, enabled ->
                checked[index] = enabled
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selection = folders.filterIndexed { index, _ -> checked[index] }.map { it.id }.toSet()
                if (selection.isNotEmpty()) synchronized(favorites) { if (id !in favorites) favorites.add(id) }
                FavoriteFolders(activity).assign(id, selection)
                refreshActions(id)
            }.show()
    }

    private fun addMetadata(content: LinearLayout, id: Int, info: GalleryInfo) {
        content.addView(style.text(label(R.string.port_metadata), 19f, bold = true),
            style.matchWrap().apply { topMargin = style.dp(20); bottomMargin = style.dp(8) })
        val group = style.stack().apply {
            background = style.rounded(style.surface, 14)
            setPadding(style.dp(14), style.dp(10), style.dp(14), style.dp(14))
        }
        fun metaRow(heading: Int, value: String) {
            if (value.isBlank()) return
            group.addView(style.text(label(heading), 12f, style.muted), style.matchWrap().apply { topMargin = style.dp(8) })
            group.addView(style.text(value, 15f, style.foreground), style.matchWrap().apply { topMargin = style.dp(2) })
        }

        group.addView(style.text(activity.getString(R.string.port_gallery_meta, info.type, info.files.size), 15f, style.muted))
        metaRow(R.string.port_languages, info.language.orEmpty())
        metaRow(R.string.port_detail_date, info.date)

        val artists = info.artists.orEmpty().map { it.artist }.distinct()
        if (artists.isNotEmpty()) {
            group.addView(style.text(label(R.string.port_artists), 12f, style.muted),
                style.matchWrap().apply { topMargin = style.dp(12); bottomMargin = style.dp(4) })
            val library = runCatching { FavoriteFolders(activity) }.getOrNull()
            var favArtists = library?.artistNames()?.map { it.replace('_', ' ').lowercase() }?.toSet().orEmpty()
            for (artist in artists) {
                val artistRow = style.row().apply { setPadding(0, style.dp(3), 0, style.dp(3)) }
                val query = "artist:${artist.trim().replace(' ', '_')}"
                artistRow.addView(style.action(artist) {
                    openSearchOverlay(artist, label(R.string.port_artists), query)
                }.apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    minHeight = style.dp(40)
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = style.dp(8) })
                val star = style.action(if (artist.trim().replace('_', ' ').lowercase() in favArtists) "★" else "☆") {}
                star.minHeight = style.dp(40)
                star.setOnClickListener {
                    val lib = runCatching { FavoriteFolders(activity) }.getOrNull() ?: return@setOnClickListener
                    val normalized = artist.trim().replace('_', ' ').lowercase()
                    val existing = lib.artistNames().firstOrNull { it.replace('_', ' ').lowercase() == normalized }
                    if (existing != null) lib.removeArtist(existing) else lib.addArtist(artist)
                    favArtists = lib.artistNames().map { it.replace('_', ' ').lowercase() }.toSet()
                    star.text = if (normalized in favArtists) "★" else "☆"
                }
                artistRow.addView(star, LinearLayout.LayoutParams(style.dp(48), -2))
                group.addView(artistRow, style.matchWrap())
            }
        }

        fun chipSection(heading: Int, caption: String, values: List<Pair<String, String>>) {
            if (values.isEmpty()) return
            group.addView(style.text(label(heading), 12f, style.muted),
                style.matchWrap().apply { topMargin = style.dp(12); bottomMargin = style.dp(4) })
            for ((display, filter) in values.distinctBy { it.second }) {
                group.addView(style.action(display) {
                    openSearchOverlay(display, caption, filter.replace(' ', '_'))
                }.apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    minHeight = style.dp(40)
                }, style.matchWrap().apply { topMargin = style.dp(4) })
            }
        }

        chipSection(R.string.port_groups, label(R.string.port_groups),
            info.groups.orEmpty().map { it.group to "group:${it.group}" })
        metaRow(R.string.port_series, info.parodys.orEmpty().joinToString { it.parody })
        metaRow(R.string.port_detail_characters, info.characters.orEmpty().joinToString { it.character })
        metaRow(R.string.port_detail_availability,
            label(if (manager.getDownloadFolder(id) != null) R.string.port_detail_offline else R.string.port_detail_online_only))

        val tags = info.tags.orEmpty().map {
            val category = when {
                !it.female.isNullOrEmpty() -> "female:"
                !it.male.isNullOrEmpty() -> "male:"
                else -> ""
            }
            val display = category + it.tag
            val filter = if (category.isEmpty()) "tag:${it.tag}" else display
            display to filter
        }
        chipSection(R.string.port_tags, label(R.string.port_tags), tags)

        content.addView(group, style.matchWrap())
    }

    private fun openSearchOverlay(title: String, caption: String, query: String) {
        val overlay = style.stack().apply { setBackgroundColor(style.background) }
        val header = style.row().apply {
            setPadding(style.dp(14), style.dp(8), style.dp(14), style.dp(7))
            addView(style.action("‹  " + label(R.string.port_back), click = back))
            addView(style.text(title, 17f, bold = true).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        overlay.addView(header)
        overlay.addView(style.text("$caption  ·  ${query.replace('_', ' ')}", 13f, style.muted).apply {
            setPadding(style.dp(16), style.dp(2), style.dp(16), style.dp(8))
        })

        val spinner = ProgressBar(activity).apply {
            layoutParams = LinearLayout.LayoutParams(style.dp(28), style.dp(28)).apply {
                gravity = Gravity.CENTER
                topMargin = style.dp(28)
            }
        }
        val emptyMsg = style.text(label(R.string.port_empty), 15f, style.muted).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(style.dp(16), style.dp(32), style.dp(16), style.dp(32))
        }
        val adapter = PortGalleryAdapter(activity, style, activity.lifecycleScope, false, openGallery) { gid ->
            AlertDialog.Builder(activity).setTitle(activity.getString(R.string.port_gallery_id, gid))
                .setItems(intArrayOf(R.string.port_details, R.string.port_read, R.string.port_background_tab)
                    .map { activity.getString(it) }.toTypedArray()) { _, which ->
                    when (which) {
                        0 -> openGallery(gid)
                        1 -> read(gid)
                        2 -> backgroundTab(gid)
                    }
                }.show()
        }
        val list = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            this.adapter = adapter
            itemAnimator = null
            visibility = View.GONE
            setPadding(style.dp(14), 0, style.dp(14), style.dp(24))
            clipToPadding = false
        }
        overlay.addView(spinner)
        overlay.addView(emptyMsg)
        overlay.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        openOverlay(overlay)

        jobs += activity.lifecycleScope.launch {
            val ids = try {
                withContext(Dispatchers.IO) { doSearch(query, SortMode.DATE_ADDED).distinct().take(40) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            spinner.visibility = View.GONE
            if (ids.isEmpty()) {
                emptyMsg.visibility = View.VISIBLE
            } else {
                list.visibility = View.VISIBLE
                adapter.submitList(ids)
            }
        }
    }

    private fun addRelated(content: LinearLayout, ids: List<Int>) {
        val related = ids.filter { it > 0 }.distinct().take(6)
        if (related.isEmpty()) return
        content.addView(style.text(label(R.string.gallery_related), 19f, bold = true),
            style.matchWrap().apply { topMargin = style.dp(18); bottomMargin = style.dp(8) })
        val adapter = PortGalleryAdapter(activity, style, activity.lifecycleScope, false, openGallery) { gid ->
            AlertDialog.Builder(activity).setTitle(activity.getString(R.string.port_gallery_id, gid))
                .setItems(intArrayOf(R.string.port_details, R.string.port_read, R.string.port_background_tab)
                    .map { activity.getString(it) }.toTypedArray()) { _, which ->
                    when (which) {
                        0 -> openGallery(gid)
                        1 -> read(gid)
                        2 -> backgroundTab(gid)
                    }
                }.show()
        }
        val recycler = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            this.adapter = adapter
            isNestedScrollingEnabled = false
            itemAnimator = null
        }
        adapter.submitList(related)
        content.addView(recycler, style.matchWrap())
    }

    fun backgroundTab(id: Int) {
        ReaderTabStore(activity).open(id, activate = false)
        tabsChanged()
        android.widget.Toast.makeText(activity, R.string.port_tab_added, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun downloadActions(id: Int) {
        if (manager.isDownloading(id)) {
            DownloadService.cancel(activity, id)
            refreshActions(id)
        } else if (manager.getDownloadFolder(id) != null) {
            AlertDialog.Builder(activity).setMessage(R.string.port_remove_download)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    DownloadService.delete(activity, id)
                    refreshActions(id)
                }.show()
        } else {
            activity.allowDownloadNotifications()
            DownloadService.download(activity, id)
            offlineButton?.setText(R.string.port_cancel_download)
        }
    }

    fun unbind() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        favoriteButton = null
        offlineButton = null
    }
}
