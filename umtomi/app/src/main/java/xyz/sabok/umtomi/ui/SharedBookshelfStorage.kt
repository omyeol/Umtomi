package xyz.sabok.umtomi.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.UUID
import xyz.sabok.umtomi.sync.CloudAccount

/**
 * Shared device-wide bookshelf storage (matching Omyaru Browser 100%):
 * - User-selected base folder (via system folder picker or default /Omyaru_Bookshelf)
 * - Automatic per-account subfolder (`<base>/<sanitized-account-email>/`) so different accounts never mix
 * - Kernel-enforced cross-process exclusive sync lock (`sync.lock` + `sync-lease.json`) so Umtomi & Omyaru never collide.
 */
@android.annotation.SuppressLint("NewApi")
internal class SharedBookshelfStorage(private val context: Context, private val testVolumes: Map<String, File>? = null) {
    companion object {
        const val DIRECTORY = "Omyaru_Bookshelf"
        private const val PREFS_NAME = "shared-bookshelf-storage"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_IMPORTED = "imported"
        private const val KEY_CONFIGURED_V1 = "sync_folder_configured_v1"
        private const val KEY_CUSTOM_BASE_DIR = "custom_base_dir"
        private const val KEY_ACCOUNT_SUBFOLDER_MODE = "account_subfolder_mode"
        private const val KEY_ACCOUNT_EMAIL = "active_account_email"

        private val gate = Any()
        private val syncProcessGate = Any()

        fun hasAccess(): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

        /** Identical sanitization rule across Android & iOS (Omyaru & Umtomi). */
        fun sanitizeAccountFolderName(raw: String?): String {
            val trimmed = raw.orEmpty().trim().lowercase(java.util.Locale.ROOT)
            if (trimmed.isEmpty() || trimmed == "." || trimmed == ".." ||
                trimmed == ".omyaru" || trimmed == ".omyaru-control") return "default"
            val cleaned = buildString(trimmed.length) {
                for (ch in trimmed) {
                    if (ch.code < 32 || ch in "\\/:*?\"<>|") append('_') else append(ch)
                }
            }.trim().trim('.', ' ').take(120)
            if (cleaned.isEmpty() || cleaned == "." || cleaned == ".." ||
                cleaned == ".omyaru" || cleaned == ".omyaru-control") {
                return "default"
            }
            return cleaned
        }

        /** Maps an Android SAF `ACTION_OPEN_DOCUMENT_TREE` URI to a real filesystem directory when MANAGE_EXTERNAL_STORAGE is held. */
        fun resolveTreeUriToDirectory(context: Context, treeUri: Uri): File? {
            val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
            val parts = docId.split(':', limit = 2)
            val volumeId = parts.getOrNull(0).orEmpty()
            val relative = parts.getOrNull(1).orEmpty().trim('/')
            val volumeRoot: File = when {
                volumeId.equals("primary", ignoreCase = true) -> Environment.getExternalStorageDirectory()
                volumeId.equals("home", ignoreCase = true) -> File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOCUMENTS)
                Build.VERSION.SDK_INT >= 30 -> {
                    val manager = context.getSystemService(StorageManager::class.java)
                    manager?.storageVolumes?.firstOrNull {
                        it.uuid.equals(volumeId, ignoreCase = true) && it.state == Environment.MEDIA_MOUNTED && it.directory != null
                    }?.directory ?: return null
                }
                else -> return null
            }
            return if (relative.isEmpty()) File(volumeRoot, DIRECTORY) else File(volumeRoot, relative)
        }
    }

    sealed class SyncLockResult<out T> {
        data class Completed<T>(val value: T) : SyncLockResult<T>()
        data class Deferred(val holderApp: String) : SyncLockResult<Nothing>()
    }

    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false) &&
            (testVolumes != null || context.filesDir == context.applicationContext.filesDir)

    val needsInitialFolderSetup: Boolean
        get() = !preferences.getBoolean(KEY_CONFIGURED_V1, false)

    val customBasePath: String?
        get() = preferences.getString(KEY_CUSTOM_BASE_DIR, null)?.takeIf { it.isNotBlank() }

    val usesAccountSubfolder: Boolean
        get() = preferences.getBoolean(KEY_ACCOUNT_SUBFOLDER_MODE, false)

    private val primary get() = testVolumes?.get("primary") ?: File(Environment.getExternalStorageDirectory(), DIRECTORY)
    private val appName get() = if (context.packageName == "xyz.sabok.umtomi") "Umtomi" else "Omyaru Browser"

    private fun baseDirectory(): File {
        val custom = customBasePath
        if (custom != null) return File(custom)
        return primary
    }

    private val control get() = File(baseDirectory(), ".omyaru-control")
    private val location get() = File(control, "location.json")

    fun activeAccountEmail(explicitEmail: String? = null): String {
        val explicit = explicitEmail?.trim().orEmpty()
        if (explicit.isNotEmpty()) {
            if (preferences.getString(KEY_ACCOUNT_EMAIL, null) != explicit) {
                preferences.edit().putString(KEY_ACCOUNT_EMAIL, explicit).apply()
            }
            return explicit
        }
        val fromAccount = runCatching { CloudAccount.account(context)?.trim().orEmpty() }.getOrDefault("")
        if (fromAccount.isNotEmpty()) {
            if (preferences.getString(KEY_ACCOUNT_EMAIL, null) != fromAccount) {
                preferences.edit().putString(KEY_ACCOUNT_EMAIL, fromAccount).apply()
            }
            return fromAccount
        }
        return preferences.getString(KEY_ACCOUNT_EMAIL, "").orEmpty()
    }

    fun accountFolderPreview(baseDir: File? = null, accountEmail: String? = null): String {
        val accountSeg = sanitizeAccountFolderName(activeAccountEmail(accountEmail))
        val base = baseDir ?: if (enabled) baseDirectory() else null
        return if (base != null) File(base, accountSeg).absolutePath else "앱 전용 저장소 ($accountSeg)"
    }

    fun markPrivateStorageSelected() {
        preferences.edit()
            .putBoolean(KEY_ENABLED, false)
            .putBoolean(KEY_CONFIGURED_V1, true)
            .apply()
    }

    fun volumes(): List<Pair<String, String>> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val manager = context.getSystemService(StorageManager::class.java)
        return listOf("primary" to "내부 저장소") + manager.storageVolumes.filter {
            it.isRemovable && it.state == Environment.MEDIA_MOUNTED && it.directory != null
        }.mapNotNull { volume -> volume.uuid?.let { it to volume.getDescription(context) } }
    }

    private fun resolve(volume: String): File {
        check(testVolumes != null || hasAccess()) { "공용 책장 파일 접근 권한이 필요합니다." }
        if (testVolumes != null) return checkNotNull(testVolumes[volume]?.takeIf { it.parentFile?.isDirectory == true }) { "선택한 저장 매체를 연결해 주세요." }
        if (volume == "primary") return baseDirectory()
        check(Build.VERSION.SDK_INT >= 30)
        val selected = context.getSystemService(StorageManager::class.java).storageVolumes.firstOrNull { it.uuid == volume && it.isRemovable }
        check(selected != null && selected.state == Environment.MEDIA_MOUNTED && selected.directory != null) {
            "선택한 SD 카드를 연결해 주세요. 내부 저장소로 자동 전환하지 않습니다."
        }
        return File(selected.directory, DIRECTORY)
    }

    fun root(accountEmail: String? = null): File {
        val base = if (customBasePath != null) {
            check(testVolumes != null || hasAccess()) { "공용 책장 파일 접근 권한이 필요합니다." }
            File(customBasePath!!)
        } else {
            val selected = if (location.exists()) read(location).getString("volume") else "primary"
            resolve(selected)
        }
        val target = if (usesAccountSubfolder) {
            val accountSeg = sanitizeAccountFolderName(activeAccountEmail(accountEmail))
            File(base, accountSeg).also { check(it.mkdirs() || it.isDirectory) { "계정 책장 폴더를 만들 수 없습니다." } }
        } else {
            base
        }
        return target.also { check(it.isDirectory) { "공용 책장 폴더가 없습니다. 저장 매체를 확인해 주세요." } }
    }

    fun syncStateDirectory(accountEmail: String): File {
        if (enabled && (testVolumes != null || hasAccess())) {
            val accountRoot = root(accountEmail)
            return File(accountRoot, ".omyaru/sync-state").apply { mkdirs() }
        }
        val scope = MessageDigest.getInstance("SHA-256")
            .digest(accountEmail.lowercase(java.util.Locale.ROOT).toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(context.noBackupFilesDir, "omyaru-shared-shelf/$scope").apply { mkdirs() }
    }

    fun description(accountEmail: String? = null): String =
        if (!enabled) "앱 전용 저장소 사용 중 (공용 폴더 미지정)"
        else runCatching { coordinated { root(accountEmail).absolutePath } }.getOrElse { it.localizedMessage ?: "저장소 확인 필요" }

    fun activate(privateCatalog: File) = coordinated {
        check(primary.mkdirs() || primary.isDirectory)
        val destination = if (location.exists()) root() else primary
        File(destination, ".nomedia").createNewFile()
        val disk = BookshelfFileTransaction(destination)
        if (!preferences.getBoolean(KEY_IMPORTED, false)) {
            val legacy = if (privateCatalog.exists()) read(privateCatalog) else JSONObject()
            val migrated = migrateNames(legacy)
            val result = disk.commit(JSONObject(), migrated, listOf(context.filesDir, context.noBackupFilesDir))
            check(preferences.edit().putBoolean(KEY_IMPORTED, true).putBoolean(KEY_ENABLED, true).commit())
            cleanupPrivateCopies(legacy, result)
        } else check(preferences.edit().putBoolean(KEY_ENABLED, true).commit())
    }

    fun activateFolder(baseDir: File, accountEmail: String?, privateCatalog: File): File {
        check(testVolumes != null || hasAccess()) { "공용 책장 파일 접근 권한이 필요합니다." }
        val normalizedBase = baseDir.canonicalFile
        check(normalizedBase.mkdirs() || normalizedBase.isDirectory) { "선택한 폴더를 만들 수 없습니다: ${normalizedBase.absolutePath}" }
        runCatching { File(normalizedBase, ".nomedia").createNewFile() }

        val email = activeAccountEmail(accountEmail)
        val accountSeg = sanitizeAccountFolderName(email)
        val destination = File(normalizedBase, accountSeg)
        check(destination.mkdirs() || destination.isDirectory) { "계정 폴더를 만들 수 없습니다: ${destination.absolutePath}" }
        runCatching { File(destination, ".nomedia").createNewFile() }

        val previousSharedRoot: File? = if (enabled) runCatching { root(email).canonicalFile }.getOrNull() else null
        val legacyFlatCatalog = File(normalizedBase, ".omyaru/catalog.json")

        val disk = BookshelfFileTransaction(destination)
        val allowedSources = buildList {
            add(context.filesDir)
            add(context.noBackupFilesDir)
            if (previousSharedRoot != null && previousSharedRoot != destination.canonicalFile) add(previousSharedRoot)
            if (legacyFlatCatalog.isFile) add(normalizedBase)
        }

        if (previousSharedRoot != null && previousSharedRoot != destination.canonicalFile && File(previousSharedRoot, ".omyaru/catalog.json").isFile) {
            val prevCatalog = BookshelfFileTransaction(previousSharedRoot).read()
            if ((prevCatalog.optJSONArray("items")?.length() ?: 0) > 0 || (prevCatalog.optJSONArray("folders")?.length() ?: 0) > 0) {
                disk.commit(JSONObject(), prevCatalog, allowedSources)
            }
        } else if (legacyFlatCatalog.isFile && normalizedBase != destination.canonicalFile) {
            val flatCatalog = BookshelfFileTransaction(normalizedBase).read()
            if ((flatCatalog.optJSONArray("items")?.length() ?: 0) > 0 || (flatCatalog.optJSONArray("folders")?.length() ?: 0) > 0) {
                disk.commit(JSONObject(), flatCatalog, allowedSources)
            }
        }

        if (privateCatalog.isFile) {
            val legacy = read(privateCatalog)
            if ((legacy.optJSONArray("items")?.length() ?: 0) > 0 || (legacy.optJSONArray("folders")?.length() ?: 0) > 0) {
                val migrated = migrateNames(legacy)
                val result = disk.commit(JSONObject(), migrated, allowedSources)
                cleanupPrivateCopies(legacy, result)
            }
        }

        check(
            preferences.edit()
                .putString(KEY_CUSTOM_BASE_DIR, normalizedBase.absolutePath)
                .putString(KEY_ACCOUNT_EMAIL, email)
                .putBoolean(KEY_ACCOUNT_SUBFOLDER_MODE, true)
                .putBoolean(KEY_IMPORTED, true)
                .putBoolean(KEY_ENABLED, true)
                .putBoolean(KEY_CONFIGURED_V1, true)
                .commit(),
        )
        return destination
    }

    fun <T> withExclusiveSyncLock(
        accountEmail: String,
        maxWaitMs: Long = 30_000L,
        onWaiting: (String) -> Unit = {},
        action: (syncStateDir: File, sharedAccountRoot: File?) -> T,
    ): SyncLockResult<T> {
        val sharedRoot = if (enabled && (testVolumes != null || hasAccess())) {
            runCatching { root(accountEmail) }.getOrNull()
        } else null
        val syncDir = syncStateDirectory(accountEmail)
        if (sharedRoot == null) {
            return synchronized(syncProcessGate) {
                SyncLockResult.Completed(action(syncDir, null))
            }
        }

        val metaDir = File(sharedRoot, ".omyaru").apply { mkdirs() }
        val lockFile = File(metaDir, "sync.lock")
        val leaseFile = File(metaDir, "sync-lease.json")
        val deadline = System.currentTimeMillis() + maxWaitMs

        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Sync interrupted while waiting for lock")
            val raf = RandomAccessFile(lockFile, "rw")
            val lock = try {
                raf.channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } catch (err: Throwable) {
                runCatching { raf.close() }
                throw err
            }
            if (lock != null) {
                val startedAt = System.currentTimeMillis()
                runCatching {
                    write(
                        leaseFile,
                        JSONObject()
                            .put("app", appName)
                            .put("startedAt", startedAt)
                            .put("heartbeatAt", startedAt),
                    )
                }
                try {
                    return SyncLockResult.Completed(action(syncDir, sharedRoot))
                } finally {
                    runCatching { leaseFile.delete() }
                    runCatching { lock.release() }
                    runCatching { raf.close() }
                }
            }
            runCatching { raf.close() }
            val holder = runCatching { read(leaseFile).optString("app").takeIf { it.isNotBlank() } }.getOrNull() ?: "다른 앱"
            if (System.currentTimeMillis() >= deadline) {
                return SyncLockResult.Deferred(holder)
            }
            onWaiting("$holder 에서 같은 동기화 폴더를 동기화 중입니다. 완료를 기다리는 중…")
            Thread.sleep(400L)
        }
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
            val safe = old.split('/').joinToString("/") { part ->
                part.map { if (it.code < 32 || it in "\\:*?\"<>|") '_' else it }.joinToString("").trim().trimEnd('.').take(90).ifEmpty { "책장" }
            }
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
        val digest = MessageDigest.getInstance("SHA-256")
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
