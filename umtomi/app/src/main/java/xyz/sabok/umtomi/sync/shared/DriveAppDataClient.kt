package xyz.sabok.umtomi.sync.shared

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Blocking transport: run on the sync worker, never the UI thread. */
@androidx.annotation.RequiresApi(26)
internal class DriveAppDataClient(private val accessToken: () -> String) {
    var accountMode = false
    data class Entry(val id: String, val name: String, val md5Checksum: String?)

    fun list(): List<Entry> = listNamed(if (accountMode) "omyaru-tabs-v" else PREFIX, false)
    fun listKeys(): List<Entry> = listNamed(KEY_PREFIX, true)
    fun listResets(): List<Entry> = listNamed(RESET_PREFIX, false)
    fun listShelf(): List<Entry> = listNamed(SHELF_PREFIX, false)
    fun listUserData(): List<Entry> = listNamed(USERDATA_PREFIX, false)
    private fun listNamed(prefix: String, keys: Boolean): List<Entry> {
        val entries = mutableListOf<Entry>()
        val pages = mutableSetOf<String>()
        var page: String? = null
        do {
            val query = encode("trashed = false and name contains '$prefix'")
            val url = "$API?spaces=appDataFolder&pageSize=1000&q=$query&fields=nextPageToken,files(id,name,md5Checksum)" +
                (page?.let { "&pageToken=${encode(it)}" } ?: "")
            val json = JSONObject(send("GET", url))
            val files = json.getJSONArray("files")
            for (index in 0 until files.length()) {
                val file = files.getJSONObject(index)
                val name = file.getString("name")
                if (if (prefix == SHELF_PREFIX) validShelfRecordName(name) else if (prefix == USERDATA_PREFIX) validUserDataName(name) else if (prefix == RESET_PREFIX) validResetName(name) else if (keys) validKeyName(name) else validName(name) && (accountMode || name.startsWith(PREFIX))) entries += Entry(file.getString("id"), name,
                    file.optString("md5Checksum").takeIf { it.isNotEmpty() })
            }
            page = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
            check(page == null || pages.add(page)) { "Drive 페이지가 반복됩니다." }
        } while (page != null)
        return entries
    }

    fun read(entry: Entry): String {
        require(validName(entry.name) || validKeyName(entry.name) || validResetName(entry.name) || validShelfName(entry.name) || validUserDataName(entry.name)) { "Omyaru 동기화 파일이 아닙니다." }
        return send("GET", "$API/${encode(entry.id)}?alt=media")
    }

    fun deleteCovered(entry: Entry) {
        require(validName(entry.name) || validUserDataName(entry.name))
        try { send("DELETE", "$API/${encode(entry.id)}") }
        catch (error: DriveHttpException) { if (error.status != 404) throw error }
    }

    class DriveHttpException(val status: Int) : java.io.IOException("Google Drive 요청 실패 ($status).")

    // An uncertain upload can be retried with the same id. Consumers deduplicate
    // logical operation ids, not Drive file names (which are not unique).
    fun append(operationId: UUID, encryptedPayload: String): String =
        appendNamed((if (accountMode) ACCOUNT_PREFIX else PREFIX) + operationId.toString().replace("-", "") + ".json", encryptedPayload)
    fun appendKey(keyId: UUID, payload: String): String =
        appendNamed(KEY_PREFIX + keyId.toString().replace("-", "") + ".json", payload)
    fun appendReset(id: UUID, payload: String): String =
        appendNamed(RESET_PREFIX + id.toString().replace("-", "") + ".json", payload)
    fun appendShelf(id: UUID, payload: String): String = appendNamed(SHELF_PREFIX + id.toString().replace("-", "") + ".json", payload)
    fun appendShelfChunk(id: UUID, payload: String): String = appendNamed(SHELF_CHUNK_PREFIX + id.toString().replace("-", "") + ".json", payload)
    fun appendUserData(id: UUID, payload: String): String = appendNamed(USERDATA_PREFIX + id.toString().replace("-", "") + ".json", payload)
    private fun appendNamed(name: String, encryptedPayload: String): String {
        require(validName(name) || validKeyName(name) || validResetName(name) || validShelfName(name) || validUserDataName(name))
        require(encryptedPayload.toByteArray(Charsets.UTF_8).size <= MAXIMUM_BYTES)
        val boundary = "omyaru-${UUID.randomUUID()}"
        val metadata = JSONObject().put("name", name)
            .put("parents", JSONArray().put("appDataFolder")).put("mimeType", "application/json")
        val body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
            "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$encryptedPayload\r\n--$boundary--\r\n"
        return JSONObject(send("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
            body.toByteArray(Charsets.UTF_8), "multipart/related; boundary=$boundary")).getString("id")
    }

    private fun send(method: String, url: String, body: ByteArray? = null, contentType: String? = null): String {
        check(!Thread.currentThread().isInterrupted)
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer ${accessToken()}")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw DriveHttpException(status)
            check(connection.contentLengthLong <= MAXIMUM_BYTES) { "Drive 응답이 너무 큽니다." }
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    check(!Thread.currentThread().isInterrupted)
                    val read = input.read(buffer)
                    if (read < 0) break
                    check(output.size() + read <= MAXIMUM_BYTES) { "Drive 응답이 너무 큽니다." }
                    output.write(buffer, 0, read)
                }
                output.toString("UTF-8")
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        const val PREFIX = "omyaru-tabs-v1-"
        const val ACCOUNT_PREFIX = "omyaru-tabs-v2-"
        const val KEY_PREFIX = "omyaru-account-key-v1-"
        const val RESET_PREFIX = "omyaru-tab-reset-v1-"
        const val SHELF_PREFIX = "omyaru-shelf-v1-"
        const val SHELF_CHUNK_PREFIX = "omyaru-shelf-chunk-v1-"
        const val USERDATA_PREFIX = "omyaru-userdata-v1-"
        const val MAXIMUM_BYTES = 4 * 1024 * 1024
        private const val API = "https://www.googleapis.com/drive/v3/files"
        private val namePattern = Regex("^omyaru-tabs-v[12]-[0-9a-f]{32}\\.json$")
        fun validName(name: String): Boolean = namePattern.matches(name) &&
            name != PREFIX + "0".repeat(32) + ".json" && name != ACCOUNT_PREFIX + "0".repeat(32) + ".json"
        fun validKeyName(name: String): Boolean = Regex("^omyaru-account-key-v1-[0-9a-f]{32}\\.json$").matches(name) &&
            name != KEY_PREFIX + "0".repeat(32) + ".json"
        fun validResetName(name: String): Boolean = Regex("^omyaru-tab-reset-v1-[0-9a-f]{32}\\.json$").matches(name) &&
            name != RESET_PREFIX + "0".repeat(32) + ".json"
        fun validShelfRecordName(name: String) = Regex("^omyaru-shelf-v1-[0-9a-f]{32}\\.json$").matches(name) && name != SHELF_PREFIX + "0".repeat(32) + ".json"
        fun validShelfName(name: String) = validShelfRecordName(name) || Regex("^omyaru-shelf-chunk-v1-[0-9a-f]{32}\\.json$").matches(name) && name != SHELF_CHUNK_PREFIX + "0".repeat(32) + ".json"
        fun validUserDataName(name: String) = Regex("^omyaru-userdata-v1-[0-9a-f]{32}\\.json$").matches(name) && name != USERDATA_PREFIX + "0".repeat(32) + ".json"
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    }
}
