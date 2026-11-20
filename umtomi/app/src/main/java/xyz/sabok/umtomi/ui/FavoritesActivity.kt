package xyz.sabok.umtomi.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
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
import xyz.sabok.umtomi.adapters.GalleryBlockAdapter
import xyz.sabok.umtomi.favorites
import xyz.sabok.umtomi.hitomi.SortMode
import xyz.sabok.umtomi.hitomi.doSearch
import xyz.sabok.umtomi.ui.dialog.GalleryDialog
import xyz.sabok.umtomi.util.FavoriteFolders
import xyz.sabok.umtomi.util.FavoritesFiles
import xyz.sabok.umtomi.util.ItemClickSupport
import xyz.sabok.umtomi.util.downloader.Cache

class FavoritesActivity : BaseActivity() {
    private lateinit var library: FavoriteFolders
    private lateinit var folderRow: LinearLayout
    private lateinit var list: RecyclerView
    private lateinit var breadcrumb: TextView
    private lateinit var empty: TextView
    private val galleryIDs = mutableListOf<Int>()
    private var selectedFolder: String? = null
    private var galleryAdapter: GalleryBlockAdapter? = null
    private var ruleJob: Job? = null

    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch {
            runCatching {
                val json = FavoritesFiles.export(this@FavoritesActivity)
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(json) }
                        ?: error("Cannot write destination")
                }
            }.onSuccess { message(getString(R.string.favorites_export_done)) }.onFailure { showError(it) }
        }
    }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("Cannot read backup")
                }
                FavoritesFiles.restore(this@FavoritesActivity, text)
            }.onSuccess { if (loadLibrary()) render(); message(getString(R.string.favorites_import_done, it)) }.onFailure { showError(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedFolder = savedInstanceState?.getString("folder")
        title = getString(R.string.main_drawer_favorite)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.color.workspace_background)
        }
        breadcrumb = TextView(this).apply {
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@FavoritesActivity, R.color.workspace_text))
            setPadding(24, 20, 24, 12)
        }
        root.addView(breadcrumb)
        folderRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply { addView(folderRow) })
        empty = TextView(this).apply { setText(R.string.favorites_folder_empty); setPadding(24, 24, 24, 24) }
        root.addView(empty)
        list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@FavoritesActivity) }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom
            view.setPadding(0, 0, 0, bottom); insets
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (selectedFolder == null) finish() else goUp() }
        })
        if (savedInstanceState == null) when (intent.getStringExtra("action")) {
            "export" -> exportFile.launch("UmtomiFavorites-${System.currentTimeMillis()}.json")
            "import" -> importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
    }

    override fun onResume() {
        super.onResume()
        if (loadLibrary()) render()
    }

    private fun loadLibrary(): Boolean = try {
        library = FavoriteFolders(this)
        if (library.folders.none { it.id == selectedFolder }) selectedFolder = null
        true
    } catch (error: Exception) {
        AlertDialog.Builder(this).setTitle(R.string.error).setMessage(error.localizedMessage)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }.setOnCancelListener { finish() }.show()
        false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("folder", selectedFolder); super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { galleryAdapter?.updateAll = false; super.onDestroy() }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.favorites_new_folder)
        menu.add(0, 2, 1, R.string.favorites_add_ids)
        menu.add(0, 3, 2, R.string.favorites_choose_existing)
        menu.add(0, 4, 3, R.string.favorites_export)
        menu.add(0, 5, 4, R.string.favorites_import)
        menu.add(0, 6, 5, R.string.workspace_favorite_artists)
        menu.add(0, 7, 6, R.string.workspace_favorite_rule)
        return true
    }
    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(7)?.isVisible = selectedFolder != null
        return super.onPrepareOptionsMenu(menu)
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> { if (selectedFolder == null) finish() else goUp() }
            1 -> promptName(null)
            2 -> promptIDs()
            3 -> chooseExisting()
            4 -> exportFile.launch("UmtomiFavorites-${System.currentTimeMillis()}.json")
            5 -> importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            6 -> showArtists()
            7 -> selectedFolder?.let(::editRule)
            else -> return super.onOptionsItemSelected(item)
        }; return true
    }

    private fun goUp() {
        selectedFolder = library.folders.find { it.id == selectedFolder }?.parentID; render()
    }

    private fun render() {
        ruleJob?.cancel()
        invalidateOptionsMenu()
        val folder = library.folders.find { it.id == selectedFolder }
        empty.setText(R.string.favorites_folder_empty)
        breadcrumb.text = folder?.let(library::path) ?: getString(R.string.favorites_all)
        folderRow.removeAllViews()
        if (selectedFolder != null) folderRow.addView(Button(this).apply {
            setText(R.string.favorites_parent); setOnClickListener { goUp() }
        })
        library.folders.filter { it.parentID == selectedFolder }.forEach { child ->
            folderRow.addView(Button(this).apply {
                isAllCaps = false; text = "${child.name} (${child.manualGalleryIDs.count { it in favorites }})"
                setOnClickListener { selectedFolder = child.id; render() }
                setOnLongClickListener {
                    AlertDialog.Builder(this@FavoritesActivity).setTitle(child.name)
                        .setItems(arrayOf(getString(R.string.favorites_rename), getString(R.string.delete))) { _, which ->
                            if (which == 0) promptName(child.id)
                            else AlertDialog.Builder(this@FavoritesActivity).setMessage(R.string.favorites_delete_folder_message)
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(R.string.delete) { _, _ -> action { library.delete(child.id); render() } }.show()
                        }.show(); true
                }
            })
        }
        galleryIDs.clear()
        galleryIDs.addAll((folder?.manualGalleryIDs?.filter { it in favorites } ?: favorites.toList()).reversed())
        empty.visibility = if (galleryIDs.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        galleryAdapter?.updateAll = false
        galleryAdapter = GalleryBlockAdapter(galleryIDs).apply {
            onDownloadClickedHandler = { position -> galleryIDs.getOrNull(position)?.let { GalleryDialog(this@FavoritesActivity, it).show() } }
            onDeleteClickedHandler = { position -> galleryIDs.getOrNull(position)?.let(::itemActions) }
        }
        list.adapter = galleryAdapter
        ItemClickSupport.addTo(list).apply {
            onItemClickListener = { _, position, _ -> galleryIDs.getOrNull(position)?.let { id ->
                startActivity(Intent(this@FavoritesActivity, ReaderActivity::class.java).putExtra("galleryID", id))
            } }
            onItemLongClickListener = { _, position, _ -> galleryIDs.getOrNull(position)?.let(::itemActions); true }
        }
        if (folder != null && folder.autoRuleQuery.isNotBlank() && favorites.isNotEmpty()) {
            val manual = galleryIDs.toList()
            if (manual.isEmpty()) empty.setText(R.string.workspace_favorite_rule_loading)
            ruleJob = lifecycleScope.launch {
                try {
                    val matches = withContext(Dispatchers.IO) { doSearch(folder.autoRuleQuery, SortMode.DATE_ADDED).toSet() }
                    if (selectedFolder != folder.id) return@launch
                    val automatic = favorites.toList().asReversed().filter { it in matches }
                    galleryIDs.clear()
                    galleryIDs.addAll((manual + automatic).distinct())
                    galleryAdapter?.notifyDataSetChanged()
                    empty.setText(R.string.favorites_folder_empty)
                    empty.visibility = if (galleryIDs.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (manual.isEmpty()) empty.setText(R.string.workspace_favorite_rule_failed)
                    else message(getString(R.string.workspace_favorite_rule_failed))
                }
            }
        }
    }

    private fun editRule(id: String) {
        val folder = library.folders.find { it.id == id } ?: return
        val input = EditText(this).apply {
            isSingleLine = true
            hint = "artist:example"
            setText(folder.autoRuleQuery)
        }
        AlertDialog.Builder(this).setTitle(R.string.workspace_favorite_rule)
            .setMessage(R.string.workspace_favorite_rule_help).setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> action {
                if (loadLibrary()) { library.setAutoRule(id, input.text.toString()); render() }
            } }.show()
    }

    private fun showArtists() {
        if (!loadLibrary()) return
        val artists = library.artistNames()
        AlertDialog.Builder(this).setTitle(R.string.workspace_favorite_artists)
            .setItems(artists.toTypedArray()) { _, index ->
                val name = artists[index]
                AlertDialog.Builder(this).setTitle(name)
                    .setItems(intArrayOf(R.string.workspace_search, R.string.delete).map { getString(it) }.toTypedArray()) { _, actionIndex ->
                        if (actionIndex == 0) {
                            startActivity(Intent(this, MainActivity::class.java)
                                .putExtra("workspace_query", "artist:${name.trim().replace(' ', '_')}"))
                        } else action { if (loadLibrary()) { library.removeArtist(name); render() } }
                    }.show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.workspace_add_artist) { _, _ ->
                val input = EditText(this).apply { isSingleLine = true }
                AlertDialog.Builder(this).setTitle(R.string.workspace_add_artist).setView(input)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ -> action {
                        if (loadLibrary()) { library.addArtist(input.text.toString()); showArtists() }
                    } }.show()
            }.show()
    }

    private fun promptName(id: String?) {
        val input = EditText(this).apply { isSingleLine = true; setText(library.folders.find { it.id == id }?.name.orEmpty()) }
        AlertDialog.Builder(this).setTitle(if (id == null) R.string.favorites_new_folder else R.string.favorites_rename)
            .setView(input).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> action {
                if (id == null) library.create(input.text.toString(), selectedFolder) else library.rename(id, input.text.toString())
                render()
            } }.show()
    }

    private fun promptIDs() {
        val input = EditText(this).apply { hint = "12345, 67890" }
        AlertDialog.Builder(this).setTitle(R.string.favorites_add_ids).setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> action {
                val ids = input.text.toString().trim().split(Regex("[,\\s]+")).map { it.toInt().also { id -> require(id > 0) } }.distinct()
                favorites.addAll(ids); selectedFolder?.let { library.addToFolder(it, ids) }; render()
            } }.show()
    }

    private fun chooseExisting() {
        val folder = selectedFolder ?: return message(getString(R.string.favorites_select_folder))
        val ids = favorites.toList().reversed()
        val checked = BooleanArray(ids.size) { ids[it] in library.folders.first { it.id == folder }.manualGalleryIDs }
        val labels = ids.map { id -> "#$id ${Cache.instances[id]?.metadata?.galleryBlock?.title.orEmpty()}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle(R.string.favorites_choose_existing)
            .setMultiChoiceItems(labels, checked) { _, index, value -> checked[index] = value }
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ -> action {
                library.setMembers(folder, ids.filterIndexed { index, _ -> checked[index] }); render()
            } }.show()
    }

    private fun itemActions(id: Int) {
        AlertDialog.Builder(this).setTitle("#$id")
            .setItems(arrayOf(getString(R.string.favorites_assign), getString(R.string.favorites_details), getString(R.string.favorites_remove))) { _, which ->
                when (which) {
                    0 -> {
                        val folders = library.folders.toList()
                        if (folders.isEmpty()) { message(getString(R.string.favorites_create_first)); return@setItems }
                        val checked = BooleanArray(folders.size) { id in folders[it].manualGalleryIDs }
                        AlertDialog.Builder(this).setTitle(R.string.favorites_assign)
                            .setMultiChoiceItems(folders.map(library::path).toTypedArray(), checked) { _, i, value -> checked[i] = value }
                            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ -> action {
                                library.assign(id, folders.filterIndexed { i, _ -> checked[i] }.map { it.id }.toSet()); render()
                            } }.show()
                    }
                    1 -> GalleryDialog(this, id).show()
                    2 -> AlertDialog.Builder(this).setMessage(R.string.favorites_remove_message)
                        .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.delete) { _, _ -> action {
                            favorites.remove(id); library.assign(id, emptySet()); render()
                        } }.show()
                }
            }.show()
    }
    private fun action(block: () -> Unit) { try { block() } catch (error: Exception) { showError(error) } }
    private fun showError(error: Throwable) { AlertDialog.Builder(this).setTitle(R.string.error).setMessage(error.localizedMessage).setPositiveButton(android.R.string.ok, null).show() }
    private fun message(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
}
