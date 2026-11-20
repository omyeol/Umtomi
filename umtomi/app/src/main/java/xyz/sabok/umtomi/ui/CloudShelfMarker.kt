package xyz.sabok.umtomi.ui

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.accounts.Account
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import xyz.sabok.umtomi.sync.CloudAccount
import xyz.sabok.umtomi.sync.shared.BookshelfImageChunks
import xyz.sabok.umtomi.sync.shared.DriveAccountKeys
import xyz.sabok.umtomi.sync.shared.DriveAppDataClient

/** A small, non-secret local reference to an encrypted Drive original. */
internal object CloudShelfMarker {
    private const val KIND = "omyaru-cloud-image-v1"
    private const val CACHE_LIMIT = 96L * 1024 * 1024

    fun read(file: File): JSONObject? = runCatching {
        if (!file.isFile || file.length() !in 32..1_048_576) return null
        JSONObject(file.readText()).takeIf { it.optString("kind") == KIND &&
            it.optString("id").matches(Regex("[0-9a-f]{32}")) &&
            it.getJSONObject("manifest").optString("sha256").matches(Regex("[0-9a-f]{64}")) }
    }.getOrNull()

    fun write(file: File, id: String, manifest: JSONObject) {
        require(id.matches(Regex("[0-9a-f]{32}")))
        require(manifest.optString("sha256").matches(Regex("[0-9a-f]{64}")))
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${UUID.randomUUID()}.partial")
        try {
            temp.outputStream().use { stream ->
                stream.write(JSONObject().put("kind", KIND).put("id", id).put("manifest", manifest).toString().toByteArray())
                stream.fd.sync()
            }
            if (file.exists()) check(read(file)?.getJSONObject("manifest")?.toString() == manifest.toString())
            else check(temp.renameTo(file))
        } finally { temp.delete() }
    }

    fun decode(context: Context, marker: File, targetWidth: Int, decodeFile: (String, Int) -> Bitmap?): Bitmap? {
        if (Build.VERSION.SDK_INT < 26) return null
        val entry = read(marker) ?: return null
        val manifest = entry.getJSONObject("manifest")
        val dir = File(context.cacheDir, "bookshelf-thumbnails").apply { mkdirs() }
        val width = targetWidth.coerceIn(1, 4096)
        val key = "${entry.getString("id")}-${manifest.getString("sha256")}-$width.png"
        val cached = File(dir, key)
        if (cached.isFile) {
            BitmapFactory.decodeFile(cached.path)?.let { cached.setLastModified(System.currentTimeMillis()); return it }
            cached.delete()
        }
        val original = File(context.cacheDir, ".bookshelf-${UUID.randomUUID()}.partial")
        try {
            download(context, manifest, original)
            val bitmap = decodeFile(original.path, width) ?: return null
            val temp = File(dir, ".${UUID.randomUUID()}.partial")
            try {
                temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (!cached.exists()) temp.renameTo(cached)
                trim(dir)
            } finally { temp.delete() }
            return bitmap
        } finally { original.delete() }
    }

    @RequiresApi(26)
    fun retireVerifiedOriginals(oldPaths: Map<String, String>, newPaths: Map<String, String>,
                                snapshot: JSONObject, allowedRoots: List<File>): Int {
        val rows = snapshot.getJSONArray("images")
        val remote = (0 until rows.length()).associate { rows.getJSONObject(it).let { row ->
            row.getString("id") to row.getJSONObject("manifest").getString("sha256")
        } }
        val retained = newPaths.values.map { File(it).canonicalPath }.toSet()
        val roots = allowedRoots.map { it.canonicalFile.toPath() }
        var removed = 0
        for ((id, oldPath) in oldPaths) {
            val next = newPaths[id] ?: continue
            val old = File(oldPath).canonicalFile
            val marker = read(File(next)) ?: continue
            if (old.path in retained || old.path == File(next).canonicalPath ||
                roots.none { old.toPath().startsWith(it) } || !old.isFile || read(old) != null ||
                marker.getString("id") != id || marker.getJSONObject("manifest").getString("sha256") != remote[id]) continue
            val digest = MessageDigest.getInstance("SHA-256")
            old.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            if (hash == remote[id] && old.delete()) removed++
        }
        return removed
    }

    @RequiresApi(26)
    private fun download(context: Context, descriptor: JSONObject, destination: File) {
        val account = requireNotNull(CloudAccount.account(context))
        val request = AuthorizationRequest.builder().setAccount(Account(account, "com.google"))
            .setRequestedScopes(listOf(Scope(DriveAppDataClient.SCOPE))).setOptOutIncludingGrantedScopes(true).build()
        val auth = Tasks.await(Identity.getAuthorizationClient(context).authorize(request), 25, TimeUnit.SECONDS)
        check(!auth.hasResolution())
        val token = requireNotNull(auth.accessToken)
        val client = DriveAppDataClient { token }.apply { accountMode = true }
        val keys = DriveAccountKeys.load(client)
        val chunks = descriptor.getJSONArray("chunks")
        val files = descriptor.getJSONArray("files")
        require(files.length() == chunks.length() && files.length() <= 10000)
        val manifest = BookshelfImageChunks.Manifest(descriptor.getInt("version"), descriptor.getLong("length"),
            descriptor.getString("sha256"), (0 until chunks.length()).map { chunks.getString(it) })
        destination.outputStream().use { output -> BookshelfImageChunks.decode(manifest, { index ->
            val reference = files.getJSONObject(index)
            val name = reference.getString("name")
            require(name.startsWith(DriveAppDataClient.SHELF_CHUNK_PREFIX) && DriveAppDataClient.validShelfName(name))
            keys.decrypt(client.read(DriveAppDataClient.Entry(reference.getString("id"), name, null)))
        }, output) }
    }

    private fun trim(dir: File) {
        val files = dir.listFiles()?.filter(File::isFile)?.sortedBy(File::lastModified) ?: return
        var total = files.sumOf(File::length)
        for (file in files) {
            if (total <= CACHE_LIMIT) break
            total -= file.length()
            file.delete()
        }
    }
}
