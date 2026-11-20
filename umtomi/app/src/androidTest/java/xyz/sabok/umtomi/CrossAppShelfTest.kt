package xyz.sabok.umtomi

import android.content.Context
import android.content.ContextWrapper
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.sabok.umtomi.ui.SharedBookshelfStorage
import java.io.File

/** Three invocations: Umtomi create -> Browser modify -> Umtomi verify. Synthetic data only. */
@RunWith(AndroidJUnit4::class)
class CrossAppShelfTest {
    @Test fun sharedExternalRoundTrip() {
        val args = InstrumentationRegistry.getArguments()
        val token = args.getString("shelfRun").orEmpty()
        assumeTrue(token.matches(Regex("[0-9a-f]{32}")))
        val stage = args.getString("shelfStage").orEmpty()
        require(stage in setOf("create", "modify", "verify"))
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(Environment.getExternalStorageDirectory(), "Omyaru_Bookshelf/.validation-$token")
        val private = File(base.cacheDir, "cross-shelf-$token").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = private
            override fun getNoBackupFilesDir() = private
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("cross-$token-$name", mode)
        }
        if (stage == "create") check(root.mkdirs())
        check(root.isDirectory)
        val storage = SharedBookshelfStorage(context, mapOf("primary" to root))
        val oldName = "교차 검사"
        if (stage != "verify") {
            val original = File(private, "test.png").apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 7, 8, 9)) }
            val legacy = File(private, "catalog.json").apply { writeText(JSONObject()
                .put("folders", JSONArray(listOf(oldName))).put("folderIds", JSONObject().put(oldName, "c".repeat(32)))
                .put("folderNotes", JSONObject().put(oldName, "initial")).put("folderColumnCounts", JSONObject().put(oldName, 2))
                .put("activeFolder", oldName).put("items", JSONArray().put(JSONObject().put("id", "d".repeat(32))
                    .put("folder", oldName).put("filePath", original.path).put("sourceUrl", "synthetic").put("width", 1).put("height", 1).put("createdAt", 1))).toString()) }
            storage.activate(legacy)
            assertFalse(original.exists())
            assertEquals("image1.png", File(storage.readCatalog().getJSONArray("items").getJSONObject(0).getString("filePath")).name)
        }
        if (stage == "modify") {
            val before = storage.readCatalog()
            assertEquals(1, before.getJSONArray("items").length())
            val sourceName = before.getJSONArray("folders").getString(0)
            assertTrue(sourceName.startsWith("Umtomi/"))
            val edited = JSONObject(before.toString())
            val name = "Omyaru Browser/교차 검사"
            edited.put("folders", JSONArray(listOf(name))).put("folderIds", JSONObject().put(name, "c".repeat(32)))
                .put("folderNotes", JSONObject().put(name, "Browser confirmed")).put("folderColumnCounts", JSONObject().put(name, 1)).put("activeFolder", name)
            edited.getJSONArray("items").getJSONObject(0).put("folder", name)
            storage.commit(before, edited)
        }
        if (stage == "verify") {
            try {
                val result = storage.readCatalog()
                val name = "Omyaru Browser/교차 검사"
                assertEquals("Browser confirmed", result.getJSONObject("folderNotes").getString(name))
                assertEquals(1, result.getJSONObject("folderColumnCounts").getInt(name))
                assertEquals("c".repeat(32), result.getJSONObject("folderIds").getString(name))
                assertEquals(1, root.walkTopDown().count { it.isFile && it.extension == "png" })
                val image = File(result.getJSONArray("items").getJSONObject(0).getString("filePath"))
                assertTrue(image.isFile)
                assertEquals("image1.png", image.name)
            } finally {
                check(root.name == ".validation-$token" && root.parentFile!!.name == "Omyaru_Bookshelf")
                root.deleteRecursively()
            }
        }
        if (stage != "create") { context.getSharedPreferences("shared-bookshelf-storage", Context.MODE_PRIVATE).edit().clear().commit(); private.deleteRecursively() }
    }
}
