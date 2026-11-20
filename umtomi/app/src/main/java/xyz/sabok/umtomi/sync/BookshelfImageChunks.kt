package xyz.sabok.umtomi.sync

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONObject

/** Shared Windows/Android format; encrypt chunks before uploading and decode into staging only. */
@androidx.annotation.RequiresApi(26)
internal object BookshelfImageChunks {
    const val CHUNK_SIZE = 1024 * 1024
    const val MAXIMUM_BYTES = 512L * CHUNK_SIZE
    data class Manifest(val version: Int, val length: Long, val sha256: String, val chunks: List<String>)
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun encode(input: InputStream, emit: (Int, String) -> Unit): Manifest {
        val fullHash = MessageDigest.getInstance("SHA-256")
        val hashes = mutableListOf<String>()
        val buffer = ByteArray(CHUNK_SIZE)
        var length = 0L
        while (true) {
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                check(read != 0) { "이미지 읽기가 진행되지 않습니다." }
                count += read
            }
            if (count == 0) break
            length += count
            require(length <= MAXIMUM_BYTES) { "동기화 이미지가 512MB를 초과했습니다." }
            val bytes = buffer.copyOf(count)
            fullHash.update(bytes)
            val hash = digest(bytes)
            emit(hashes.size, JSONObject().put("version", 1).put("index", hashes.size)
                .put("sha256", hash).put("data", Base64.getEncoder().encodeToString(bytes)).toString())
            hashes += hash
        }
        require(length > 0) { "빈 이미지 파일입니다." }
        return Manifest(1, length, hex(fullHash.digest()), hashes)
    }

    fun decode(manifest: Manifest, read: (Int) -> String, staging: OutputStream) {
        fun validHash(value: String) = value.matches(Regex("[0-9a-f]{64}"))
        require(manifest.version == 1 && manifest.length in 1..MAXIMUM_BYTES && validHash(manifest.sha256) &&
            manifest.chunks.size.toLong() == (manifest.length + CHUNK_SIZE - 1) / CHUNK_SIZE && manifest.chunks.all(::validHash))
        val fullHash = MessageDigest.getInstance("SHA-256")
        manifest.chunks.forEachIndexed { index, hash ->
            val json = read(index)
            require(json.length <= 2 * CHUNK_SIZE)
            val chunk = JSONObject(json)
            require(chunk.getInt("version") == 1 && chunk.getInt("index") == index && chunk.getString("sha256") == hash)
            val bytes = Base64.getDecoder().decode(chunk.getString("data"))
            val expected = minOf(CHUNK_SIZE.toLong(), manifest.length - index.toLong() * CHUNK_SIZE)
            require(bytes.size.toLong() == expected && digest(bytes) == hash) { "이미지 조각이 손상되었습니다." }
            fullHash.update(bytes)
            staging.write(bytes)
        }
        require(hex(fullHash.digest()) == manifest.sha256) { "원본 이미지 검증에 실패했습니다." }
    }
}
