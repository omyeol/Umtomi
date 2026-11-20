package xyz.sabok.umtomi.ui

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/** Fixed device-wide location. Preferences opt in; the location itself is shared by both apps. */
// Every disk entry point passes coordinated(), which rejects API < 30 and missing access.
@android.annotation.SuppressLint("NewApi")
internal class SharedBookshelfStorage(private val context: Context, private val testVolumes: Map<String, File>? = null) {
    companion object {
        const val DIRECTORY = "Omyaru_Bookshelf"
        private val gate = Any()
        fun hasAccess(): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()
    }
    private val preferences = context.getSharedPreferences("shared-bookshelf-storage", Context.MODE_PRIVATE)
    val enabled: Boolean get() = preferences.getBoolean("enabled", false) && (testVolumes != null || context.filesDir == context.applicationContext.filesDir)
    private val primary get() = testVolumes?.get("primary") ?: File(Environment.getExternalStorageDirectory(), DIRECTORY)
    private val control get() = File(primary, ".omyaru-control")
    private val location get() = File(control, "location.json")
    private val appName get() = if (context.packageName == "xyz.sabok.umtomi") "Umtomi" else "Omyaru Browser"

    fun volumes(): List<Pair<String, String>> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val manager = context.getSystemService(StorageManager::class.java)
        return listOf("primary" to "내부 저장소") + manager.storageVolumes.filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED && it.directory != null }
            .mapNotNull { volume -> volume.uuid?.let { it to volume.getDescription(context) } }
    }
    private fun resolve(volume: String): File {
        check(testVolumes != null || hasAccess()) { "공용 책장 파일 접근 권한이 필요합니다." }
        if (testVolumes != null) return checkNotNull(testVolumes[volume]?.takeIf { it.parentFile?.isDirectory == true }) { "선택한 저장 매체를 연결해 주세요." }
        if (volume == "primary") return primary
        check(Build.VERSION.SDK_INT >= 30)
        val selected = context.getSystemService(StorageManager::class.java).storageVolumes.firstOrNull { it.uuid == volume && it.isRemovable }
        check(selected != null && selected.state == Environment.MEDIA_MOUNTED && selected.directory != null) { "선택한 SD 카드를 연결해 주세요. 내부 저장소로 자동 전환하지 않습니다." }
        return File(selected.directory, DIRECTORY)
    }
    private fun root(): File {
        val selected = if (location.exists()) read(location).getString("volume") else "primary"
        return resolve(selected).also { check(it.isDirectory) { "공용 책장 폴더가 없습니다. 저장 매체를 확인해 주세요." } }
    }
    fun description(): String = if (!enabled) "공용 저장소 연결 필요" else coordinated { root().absolutePath }
    fun activate(privateCatalog: File) = coordinated {
        check(primary.mkdirs() || primary.isDirectory)
        val destination = if (location.exists()) root() else primary
        File(destination, ".nomedia").createNewFile()
        val disk = BookshelfFileTransaction(destination)
        if (!preferences.getBoolean("imported", false)) {
            val legacy = if (privateCatalog.exists()) read(privateCatalog) else JSONObject()
            val migrated = migrateNames(legacy)
            val result = disk.commit(JSONObject(), migrated, listOf(context.filesDir, context.noBackupFilesDir))
            // The receipt records the completed merge before this app starts using the shared catalog.
            check(preferences.edit().putBoolean("imported", true).putBoolean("enabled", true).commit())
            // Keep the legacy catalog as a migration receipt; old originals are removed only after byte verification.
            cleanupPrivateCopies(legacy, result)
        } else check(preferences.edit().putBoolean("enabled", true).commit())
    }
    fun readCatalog(): JSONObject = coordinated { BookshelfFileTransaction(root()).migrateNamesIfNeeded() }
    fun commit(base: JSONObject, desired: JSONObject): JSONObject = coordinated {
        BookshelfFileTransaction(root()).commit(base, desired, listOf(context.filesDir, context.noBackupFilesDir)).also {
            cleanupPrivateCopies(desired, it)
        }
    }
    fun moveTo(volume: String) = coordinated {
        val sourceVolume = if (location.exists()) read(location).getString("volume") else "primary"
        val source = root()
        val destination = resolve(volume)
        if (source.canonicalFile == destination.canonicalFile) return@coordinated
        check(destination.mkdirs() || destination.isDirectory)
        val target = BookshelfFileTransaction(destination)
        val identityFile = File(control, "library.json")
        val identity = if (identityFile.exists()) read(identityFile) else JSONObject().put("id", UUID.randomUUID().toString()).also { write(identityFile, it) }
        val destinationIdentity = File(destination, ".omyaru/library.json")
        val existing = target.read()
        check(existing.optJSONArray("folders")?.length().let { it == null || it == 0 } ||
            (destinationIdentity.exists() && read(destinationIdentity).getString("id") == identity.getString("id"))) {
            "대상 저장 매체에 다른 책장이 있습니다. 기존 책장을 보호하기 위해 전환하지 않았습니다."
        }
        write(destinationIdentity, identity)
        write(File(source, ".omyaru/library.json"), identity)
        File(destination, ".nomedia").createNewFile()
        val before = BookshelfFileTransaction(source).read()
        val copied = target.commit(existing, before, listOf(source))
        val hashes = JSONObject()
        val images = copied.getJSONArray("items")
        for (i in 0 until images.length()) images.getJSONObject(i).let { row ->
            hashes.put(row.getString("id"), hash(File(row.getString("filePath"))).joinToString("") { "%02x".format(it.toInt() and 255) })
        }
        val journal = JSONObject().put("from", sourceVolume).put("to", volume)
            .put("before", before).put("after", copied).put("hashes", hashes)
        write(File(control, "moving.json"), journal)
        finishVolumeMove(journal)
    }
    private fun finishVolumeMove(journal: JSONObject) {
        val source = resolve(journal.getString("from"))
        val target = resolve(journal.getString("to"))
        check(source.canonicalFile != target.canonicalFile)
        val after = journal.getJSONObject("after")
        val images = after.getJSONArray("items")
        for (i in 0 until images.length()) images.getJSONObject(i).let { row ->
            val file = File(row.getString("filePath")).canonicalFile
            check(file.toPath().startsWith(target.canonicalFile.toPath()) && file.isFile)
            check(hash(file).joinToString("") { "%02x".format(it.toInt() and 255) } == journal.getJSONObject("hashes").getString(row.getString("id")))
        }
        val before = journal.getJSONObject("before")
        write(File(control, "previous-location.json"), JSONObject().put("path", source.path).put("catalog", before))
        write(location, JSONObject().put("volume", journal.getString("to")))
        cleanupMovedCopies(before, after, source)
        File(source, ".omyaru/catalog.json").delete()
        check(File(control, "moving.json").delete())
    }
    private fun migrateNames(legacy: JSONObject): JSONObject {
        val result = JSONObject(legacy.toString())
        val folders = legacy.optJSONArray("folders") ?: JSONArray().put("기본 책장")
        val ids = JSONObject(); val notes = JSONObject(); val columns = JSONObject(); val names = mutableMapOf<String, String>()
        for (i in 0 until folders.length()) {
            val old = folders.getString(i)
            val safe = old.split('/').joinToString("/") { part -> part.map { if (it.code < 32 || it in "\\:*?\"<>|") '_' else it }.joinToString("").trim().trimEnd('.').take(90).ifEmpty { "책장" } }
            var name = if (safe.startsWith("Umtomi/") || safe.startsWith("Omyaru Browser/")) safe else "$appName/$safe"
            if (BookshelfPath.normalize(name) == null) name = "$appName/이전 책장 ${i + 1}"
            val id = legacy.optJSONObject("folderIds")?.optString(old).orEmpty().replace("-", "").lowercase()
                .takeIf { it.matches(Regex("[0-9a-f]{32}")) } ?: UUID.randomUUID().toString().replace("-", "")
            if (names.values.any { it.equals(name, true) }) name += " (${id.take(8)})"
            names[old] = name; ids.put(name, id); notes.put(name, legacy.optJSONObject("folderNotes")?.optString(old).orEmpty())
            columns.put(name, legacy.optJSONObject("folderColumnCounts")?.optInt(old, 2) ?: 2)
        }
        val images = result.optJSONArray("items") ?: JSONArray()
        for (i in 0 until images.length()) {
            val row = images.getJSONObject(i)
            row.put("folder", names.getValue(row.getString("folder")))
            row.put("id", row.getString("id").replace("-", "").lowercase())
        }
        return result.put("folders", JSONArray(names.values.toList())).put("folderIds", ids).put("folderNotes", notes)
            .put("folderColumnCounts", columns).put("items", images).put("activeFolder", names[legacy.optString("activeFolder")] ?: names.values.first())
    }
    private fun cleanupPrivateCopies(before: JSONObject, after: JSONObject) {
        cleanupMovedCopies(before, after, context.filesDir)
        cleanupMovedCopies(before, after, context.noBackupFilesDir)
    }
    private fun cleanupMovedCopies(before: JSONObject, after: JSONObject, allowed: File) {
        val target = after.getJSONArray("items")
        val paths = (0 until target.length()).associate { target.getJSONObject(it).let { row -> row.getString("id") to File(row.getString("filePath")) } }
        val old = before.optJSONArray("items") ?: return
        for (i in 0 until old.length()) {
            val row = old.getJSONObject(i); val source = File(row.getString("filePath")).canonicalFile
            val destination = paths[row.getString("id").replace("-", "").lowercase()] ?: continue
            if (source != destination.canonicalFile && source.toPath().startsWith(allowed.canonicalFile.toPath()) && source.isFile && destination.isFile &&
                source.length() == destination.length() && hash(source).contentEquals(hash(destination))) source.delete()
        }
    }
    private fun hash(file: File): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest()
    }
    private fun <T> coordinated(action: () -> T): T = synchronized(gate) {
        check(testVolumes != null || hasAccess()) { "공용 책장 파일 접근 권한이 필요합니다." }
        check(control.mkdirs() || control.isDirectory)
        RandomAccessFile(File(control, "location.lock"), "rw").use { it.channel.use { channel -> channel.lock().use {
            val pending = File(control, "moving.json")
            if (pending.exists()) finishVolumeMove(read(pending))
            action()
        } } }
    }
    private fun read(file: File) = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
    private fun write(file: File, value: JSONObject) {
        val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
}
