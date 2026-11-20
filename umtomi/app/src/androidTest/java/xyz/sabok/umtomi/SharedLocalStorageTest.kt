package xyz.sabok.umtomi

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import xyz.sabok.umtomi.ui.SharedBookshelfStorage
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SharedLocalStorageTest {
    @Test fun twoAppsShareOriginalsAndVolumeSwitchWithoutFallback() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val token = UUID.randomUUID().toString()
        val testRoot = File(base.cacheDir, "shared-local-$token").apply { mkdirs() }
        val preferences = mutableListOf<SharedPreferences>()
        fun app(name: String): Context = object : ContextWrapper(base) {
            override fun getPackageName() = name
            override fun getFilesDir() = File(testRoot, "$name/files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(testRoot, "$name/no-backup").apply { mkdirs() }
            override fun getSharedPreferences(key: String, mode: Int) = base.getSharedPreferences("$token-$name-$key", mode).also { if (it !in preferences) preferences += it }
        }
        fun legacy(context: Context): File {
            val root = File(context.filesDir, "bookshelf").apply { mkdirs() }
            val original = File(root, "original.png").apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3)) }
            val name = "책장1"
            return File(root, "catalog.json").apply {
                writeText(JSONObject().put("folders", JSONArray(listOf(name))).put("activeFolder", name)
                    .put("folderIds", JSONObject().put(name, "a".repeat(32))).put("folderNotes", JSONObject().put(name, "memo"))
                    .put("folderColumnCounts", JSONObject().put(name, 3)).put("items", JSONArray().put(JSONObject()
                        .put("id", "b".repeat(32)).put("folder", name).put("filePath", original.path)
                        .put("sourceUrl", "synthetic").put("width", 1).put("height", 1).put("createdAt", 1))).toString())
            }
        }
        try {
            val internal = File(testRoot, "internal").apply { mkdirs() }
            val sd = File(testRoot, "sd").apply { mkdirs() }
            val volumes = mapOf("primary" to File(internal, "Omyaru_Bookshelf"), "test-sd" to File(sd, "Omyaru_Bookshelf"))
            val uContext = app("xyz.sabok.umtomi"); val bContext = app("cuttan.omyaru.app")
            val uLegacy = legacy(uContext); val bLegacy = legacy(bContext)
            val umtomi = SharedBookshelfStorage(uContext, volumes)
            val browser = SharedBookshelfStorage(bContext, volumes)
            umtomi.activate(uLegacy); browser.activate(bLegacy)
            assertTrue(umtomi.enabled); assertTrue(browser.enabled)
            assertEquals(1, internal.walkTopDown().count { it.isFile && it.extension == "png" })
            assertFalse(File(uLegacy.parentFile, "original.png").exists())
            assertFalse(File(bLegacy.parentFile, "original.png").exists())
            val before = umtomi.readCatalog()
            val name = before.getJSONArray("folders").getString(0)
            val edited = JSONObject(before.toString()).apply { getJSONObject("folderNotes").put(name, "edited while moving") }
            browser.moveTo("test-sd")
            val combined = umtomi.commit(before, edited)
            assertEquals("edited while moving", combined.getJSONObject("folderNotes").getString(name))
            val moved = File(combined.getJSONArray("items").getJSONObject(0).getString("filePath"))
            assertTrue("Expected SD destination: ${moved.canonicalPath}", moved.canonicalFile.toPath().startsWith(sd.canonicalFile.toPath())); assertTrue(moved.isFile)
            assertEquals(0, internal.walkTopDown().count { it.isFile && it.extension == "png" })
            val offline = File(testRoot, "sd-offline")
            assertTrue(sd.renameTo(offline))
            assertThrows(IllegalStateException::class.java) { umtomi.readCatalog() }
            assertEquals(0, internal.walkTopDown().count { it.isFile && it.extension == "png" })
            assertTrue(offline.renameTo(sd))
            umtomi.moveTo("primary")
            assertEquals(1, browser.readCatalog().getJSONArray("items").length())
            assertEquals(1, internal.walkTopDown().count { it.isFile && it.extension == "png" })
            assertEquals(0, sd.walkTopDown().count { it.isFile && it.extension == "png" })
        } finally {
            preferences.forEach { it.edit().clear().commit() }
            testRoot.deleteRecursively()
        }
    }
}

