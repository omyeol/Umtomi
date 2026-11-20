package xyz.sabok.umtomi.sync.shared

import android.util.AtomicFile
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import xyz.sabok.umtomi.ui.CloudShelfMarker

/** Blocking, chunked transfer. Run separately from the latency-sensitive tab worker. */
@androidx.annotation.RequiresApi(26)
internal object DriveBookshelfSync {
    data class Result(val snapshot: JSONObject, val paths: Map<String, String>)
    fun run(directory: File, local: JSONObject, sources: Map<String, String>, client: DriveAppDataClient, keys: DriveAccountKeys,
            progress: (String) -> Unit = {}, cloudOnly: Boolean = false,
            metadataReady: (Result) -> Unit = {}): Result {
        directory.mkdirs()
        val stateFile = File(directory, "replica.json"); val cacheFile = File(directory, "images.json")
        val replica = BookshelfSyncReplica(read(stateFile)); val cache = read(cacheFile)
        val snapshot = JSONObject(local.toString())
        val images = snapshot.getJSONArray("images")
        fun uploaded(count: Int) = progress("업로드 ${if (images.length() == 0) 100 else count * 100 / images.length()}% ($count/${images.length()})")
        uploaded(0)
        for (i in 0 until images.length()) {
            interrupted()
            val image = images.getJSONObject(i); val id = image.getString("id")
            val source = File(sources.getValue(id)); require(source.isFile) { "원본 파일에 접근할 수 없습니다." }
            val cloudMarker = CloudShelfMarker.read(source)
            if (cloudMarker != null) {
                require(cloudMarker.getString("id") == id)
                image.put("manifest", JSONObject(cloudMarker.getJSONObject("manifest").toString()))
                uploaded(i + 1)
                continue
            }
            val stamp = stamp(source); val old = cache.optJSONObject(id)
            val cachedManifest = old?.optJSONObject("manifest")
            val sameOriginal = old?.optString("stamp") != stamp && cachedManifest != null && cachedManifest.optLong("length", -1) == source.length() &&
                cachedManifest.optString("sha256") == fileHash(source)
            if (old?.optString("stamp") == stamp || sameOriginal) {
                image.put("manifest", JSONObject(old!!.getJSONObject("manifest").toString()))
                if (old.optString("stamp") != stamp) { old.put("stamp", stamp); save(cacheFile, cache) }
                uploaded(i + 1)
                continue
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
            uploaded(i + 1)
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
        // Make new folders selectable while originals are still downloading.
        val previewPaths = mutableMapOf<String, String>()
        for (i in 0 until remote.length()) {
            interrupted()
            val image = remote.getJSONObject(i); val id = image.getString("id")
            val descriptor = image.getJSONObject("manifest")
            val source = sources[id]?.let(::File)
            val marker = source?.takeIf { it.isFile }?.let(CloudShelfMarker::read)
            if (source?.isFile == true && (marker == null || BookshelfSyncReplica.same(marker.getJSONObject("manifest"), descriptor))) {
                previewPaths[id] = source.absolutePath
            } else {
                val destination = File(originals, "$id-${descriptor.getString("sha256")}.cloud")
                CloudShelfMarker.write(destination, id, descriptor)
                previewPaths[id] = destination.absolutePath
            }
        }
        metadataReady(Result(projected, previewPaths))
        fun downloaded(count: Int) = progress("다운로드 ${if (remote.length() == 0) 100 else count * 100 / remote.length()}% ($count/${remote.length()})")
        downloaded(0)
        for (i in 0 until remote.length()) {
            interrupted()
            try {
            val image = remote.getJSONObject(i); val id = image.getString("id"); val descriptor = image.getJSONObject("manifest")
            val source = sources[id]?.let(::File)
            if (cloudOnly && source?.isFile == true && CloudShelfMarker.read(source)?.getJSONObject("manifest")?.let { BookshelfSyncReplica.same(it, descriptor) } == true) {
                paths[id] = source.absolutePath; continue
            }
            val chunks = descriptor.getJSONArray("chunks")
            val manifest = BookshelfImageChunks.Manifest(descriptor.getInt("version"), descriptor.getLong("length"),
                descriptor.getString("sha256"), (0 until chunks.length()).map { chunks.getString(it) })
            val files = descriptor.getJSONArray("files")
            require(manifest.sha256.matches(Regex("[0-9a-f]{64}")) && files.length() == chunks.length())
            if (!cloudOnly) {
                if (source?.isFile == true && CloudShelfMarker.read(source) == null &&
                    source.length() == manifest.length && fileHash(source) == manifest.sha256) {
                    paths[id] = source.absolutePath
                    continue
                }
                val destination = File(originals, id + "-" + manifest.sha256 + ".png")
                if (destination.isFile && destination.length() == manifest.length && fileHash(destination) == manifest.sha256) {
                    paths[id] = destination.absolutePath
                    continue
                }
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
                continue
            }
            if (source?.isFile == true && CloudShelfMarker.read(source) == null) {
                check(source.length() == manifest.length && fileHash(source) == manifest.sha256) { "로컬 원본과 클라우드 사진이 다릅니다." }
                paths[id] = source.absolutePath
                continue
            }
            val referenceHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(descriptor.toString().toByteArray()).take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
            val destination = File(originals, id + "-" + manifest.sha256 + "-" + referenceHash + ".cloud")
            CloudShelfMarker.write(destination, id, descriptor)
            paths[id] = destination.absolutePath
            cache.put(id, JSONObject().put("stamp", stamp(destination)).put("manifest", descriptor)); save(cacheFile, cache)
            } finally { downloaded(i + 1) }
        }
        return Result(projected, paths)
    }
    fun acknowledge(directory: File, snapshot: JSONObject) {
        val file = File(directory, "replica.json"); val replica = BookshelfSyncReplica(read(file))
        replica.acknowledge(snapshot); save(file, replica.state)
    }
    private fun fileHash(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { interrupted(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
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
