package xyz.sabok.umtomi

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import xyz.sabok.umtomi.sync.*
import xyz.sabok.umtomi.ui.BookshelfStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 26)
class DriveSyncTest {
    @Test fun legacyImportedImageIdsMigrateWithoutMovingOriginals() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val temporary = java.io.File(base.cacheDir, "shelf-migration-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val context = object : android.content.ContextWrapper(base) { override fun getFilesDir() = temporary }
        try {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            val store = BookshelfStore(context)
            val item = try { store.addImage(bitmap, "fixture") } finally { bitmap.recycle() }
            val legacy = java.util.UUID.randomUUID().toString()
            store.items[0] = item.copy(id = legacy)
            store.save()
            val before = java.io.File(item.filePath).readBytes()
            store.prepareSync()
            assertEquals(legacy.replace("-", ""), BookshelfStore(context).items.single().id)
            assertEquals(item.filePath, store.items.single().filePath)
            assertArrayEquals(before, java.io.File(item.filePath).readBytes())
            assertTrue(java.io.File(temporary, "bookshelf").listFiles()!!.any { it.name.startsWith("catalog.json.before-id-migration-") })
            BookshelfSyncReplica().capture(store.syncSnapshot())
            store.prepareSync()
            assertEquals(1, java.io.File(temporary, "bookshelf").listFiles()!!.count { it.name.startsWith("catalog.json.before-id-migration-") })
        } finally { temporary.deleteRecursively() }
    }
    @Test fun reconnectCompletesWithoutStartingShelfTransfer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("umtomi-drive", android.content.Context.MODE_PRIVATE)
        val stores = listOf("umtomi-drive", "shared-bookshelf", "umtomi-cloud-account").map { context.getSharedPreferences(it, android.content.Context.MODE_PRIVATE) }
        val saved = stores.map { it.all.toMap() }
        try {
            androidx.test.core.app.ActivityScenario.launch<xyz.sabok.umtomi.ui.DriveSyncActivity>(android.content.Intent(context, xyz.sabok.umtomi.ui.DriveSyncActivity::class.java)).use { scenario ->
                repeat(2) {
                    scenario.onActivity { activity ->
                        activity.finishAuthorization("fixture@example.invalid", "invalid-test-token")
                        fun texts(view: android.view.View): List<android.widget.TextView> =
                            (if (view is android.widget.TextView) listOf(view) else emptyList()) +
                            (if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList())
                        val views = texts(activity.window.decorView)
                        assertTrue(views.any { it.text.startsWith("계정 연결 완료") })
                        assertEquals("fixture@example.invalid", xyz.sabok.umtomi.sync.CloudAccount.account(context))
                        assertEquals("fixture@example.invalid", context.getSharedPreferences("shared-bookshelf", 0).getString("account", null))
                        assertTrue(views.single { it.text.toString() == "지금 동기화" }.isEnabled)
                        assertEquals(android.view.View.GONE, views.single { it.text.toString() == "작업 중단" }.visibility)
                        activity.expireAuthorization()
                        assertTrue(views.any { it.text.startsWith("Google 응답 시간이 초과") })
                        assertTrue(views.single { it.text.toString() == "Google 계정 연결 / 변경" }.isEnabled)
                    }
                }
            }
        } finally {
            stores.zip(saved).forEach { (store, values) ->
                store.edit().clear().apply { values.forEach { (key, value) ->
                    when (value) {
                        is String -> putString(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                } }.commit()
            }
        }
    }
    @Test fun connectionScreenOpensWithoutLoggingIn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        androidx.test.core.app.ActivityScenario.launch<xyz.sabok.umtomi.ui.DriveSyncActivity>(android.content.Intent(context, xyz.sabok.umtomi.ui.DriveSyncActivity::class.java)).use {
            instrumentation.waitForIdleSync()
            val image = instrumentation.uiAutomation.takeScreenshot()
            java.io.File(context.getExternalFilesDir(null), "drive-screen.png").outputStream().use { output -> image.compress(Bitmap.CompressFormat.PNG, 100, output) }
            image.recycle()
            it.recreate()
        }
    }
    @Test fun concurrentChangesConvergeAndDeletedFavoritesStayDeleted() {
        val a = UserDataReplica(); val b = UserDataReplica()
        a.capture(JSONObject().put("favorite/1", true))
        b.capture(JSONObject().put("favorite/2", true))
        val first = JSONObject(a.pending.getJSONObject(0).toString())
        a.apply(b.pending.getJSONObject(0)); b.apply(first)
        assertTrue(BookshelfSyncReplica.same(a.project(), b.project()))
        a.acknowledge(a.project()); b.acknowledge(b.project())
        a.capture(JSONObject().put("favorite/2", true))
        b.apply(a.pending.getJSONObject(a.pending.length()-1))
        b.apply(first)
        assertFalse(b.project().has("favorite/1"))
        assertTrue(b.project().has("favorite/2"))
        val resumed = UserDataReplica(JSONObject(b.state.toString()))
        assertTrue(BookshelfSyncReplica.same(b.project(), resumed.project()))
    }
    @Test fun favoriteFolderDeletionAndHistoryRestoreAreApplied() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val before = UmtomiDriveSync.snapshot(context)
        val folderId = java.util.UUID.randomUUID().toString()
        try {
            val folder = JSONObject().put("id", folderId).put("name", "Synced folder")
                .put("parentID", java.util.UUID.randomUUID().toString())
                .put("manualGalleryIDs", org.json.JSONArray(listOf(2000000008, 2000000009)))
            UmtomiDriveSync.apply(context, JSONObject().put("favorite/2000000008", true)
                .put("history/2000000008", 0).put("folder/$folderId", folder))
            assertEquals(setOf(2000000008), favorites.toSet())
            assertEquals(setOf(2000000008), histories.toSet())
            val library = xyz.sabok.umtomi.util.FavoriteFolders(context)
            assertEquals(listOf(2000000008), library.folders.single().manualGalleryIDs)
            assertNull(library.folders.single().parentID)
            UmtomiDriveSync.apply(context, JSONObject())
            assertTrue(favorites.isEmpty())
            assertTrue(xyz.sabok.umtomi.util.FavoriteFolders(context).folders.isEmpty())
        } finally { UmtomiDriveSync.apply(context, before) }
    }
    @Test fun namespaceAndEncryptionAreSeparateFromBrowser() {
        assertFalse(DriveAppDataClient.validShelfName("omyaru-shelf-v1-" + "a".repeat(32) + ".json"))
        assertFalse(DriveAppDataClient.validKeyName("omyaru-account-key-v1-" + "a".repeat(32) + ".json"))
        val bytes = "test data".toByteArray()
        val envelope = UmtomiSyncCrypto.encrypt(bytes, "test-password".toCharArray())
        assertArrayEquals(bytes, UmtomiSyncCrypto.decrypt(envelope, "test-password".toCharArray()))
        val corrupt = JSONObject(envelope)
        corrupt.getJSONObject("cipher").put("ciphertext", "A".repeat(64))
        assertTrue(runCatching { UmtomiSyncCrypto.decrypt(corrupt.toString(), "test-password".toCharArray()) }.isFailure)
    }
    @Test fun originalImageChunksRoundTripAndRejectCorruption() {
        val bytes = ByteArray(BookshelfImageChunks.CHUNK_SIZE + 23) { (it % 251).toByte() }
        val chunks = mutableListOf<String>()
        val manifest = BookshelfImageChunks.encode(ByteArrayInputStream(bytes)) { _, value -> chunks += value }
        val output = ByteArrayOutputStream()
        BookshelfImageChunks.decode(manifest, { chunks[it] }, output)
        assertArrayEquals(bytes, output.toByteArray())
        assertTrue(runCatching { BookshelfImageChunks.decode(manifest.copy(sha256="0".repeat(64)), { chunks[it] }, ByteArrayOutputStream()) }.isFailure)
    }
    @Test fun shelfIdentitySurvivesRenameAndReload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "Drive test " + System.nanoTime()
        val store = BookshelfStore(context)
        val previous = store.activeFolder
        try {
            store.addFolder(name)
            val id = store.folderId(name)
            assertTrue(store.renameFolder(name, "$name renamed"))
            assertEquals(id, BookshelfStore(context).folderId("$name renamed"))
            val snapshot = store.syncSnapshot()
            val copy = BookshelfSyncReplica()
            copy.capture(snapshot)
            assertTrue(copy.project().getJSONArray("folders").length() > 0)
        } finally {
            store.deleteFolder("$name renamed"); store.deleteFolder(name); store.selectFolder(previous)
        }
    }
}
