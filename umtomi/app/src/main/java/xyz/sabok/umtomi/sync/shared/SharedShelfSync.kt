package xyz.sabok.umtomi.sync.shared

import android.content.Context
import androidx.annotation.RequiresApi
import xyz.sabok.umtomi.ui.BookshelfStore
import xyz.sabok.umtomi.sync.UmtomiDriveSync
import java.io.File
import java.security.MessageDigest

@RequiresApi(26)
internal object SharedShelfSync {
    private fun <T> onMain(block: () -> T): T {
        val task = java.util.concurrent.FutureTask<T> { block() }
        android.os.Handler(android.os.Looper.getMainLooper()).post(task)
        try { return task.get() }
        catch (error: InterruptedException) { task.cancel(false); throw error }
    }
    // Only the shelf/key namespaces are consumed. Browser tabs and userdata
    // are never read, copied or written by this adapter.
    fun run(context: Context, account: String, token: () -> String, applied: () -> Unit = {}, progress: (String) -> Unit = {}, valid: () -> Boolean): Boolean = UmtomiDriveSync.exclusive {
        check(valid())
        progress("공통 책장 암호화 키 확인 중")
        val client = DriveAppDataClient { check(valid()); token() }.apply { accountMode = true }
        val keys = DriveAccountKeys.load(client)
        check(keys.hasKeys) { "Omyaru 연결 확인 필요" }
        val scope = MessageDigest.getInstance("SHA-256").digest(account.lowercase(java.util.Locale.ROOT).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val directory = File(context.noBackupFilesDir, "omyaru-shared-shelf/$scope")
        val store = onMain {
            check(valid())
            BookshelfStore(context).also { it.prepareSync() }
        }
        val before = store.syncSnapshot()
        val sources = store.items.associate { it.id to it.filePath }
        var expected = before
        var expectedPaths = sources
        fun applyStage(stage: DriveBookshelfSync.Result): Boolean = onMain {
            check(valid())
            val fresh = BookshelfStore(context)
            check(fresh.loadError == null && BookshelfSyncReplica.same(expected, fresh.syncSnapshot()))
            val visible = org.json.JSONObject(stage.snapshot.toString())
            val images = visible.getJSONArray("images")
            for (i in 0 until images.length()) images.getJSONObject(i).put("manifest", org.json.JSONObject())
            val changed = !BookshelfSyncReplica.same(expected, visible) || stage.paths.any { expectedPaths[it.key] != it.value }
            if (changed) { fresh.applySynced(stage.snapshot, stage.paths); applied() }
            val updated = BookshelfStore(context)
            expected = updated.syncSnapshot()
            expectedPaths = updated.items.associate { it.id to it.filePath }
            context.getSharedPreferences("shared-bookshelf", Context.MODE_PRIVATE).edit().putBoolean("joined", true).apply()
            changed
        }
        val cloudOnly = context.getSharedPreferences("bookshelf_sync", Context.MODE_PRIVATE)
            .getString("image_mode", "local") == "on_demand"
        val result = DriveBookshelfSync.run(directory, before, sources, client, keys, progress, cloudOnly,
            metadataReady = { applyStage(it) })
        val changed = applyStage(result)
        DriveBookshelfSync.acknowledge(directory, result.snapshot)
        changed
    }
}
