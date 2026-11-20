package xyz.sabok.umtomi.ui

import xyz.sabok.umtomi.R

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class BookshelfItem(
    val id: String,
    val folder: String,
    val filePath: String,
    val sourceUrl: String,
    val width: Int,
    val height: Int,
    val createdAt: Long,
)

class BookshelfStore(private val context: Context) {
    private val sharedStorage = SharedBookshelfStorage(context)
    private var sharedBaseline = JSONObject()
    private val root = File(context.filesDir, "bookshelf")
    private val catalogFile = File(root, "catalog.json")
    var loadError: String? = null
        private set

    var activeFolder: String = DEFAULT_FOLDER
        private set
    val folders = mutableListOf(DEFAULT_FOLDER)
    private val folderIds = mutableMapOf<String, String>()
    fun folderId(folder: String = activeFolder): String = folderIds.getOrPut(folder) { UUID.randomUUID().toString().replace("-", "") }
    private val folderNotes = mutableMapOf<String, String>()
    private val folderColumnCounts = mutableMapOf(DEFAULT_FOLDER to DEFAULT_COLUMN_COUNT)
    val items = mutableListOf<BookshelfItem>()

    init {
        load()
    }

    fun selectFolder(folder: String) {
        if (folder in folders) {
            activeFolder = folder
            save()
        }
    }

    fun moveFolder(folder: String, direction: Int): Boolean {
        val reordered = BookshelfPath.moveSibling(folders, folder, direction) ?: return false
        folders.clear(); folders.addAll(reordered)
        save()
        return true
    }

    fun visibleItems(folder: String = activeFolder): List<BookshelfItem> {
        val result = mutableListOf<BookshelfItem>()
        fun visit(path: String) {
            result += items.filter { it.folder.equals(path, true) }.sortedWith(compareBy<BookshelfItem> { it.createdAt }.thenBy { it.id })
            BookshelfPath.children(folders, path).forEach(::visit)
        }
        visit(folder)
        return result
    }

    fun addFolder(name: String): String {
        val normalized = BookshelfPath.normalize(name.trim().ifEmpty { DEFAULT_FOLDER }) ?: return activeFolder
        val existing = folders.firstOrNull { it.equals(normalized, ignoreCase = true) }
        activeFolder = existing ?: normalized.also {
            folders += it
            folderColumnCounts[it] = DEFAULT_COLUMN_COUNT
        }
        save()
        return activeFolder
    }

    fun renameFolder(folder: String, requestedName: String): Boolean {
        val moves = BookshelfPath.relocation(folders, folder, requestedName) ?: return false
        val ids = moves.keys.associateWith { folderId(it) }
        val memos = moves.keys.associateWith { note(it) }
        val columns = moves.keys.associateWith { columnCount(it) }
        moves.forEach { (old, new) ->
            folders[folders.indexOf(old)] = new
            folderIds.remove(old); folderIds[new] = ids.getValue(old)
            folderNotes.remove(old); folderNotes[new] = memos.getValue(old)
            folderColumnCounts.remove(old); folderColumnCounts[new] = columns.getValue(old)
        }
        items.indices.forEach { i -> moves[items[i].folder]?.let { items[i] = items[i].copy(folder = it) } }
        moves[activeFolder]?.let { activeFolder = it }
        save()
        return true
    }

    fun deleteFolder(folder: String): Boolean {
        if (folders.any { it.startsWith("$folder/", true) }) return false
        if (folder !in folders) return false
        val removed = items.filter { it.folder.equals(folder, ignoreCase = true) }
        items.removeAll { it.folder.equals(folder, ignoreCase = true) }
        folderNotes.remove(folder)
        folders.remove(folder)
        folderIds.remove(folder)
        folderColumnCounts.remove(folder)
        if (folders.isEmpty()) {
            folders += DEFAULT_FOLDER
            folderColumnCounts[DEFAULT_FOLDER] = DEFAULT_COLUMN_COUNT
        }
        if (activeFolder == folder) activeFolder = folders.first()
        save()
        removed.forEach { item -> runCatching { File(item.filePath).takeIf(File::exists)?.delete() } }
        return true
    }

    fun columnCount(folder: String = activeFolder): Int =
        (folderColumnCounts[folder] ?: DEFAULT_COLUMN_COUNT).coerceIn(MIN_COLUMN_COUNT, MAX_COLUMN_COUNT)

