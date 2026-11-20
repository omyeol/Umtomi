package xyz.sabok.umtomi.sync

import android.content.Context
import android.util.AtomicFile
import androidx.annotation.RequiresApi
import org.json.JSONArray
import org.json.JSONObject
import xyz.sabok.umtomi.histories
import xyz.sabok.umtomi.ui.BookshelfStore
import xyz.sabok.umtomi.util.FavoritesFiles
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RequiresApi(26)
internal object UmtomiDriveSync {
    private val gate = java.util.concurrent.locks.ReentrantLock()
    internal fun <T> exclusive(block: () -> T): T {
        gate.lockInterruptibly()
        try { return block() } finally { gate.unlock() }
    }
    fun snapshot(context: Context): JSONObject {
        val backup = JSONObject(FavoritesFiles.export(context))
        return JSONObject().apply {
            val favorites = backup.getJSONArray("favorites")
            for (i in 0 until favorites.length()) put("favorite/${favorites.getInt(i)}", true)
            synchronized(histories) { histories.forEachIndexed { i, id -> put("history/$id", i) } }
            fun records(key: String, array: JSONArray, identity: (JSONObject) -> String) {
                for (i in 0 until array.length()) { val item = array.getJSONObject(i); put("$key/${identity(item)}", item) }
            }
            records("folder", backup.getJSONObject("favoriteLibrary").getJSONArray("folders")) { it.getString("id") }
            records("tag", backup.getJSONArray("favorite_tags")) { digest(it.toString()) }
            records("artist", backup.getJSONArray("favoriteArtists")) { digest(it.getString("name")) }
        }
    }
    fun apply(context: Context, snapshot: JSONObject) {
        val favorites = JSONArray(); val folders = JSONArray(); val tags = JSONArray(); val artists = JSONArray()
        val history = mutableListOf<Pair<Int, Int>>()
        snapshot.keys().forEach { key ->
            when (key.substringBefore('/')) {
                "favorite" -> favorites.put(key.substringAfter('/').toInt().also { require(it > 0) })
                "folder" -> folders.put(snapshot.getJSONObject(key))
                "tag" -> tags.put(snapshot.getJSONObject(key))
                "artist" -> artists.put(snapshot.getJSONObject(key))
                "history" -> history.add(key.substringAfter('/').toInt().also { require(it > 0) } to snapshot.getInt(key))
            }
        }
        val favoriteIds = (0 until favorites.length()).map { favorites.getInt(it) }.toSet()
        val byId = (0 until folders.length()).map { folders.getJSONObject(it) }.associateBy { it.getString("id") }
        byId.values.forEach { folder ->
            val members = folder.optJSONArray("manualGalleryIDs") ?: JSONArray()
            folder.put("manualGalleryIDs", JSONArray((0 until members.length()).map { members.getInt(it) }.filter { it in favoriteIds }))
            val visited = mutableSetOf(folder.getString("id"))
            var parent = folder.optString("parentID")
            while (parent.isNotBlank() && parent != "null") {
                if (!visited.add(parent) || parent !in byId) { folder.put("parentID", JSONObject.NULL); break }
                parent = byId.getValue(parent).optString("parentID")
            }
        }
        val backup = JSONObject().put("favorites", favorites).put("favorite_tags", tags)
            .put("favoriteLibrary", JSONObject().put("folders", folders)).put("favoriteArtists", artists).toString()
        FavoritesFiles.parse(backup)
        FavoritesFiles.replace(context, backup)
        histories.replaceWith(history.sortedBy { it.second }.map { it.first })
    }
    fun run(context: Context, account: String, token: () -> String, includeLegacyShelf: Boolean = true, progress: (String) -> Unit) = exclusive {
        val sharedJoined = context.getSharedPreferences("shared-bookshelf", Context.MODE_PRIVATE).getBoolean("joined", false)
        val space = if (sharedJoined || !includeLegacyShelf) "umtomi-drive-omyaru-project" else "umtomi-drive"
        val directory = File(context.noBackupFilesDir, "$space/${digest(account.lowercase())}").apply { mkdirs() }
        val client = DriveAppDataClient(token).apply { accountMode = true }
        progress("암호화 키 확인 중")
        val keys = DriveAccountKeys.ensureKeys(client)
        if (includeLegacyShelf && !sharedJoined && !xyz.sabok.umtomi.ui.SharedBookshelfStorage(context).enabled) {
        val store = onMain { BookshelfStore(context).also { it.prepareSync() } }
        val localShelf = store.syncSnapshot()
        progress("책장 원본 이미지와 폴더 동기화 중")
        val shelfDir = File(directory, "shelf")
        val result = DriveBookshelfSync.run(shelfDir, localShelf, store.items.associate { it.id to it.filePath }, client, keys, progress)
        onMain {
            check(BookshelfSyncReplica.same(localShelf, BookshelfStore(context).syncSnapshot())) { "책장이 변경되었습니다. 다시 동기화해 주세요." }
            store.applySynced(result.snapshot, result.paths)
        }
        DriveBookshelfSync.acknowledge(shelfDir, result.snapshot)
        }
        progress("즐겨찾기와 열람 기록 동기화 중")
        val stateFile = File(directory, "userdata.json")
        val replica = UserDataReplica(read(stateFile))
        val local = onMain { snapshot(context) }
        save(File(directory, "userdata-before-sync.json"), local)
        replica.capture(local); save(stateFile, replica.state)
        while (replica.pending.length() > 0) {
            val batch = replica.pending.getJSONObject(0)
            val id = client.appendUserData(UUID.fromString(batch.getString("id")), keys.encrypt(batch.toString()))
            replica.state.getJSONArray("seen").put(id); replica.pending.remove(0); save(stateFile, replica.state)
        }
        val seen = replica.state.getJSONArray("seen")
        val known = (0 until seen.length()).map { seen.getString(it) }.toSet()
        val entries = client.listUserData(); require(entries.size <= 10000)
        for (entry in entries) if (entry.id !in known) {
            val batch = JSONObject(keys.decrypt(client.read(entry)))
            require(entry.name == DriveAppDataClient.USERDATA_PREFIX + batch.getString("id").replace("-", "") + ".json")
            replica.apply(batch); seen.put(entry.id)
        }
        save(stateFile, replica.state)
        onMain {
            check(BookshelfSyncReplica.same(local, snapshot(context))) { "기록이 변경되었습니다. 다시 동기화해 주세요." }
            apply(context, replica.project())
            replica.acknowledge(snapshot(context))
        }
        save(stateFile, replica.state)
    }
    private fun <T> onMain(block: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        val task = java.util.concurrent.FutureTask<T> { block() }
        android.os.Handler(android.os.Looper.getMainLooper()).post(task)
        try { return task.get() }
        catch (error: InterruptedException) { task.cancel(false); throw error }
    }
    private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun read(file: File) = if (file.exists() || File(file.path + ".bak").exists()) JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() }) else JSONObject()
    private fun save(file: File, value: JSONObject) {
        val atomic = AtomicFile(file); val output = atomic.startWrite()
        try { output.write(value.toString().toByteArray()); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
    }
}
