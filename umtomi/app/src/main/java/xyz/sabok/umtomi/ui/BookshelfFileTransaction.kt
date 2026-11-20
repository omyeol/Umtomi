package xyz.sabok.umtomi.ui

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Filesystem-only transaction layer. Caller must resolve/authorize a fixed storage volume first. */
@androidx.annotation.RequiresApi(26)
internal class BookshelfFileTransaction(private val root: File) {
    companion object { private val processGate = Any() }
    private val metadata get() = File(root, ".omyaru")
    private val catalog get() = File(metadata, "catalog.json")

    fun read(): JSONObject = locked { recover(); readCatalog() }

    fun migrateNamesIfNeeded(): JSONObject {
        val current = read()
        val items = current.optJSONArray("items") ?: return current
        val needsMigration = (0 until items.length()).any { index ->
            val row = items.getJSONObject(index)
            val source = File(row.getString("filePath")).canonicalFile
            val folder = directory(row.getString("folder"))
            source.parentFile != folder || numbered(source) == null
        }
        return if (needsMigration) commit(current, current, listOf(root)) else current
    }

    fun commit(base: JSONObject, desired: JSONObject, allowedSources: List<File>): JSONObject = locked {
        recover()
        val latest = readCatalog()
        val merged = SharedBookshelfCatalog.merge(base, desired, latest)
        val cleanup = JSONArray()
        val created = mutableListOf<File>()
        try {
            val folders = merged.getJSONArray("folders")
            for (index in 0 until folders.length()) directory(folders.getString(index)).also { check(it.mkdirs() || it.isDirectory) }
            val items = merged.getJSONArray("items")
            val nextNumbers = mutableMapOf<String, Int>()
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val id = item.getString("id")
                require(id.matches(Regex("[0-9a-f]{32}")))
                val source = File(item.getString("filePath")).canonicalFile
                require((allowedSources + root).any { inside(source, it) }) { "Original is outside the authorized shelf storage" }
                check(source.isFile) { "책장 원본에 접근할 수 없습니다. 저장 매체를 확인해 주세요." }
                val suffix = extension(source)
                val folder = item.getString("folder")
                val folderDirectory = directory(folder)
                val existingNumber = numbered(source)?.takeIf { source.parentFile == folderDirectory }
                val destination = if (existingNumber != null) source else {
                    val next = nextNumbers.getOrPut(folder) {
                        (folderDirectory.listFiles()?.mapNotNull(::numbered)?.maxOrNull() ?: 0) + 1
                    }
                    nextNumbers[folder] = Math.addExact(next, 1)
                    File(folderDirectory, "image$next.$suffix").canonicalFile
                }
                require(inside(destination, root))
                if (source != destination) {
                    val hash = digest(source)
                    if (destination.exists()) check(digest(destination) == hash) { "동일한 사진 ID의 원본이 다릅니다." }
                    else {
                        val temporary = File(destination.parentFile, ".${UUID.randomUUID()}.partial")
                        try {
                            source.inputStream().use { input -> temporary.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
                            check(digest(temporary) == hash)
                            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                            created += destination
                        } finally { temporary.delete() }
                    }
                    cleanup.put(JSONObject().put("path", source.path).put("sha256", hash))
                    item.put("filePath", destination.path)
                }
            }
            val retainedIds = (0 until items.length()).map { items.getJSONObject(it).getString("id") }.toSet()
            val previous = latest.optJSONArray("items") ?: JSONArray()
            for (i in 0 until previous.length()) {
                val old = previous.getJSONObject(i)
                val file = File(old.getString("filePath")).canonicalFile
                if (old.getString("id") !in retainedIds && inside(file, root) && file.isFile)
                    cleanup.put(JSONObject().put("path", file.path).put("sha256", digest(file)))
            }
            // Journal is durable before the catalog changes. Recovery always finishes the same commit.
            val journal = JSONObject().put("catalog", merged).put("cleanup", cleanup)
            writeAtomic(File(metadata, "pending.json"), journal)
            finish(journal)
            merged
        } catch (error: Throwable) {
            // Once journaled, recovery owns all files. Never remove its verified destinations.
            if (!File(metadata, "pending.json").exists()) created.forEach { it.delete() }
            throw error
        }
    }

    private fun directory(path: String): File {
        require(BookshelfPath.normalize(path) == path)
        require(path.substringBefore('/').lowercase() !in setOf(".omyaru", ".omyaru-control"))
        return File(root, path).canonicalFile.also { require(inside(it, root)) }
    }
    private fun readCatalog() = if (catalog.exists()) JSONObject(catalog.readText(Charsets.UTF_8)) else JSONObject()
    private fun recover() {
        val pending = File(metadata, "pending.json")
        if (pending.exists()) finish(JSONObject(pending.readText(Charsets.UTF_8)))
    }
    private fun finish(journal: JSONObject) {
        val next = journal.getJSONObject("catalog")
        val retained = mutableSetOf<String>()
        val items = next.getJSONArray("items")
        for (i in 0 until items.length()) {
            val file = File(items.getJSONObject(i).getString("filePath")).canonicalFile
            require(inside(file, root) && file.isFile)
            retained += file.path
        }
        writeAtomic(catalog, next)
        // Cleanup is intentionally limited to this shared root. App-private migration cleanup
        // needs a separate per-app receipt; one app must never delete another app's files.
        val cleanup = journal.getJSONArray("cleanup")
        for (i in 0 until cleanup.length()) {
            val entry = cleanup.getJSONObject(i)
            val old = File(entry.getString("path")).canonicalFile
            if (inside(old, root) && old.path !in retained && old.isFile && digest(old) == entry.getString("sha256")) old.delete()
            var parent = old.parentFile
            while (parent != null && inside(parent.canonicalFile, root) && parent != metadata && parent.list()?.isEmpty() == true) {
                if (!parent.delete()) break
                parent = parent.parentFile
            }
        }
        check(File(metadata, "pending.json").delete())
    }
    private fun <T> locked(action: () -> T): T = synchronized(processGate) {
        check(root.isDirectory) { "책장 저장 매체에 접근할 수 없습니다." }
        check(metadata.mkdirs() || metadata.isDirectory)
        require(inside(metadata.canonicalFile, root))
        RandomAccessFile(File(metadata, "write.lock"), "rw").use { file ->
            file.channel.use { channel -> channel.lock().use { action() } }
        }
    }
    private fun writeAtomic(file: File, value: JSONObject) {
        val temporary = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.outputStream().use { output -> output.write(value.toString().toByteArray(Charsets.UTF_8)); output.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    private fun inside(file: File, parent: File): Boolean = file.toPath().startsWith(parent.canonicalFile.toPath()) && file != parent.canonicalFile
    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun extension(file: File): String {
        val bytes = ByteArray(12)
        file.inputStream().use { it.read(bytes) }
        return when {
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png"
            bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() -> "jpg"
            String(bytes, 0, 3, Charsets.US_ASCII) == "GIF" -> "gif"
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
            else -> "img"
        }
    }
    private fun numbered(file: File): Int? =
        Regex("^image([1-9][0-9]*)\\.[A-Za-z0-9]{1,8}$", RegexOption.IGNORE_CASE)
            .matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull()
}