    fun setColumnCount(count: Int, folder: String = activeFolder) {
        if (folder !in folders) return
        folderColumnCounts[folder] = count.coerceIn(MIN_COLUMN_COUNT, MAX_COLUMN_COUNT)
        save()
    }

    fun note(folder: String = activeFolder): String = folderNotes[folder].orEmpty()

    fun setNote(note: String) { folderNotes[activeFolder] = note; save() }

    /** Copy the original bytes off the UI thread; do not reduce archival image quality. */
    fun prepareImport(uri: Uri, folder: String): BookshelfItem {
        val directory = File(root, "images").apply { check(mkdirs() || isDirectory) }
        val id = UUID.randomUUID().toString().replace("-", "")
        val file = File(directory, id)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { input.copyTo(it) }
            } ?: error("Cannot read image")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image" }
            val orientation = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
            val swapped = orientation in 5..8
            return BookshelfItem(id, folder, file.path, uri.toString(),
                if (swapped) bounds.outHeight else bounds.outWidth,
                if (swapped) bounds.outWidth else bounds.outHeight, System.currentTimeMillis())
        } catch (error: Exception) { file.delete(); throw error }
    }

    fun commitImport(item: BookshelfItem) {
        if (item.folder !in folders) { File(item.filePath).delete(); return }
        items.add(item)
        save()
    }

    fun addImage(bitmap: Bitmap, sourceUrl: String, folder: String = activeFolder): BookshelfItem {
        require(folder in folders)
        val folderDirectory = File(root, safeSegment(folder)).apply { mkdirs() }
        val id = UUID.randomUUID().toString().replace("-", "")
        val file = File(folderDirectory, "${System.currentTimeMillis()}-${id.take(6)}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val item = BookshelfItem(
            id = id,
            folder = folder,
            filePath = file.absolutePath,
            sourceUrl = sourceUrl,
            width = bitmap.width,
            height = bitmap.height,
            createdAt = System.currentTimeMillis(),
        )
        items.add(item)
        save()
        return items.first { it.id == id }
    }

    fun removeItem(item: BookshelfItem): Boolean {
        val removed = items.removeAll { it.id == item.id }
        if (!removed) return false
        save()
        runCatching { File(item.filePath).takeIf(File::exists)?.delete() }
        return true
    }

    fun save() {
        check(loadError == null) { "책장 원본 또는 저장 매체를 확인해 주세요." }
        check(loadError == null) { "The existing bookshelf catalog could not be read; it has been preserved." }
        root.mkdirs()
        val json = JSONObject().apply {
            put("folderIds", JSONObject().apply { folders.forEach { put(it, folderId(it)) } })
            put("folderNotes", JSONObject(folderNotes as Map<*, *>))
            put("activeFolder", activeFolder)
            put("folders", JSONArray(folders))
            put("folderColumnCounts", JSONObject().apply {
                folders.forEach { folder -> put(folder, columnCount(folder)) }
            })
            put("items", JSONArray().apply {
                items.forEach { item ->
                    put(JSONObject().apply {
                        put("id", item.id)
                        put("folder", item.folder)
                        put("filePath", item.filePath)
                        put("sourceUrl", item.sourceUrl)
                        put("width", item.width)
                        put("height", item.height)
                        put("createdAt", item.createdAt)
                    })
                }
            })
        }
        if (sharedStorage.enabled) {
            sharedStorage.commit(sharedBaseline, json)
            load()
            return
        }
        val atomic = android.util.AtomicFile(catalogFile)
        val output = atomic.startWrite()
        try { output.write(json.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
        migratePrivateImageNames()
    }

    private fun migratePrivateImageNames() {
        if (items.isEmpty() || !BookshelfImageNames.needsMigration(root, items)) return
        if (items.any { !File(it.filePath).isFile }) return
        val plan = BookshelfImageNames.prepare(root, items)
        var committed = false
        try {
            val json = JSONObject(android.util.AtomicFile(catalogFile).openRead().bufferedReader().use { it.readText() })
            val rows = json.getJSONArray("items")
            check(rows.length() == items.size && (0 until rows.length()).all {
                rows.getJSONObject(it).getString("id") == items[it].id
            }) { "책장 목록이 변경되어 이름 바꾸기를 보류했습니다." }
            for (index in 0 until rows.length())
                rows.getJSONObject(index).put("filePath", plan.items[index].filePath)
            val atomic = android.util.AtomicFile(catalogFile)
            val output = atomic.startWrite()
            try {
                output.write(json.toString().toByteArray(Charsets.UTF_8))
                atomic.finishWrite(output)
                committed = true
            } catch (error: Throwable) {
                atomic.failWrite(output)
                throw error
            }
            items.clear()
            items.addAll(plan.items)
            runCatching { plan.committed(root) }
        } catch (error: Throwable) {
            if (!committed) plan.rollback()
            throw error
        }
    }

    private fun load() {
        if (!sharedStorage.enabled && !catalogFile.exists()) return
        runCatching {
            val json = if (sharedStorage.enabled) sharedStorage.readCatalog() else JSONObject(android.util.AtomicFile(catalogFile).openRead().bufferedReader().use { it.readText() })
            sharedBaseline = JSONObject(json.toString())
            loadError = null
            folders.clear()
            val storedFolders = json.optJSONArray("folders") ?: JSONArray()
            for (index in 0 until storedFolders.length()) {
                storedFolders.optString(index).takeIf { it.isNotBlank() }?.let(folders::add)
            }
            if (folders.isEmpty()) folders += DEFAULT_FOLDER
            activeFolder = json.optString("activeFolder").takeIf { it in folders } ?: folders.first()
            folderIds.clear()
            folders.forEach { folder -> json.optJSONObject("folderIds")?.optString(folder)?.takeIf { it.matches(Regex("[0-9a-f]{32}")) }?.let { folderIds[folder] = it } }
            folderNotes.clear()
            val notes = json.optJSONObject("folderNotes")
            folders.forEach { folderNotes[it] = notes?.optString(it).orEmpty() }
            folderColumnCounts.clear()
            val storedColumnCounts = json.optJSONObject("folderColumnCounts")
            folders.forEach { folder ->
                folderColumnCounts[folder] = storedColumnCounts
                    ?.optInt(folder, DEFAULT_COLUMN_COUNT)
                    ?.coerceIn(MIN_COLUMN_COUNT, MAX_COLUMN_COUNT)
                    ?: DEFAULT_COLUMN_COUNT
            }
            items.clear()
            val storedItems = json.optJSONArray("items") ?: JSONArray()
            for (index in 0 until storedItems.length()) {
                val item = storedItems.optJSONObject(index) ?: continue
                val filePath = item.optString("filePath")
                items += BookshelfItem(
                    id = item.optString("id"),
                    folder = item.optString("folder", DEFAULT_FOLDER),
                    filePath = filePath,
                    sourceUrl = item.optString("sourceUrl"),
                    width = item.optInt("width", 1).coerceAtLeast(1),
                    height = item.optInt("height", 1).coerceAtLeast(1),
                    createdAt = item.optLong("createdAt"),
                )
            }
            if (!sharedStorage.enabled && items.all { File(it.filePath).isFile })
                runCatching { migratePrivateImageNames() }
        }.onFailure {
            loadError = it.localizedMessage ?: "Unable to read bookshelf catalog"
            folders.clear()
            folders += DEFAULT_FOLDER
            activeFolder = DEFAULT_FOLDER
            folderColumnCounts.clear()
            folderColumnCounts[DEFAULT_FOLDER] = DEFAULT_COLUMN_COUNT
            items.clear()
        }
    }

    internal fun prepareSync() {
        // A load failure must not turn into synchronized deletion of every shelf.
        check(loadError == null)
        if (sharedStorage.enabled) {
            check(items.all { File(it.filePath).isFile }) { "책장 원본 또는 저장 매체를 확인해 주세요." }
            save()
            return
        }
        if (catalogFile.exists()) {
            val disk = JSONObject(catalogFile.readText())
            require(disk.getJSONArray("folders").length() > 0 && disk.has("items"))
            val originals = disk.getJSONArray("items")
            for (i in 0 until originals.length()) check(File(originals.getJSONObject(i).getString("filePath")).isFile) { "책장 원본 파일이 없습니다. 동기화를 중단했습니다." }
        }
        check(loadError == null)
        // Older file imports used hyphenated UUIDs; the shared shelf protocol
        // uses compact UUIDs. Keep original image paths and back up the catalog.
        val normalized = items.map { item ->
            val id = item.id.replace("-", "").lowercase(java.util.Locale.ROOT)
            require(id.matches(Regex("[0-9a-f]{32}")) && id != "0".repeat(32)) { "책장 이미지 ID를 확인해 주세요." }
            item.copy(id = id)
        }
        require(normalized.map { it.id }.distinct().size == normalized.size) { "책장 이미지 ID가 중복됩니다." }
        if (normalized != items) {
            if (catalogFile.exists()) catalogFile.copyTo(File(root, "catalog.json.before-id-migration-${System.currentTimeMillis()}"))
            items.clear()
            items.addAll(normalized)
        }
        save()
    }

    internal fun syncSnapshot(): JSONObject = JSONObject().put("folders", JSONArray().apply {
        folders.forEachIndexed { index, folder -> put(JSONObject().put("id", folderId(folder)).put("name", folder)
            .put("note", note(folder)).put("columns", columnCount(folder)).put("order", index)) }
    }).put("images", JSONArray().apply {
        items.forEachIndexed { index, item -> put(JSONObject().put("id", item.id).put("folderId", folderId(item.folder))
            .put("manifest", JSONObject()).put("sourceUrl", item.sourceUrl).put("width", item.width).put("height", item.height)
            .put("createdAt", item.createdAt).put("order", index)) }
    })

    internal fun applySynced(snapshot: JSONObject, paths: Map<String, String>) {
        val activeId = folderId()
        val nextFolders = mutableListOf<String>(); val nextIds = mutableMapOf<String, String>()
        val nextNotes = mutableMapOf<String, String>(); val nextColumns = mutableMapOf<String, Int>()
        val names = mutableMapOf<String, String>(); val nextItems = mutableListOf<BookshelfItem>()
        val remoteFolders = snapshot.getJSONArray("folders")
        for (entry in (0 until remoteFolders.length()).map { remoteFolders.getJSONObject(it) }
            .sortedWith(compareBy<JSONObject> { it.optInt("order", Int.MAX_VALUE) }.thenBy { it.getString("id") })) {
            val id = entry.getString("id")
            var name = entry.getString("name")
            if (nextFolders.any { it.equals(name, true) }) name = name.take(501) + " (${id.take(8)})"
            nextFolders += name; nextIds[name] = id; names[id] = name
            nextNotes[name] = entry.getString("note"); nextColumns[name] = entry.getInt("columns")
        }
        val images = snapshot.getJSONArray("images")
        (0 until images.length()).map { images.getJSONObject(it) }
            .sortedWith(compareBy<JSONObject> { it.getInt("order") }.thenBy { it.getString("id") }).forEach { entry ->
                val folder = names[entry.getString("folderId")] ?: return@forEach
                val id = entry.getString("id")
                nextItems += BookshelfItem(id, folder, paths.getValue(id), entry.getString("sourceUrl"), entry.getInt("width"), entry.getInt("height"), entry.getLong("createdAt"))
            }
        if (nextFolders.isEmpty()) { nextFolders += DEFAULT_FOLDER; nextIds[DEFAULT_FOLDER] = UUID.randomUUID().toString().replace("-", ""); nextNotes[DEFAULT_FOLDER] = ""; nextColumns[DEFAULT_FOLDER] = 2 }
        if (catalogFile.exists()) catalogFile.copyTo(File(root, "catalog.json.before-sync"), overwrite = true)
        val previousFolders = folders.toList()
        val previousIds = folderIds.toMap()
        val previousNotes = folderNotes.toMap()
        val previousColumns = folderColumnCounts.toMap()
        val previousItems = items.toList()
        val previousActive = activeFolder
        folders.clear(); folders += nextFolders
        folderIds.clear(); folderIds.putAll(nextIds)
        folderNotes.clear(); folderNotes.putAll(nextNotes)
        folderColumnCounts.clear(); folderColumnCounts.putAll(nextColumns)
        items.clear(); items += nextItems
        activeFolder = names[activeId] ?: folders.first()
        try { save() } catch (error: Throwable) {
            folders.clear(); folders += previousFolders
            folderIds.clear(); folderIds.putAll(previousIds)
            folderNotes.clear(); folderNotes.putAll(previousNotes)
            folderColumnCounts.clear(); folderColumnCounts.putAll(previousColumns)
            items.clear(); items += previousItems
            activeFolder = previousActive
            throw error
        }
    }

    private fun safeSegment(value: String): String = value
        .map { character -> if (character in INVALID_FILE_CHARS) '_' else character }
        .joinToString("")
        .trim()
        .ifEmpty { "shelf" }

    companion object {
        const val DEFAULT_FOLDER = "기본 책장"
        const val MIN_COLUMN_COUNT = 1
        const val MAX_COLUMN_COUNT = 6
        const val DEFAULT_COLUMN_COUNT = 2
        private const val INVALID_FILE_CHARS = "\\/:*?\"<>|"
    }
}
