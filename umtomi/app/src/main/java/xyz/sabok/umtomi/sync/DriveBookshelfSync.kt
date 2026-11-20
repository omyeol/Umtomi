package xyz.sabok.umtomi.sync

import android.util.AtomicFile
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Blocking, chunked transfer. Run separately from the latency-sensitive tab worker. */
@androidx.annotation.RequiresApi(26)
internal object DriveBookshelfSync {
    data class Result(val snapshot: JSONObject, val paths: Map<String, String>)
    fun run(directory: File, local: JSONObject, sources: Map<String, String>, client: DriveAppDataClient, keys: DriveAccountKeys, progress: (String) -> Unit = {}): Result {
        directory.mkdirs()
        val stateFile = File(directory, "replica.json"); val cacheFile = File(directory, "images.json")
        val replica = BookshelfSyncReplica(read(stateFile)); val cache = read(cacheFile)
        val snapshot = JSONObject(local.toString())
        val images = snapshot.getJSONArray("images")
        for (i in 0 until images.length()) {
            interrupted()
            progress("책장 원본 확인·업로드 ${i + 1}/${images.length()}")
            val image = images.getJSONObject(i); val id = image.getString("id")
            val source = File(sources.getValue(id)); require(source.isFile) { "원본 파일에 접근할 수 없습니다." }
            val stamp = stamp(source); val old = cache.optJSONObject(id)
            if (old?.optString("stamp") == stamp) {
                image.put("manifest", JSONObject(old.getJSONObject("manifest").toString())); continue
            }
            val references = JSONArray()
            val manifest = source.inputStream().use { input -> BookshelfImageChunks.encode(input) { _, plain ->
                interrupted()
                val operation = UUID.randomUUID()
                val cloudId = client.appendShelfChunk(operation, keys.encrypt(plain))
                val name = DriveAppDataClient.SHELF_CHUNK_PREFIX + operation.toString().replace("-", "") + ".json"
                require(keys.decrypt(client.read(DriveAppDataClient.Entry(cloudId, name, null))) == plain)
                references.put(JSONObject().put("id", cloudId).put("name", name))
            } }
            check(stamp(source) == stamp) { "전송 중 원본 파일이 변경되었습니다." }
            val descriptor = JSONObject().put("version", manifest.version).put("length", manifest.length)
                .put("sha256", manifest.sha256).put("chunks", JSONArray(manifest.chunks)).put("files", references)
            image.put("manifest", descriptor)
            cache.put(id, JSONObject().put("stamp", stamp).put("manifest", descriptor)); save(cacheFile, cache)
        }
        replica.capture(snapshot); save(stateFile, replica.state)
        while (replica.pending.length() > 0) {
            interrupted()
            val batch = replica.pending.getJSONObject(0)
            val id = batch.getString("id")
            val uuid = UUID.fromString("${id.take(8)}-${id.substring(8,12)}-${id.substring(12,16)}-${id.substring(16,20)}-${id.substring(20)}")
            val cloudId = client.appendShelf(uuid, keys.encrypt(batch.toString()))
            replica.seenFiles.put(cloudId); replica.pending.remove(0); save(stateFile, replica.state)
        }
        progress("책장 폴더와 변경 내역 확인 중")
        val entries = client.listShelf(); require(entries.size <= 10000)
        val seen = (0 until replica.seenFiles.length()).map { replica.seenFiles.getString(it) }.toMutableSet()
        for (entry in entries) if (entry.id !in seen) {
            interrupted()
            val batch = JSONObject(keys.decrypt(client.read(entry)))
            require(entry.name == DriveAppDataClient.SHELF_PREFIX + batch.getString("id") + ".json")
            replica.apply(batch); replica.seenFiles.put(entry.id); seen.add(entry.id)
        }
        save(stateFile, replica.state)
        val projected = replica.project(); val paths = mutableMapOf<String, String>()
        val originals = File(directory, "originals").apply { mkdirs() }
        val remote = projected.getJSONArray("images")
        for (i in 0 until remote.length()) {
            interrupted()
            progress("책장 원본 확인·다운로드 ${i + 1}/${remote.length()}")
            val image = remote.getJSONObject(i); val id = image.getString("id"); val descriptor = image.getJSONObject("manifest")
            val source = sources[id]?.let(::File)
            if (source?.isFile == true && cache.optJSONObject(id)?.optJSONObject("manifest")?.let { BookshelfSyncReplica.same(it, descriptor) } == true) {
                paths[id] = source.absolutePath; continue
            }
            val chunks = descriptor.getJSONArray("chunks")
            val manifest = BookshelfImageChunks.Manifest(descriptor.getInt("version"), descriptor.getLong("length"),
                descriptor.getString("sha256"), (0 until chunks.length()).map { chunks.getString(it) })
            val files = descriptor.getJSONArray("files")
            require(manifest.sha256.matches(Regex("[0-9a-f]{64}")) && files.length() == chunks.length())
            val destination = File(originals, id + "-" + manifest.sha256 + ".png")
            val temporary = File(originals, "${manifest.sha256}.${UUID.randomUUID()}.partial")
            try {
                temporary.outputStream().use { output -> BookshelfImageChunks.decode(manifest, { index ->
                    interrupted()
                    val reference = files.getJSONObject(index); val name = reference.getString("name")
                    require(name.startsWith(DriveAppDataClient.SHELF_CHUNK_PREFIX) && DriveAppDataClient.validShelfName(name))
                    keys.decrypt(client.read(DriveAppDataClient.Entry(reference.getString("id"), name, null)))
                }, output) }
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
            paths[id] = destination.absolutePath
            cache.put(id, JSONObject().put("stamp", stamp(destination)).put("manifest", descriptor)); save(cacheFile, cache)
        }
        return Result(projected, paths)
    }
    fun acknowledge(directory: File, snapshot: JSONObject) {
        val file = File(directory, "replica.json"); val replica = BookshelfSyncReplica(read(file))
        replica.acknowledge(snapshot); save(file, replica.state)
    }
    private fun interrupted() { check(!Thread.currentThread().isInterrupted) }
    private fun stamp(file: File) = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
    private fun read(file: File): JSONObject = if (file.exists() || File(file.path + ".bak").exists())
        JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() }) else JSONObject()
    private fun save(file: File, json: JSONObject) {
        val atomic = AtomicFile(file); val output = atomic.startWrite()
        try { output.write(json.toString().toByteArray()); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
    }
}
