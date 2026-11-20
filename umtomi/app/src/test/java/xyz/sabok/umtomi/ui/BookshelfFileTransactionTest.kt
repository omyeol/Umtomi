package xyz.sabok.umtomi.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BookshelfFileTransactionTest {
    private val folderId = "a".repeat(32)
    private val imageId = "b".repeat(32)
    private fun catalog(source: File, name: String = "Umtomi/책장1") = JSONObject()
        .put("folders", JSONArray(listOf(name))).put("folderIds", JSONObject().put(name, folderId))
        .put("folderNotes", JSONObject().put(name, "memo")).put("folderColumnCounts", JSONObject().put(name, 3))
        .put("activeFolder", name).put("items", JSONArray().put(JSONObject().put("id", imageId).put("folder", name)
            .put("filePath", source.path).put("width", 1).put("height", 1).put("createdAt", 1).put("sourceUrl", "fixture")))

    private fun fixture(test: (File, File, BookshelfFileTransaction) -> Unit) {
        val directory = Files.createTempDirectory("shared-shelf-test-").toFile()
        try {
            val root = File(directory, "Omyaru_Bookshelf").apply { mkdir() }
            val original = File(directory, "original.png").apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3)) }
            test(root, original, BookshelfFileTransaction(root))
        } finally { directory.deleteRecursively() }
    }
    @Test fun commitAndRetryUseOneOriginalAndPhysicalHierarchy() = fixture { root, source, disk ->
        val committed = disk.commit(JSONObject(), catalog(source), listOf(source.parentFile!!))
        val destination = File(root, "Umtomi/책장1/image1.png")
        assertArrayEquals(source.readBytes(), destination.readBytes())
        assertEquals(destination.canonicalPath, committed.getJSONArray("items").getJSONObject(0).getString("filePath"))
        disk.commit(committed, committed, listOf(source.parentFile!!))
        assertEquals(1, root.walkTopDown().count { it.isFile && it.extension == "png" })
        assertFalse(File(root, ".omyaru/pending.json").exists())
    }
    @Test fun numberingKeepsCatalogOrderAndOriginalBytes() = fixture { root, source, disk ->
        val second = File(source.parentFile, "second.png").apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 9, 8, 7)) }
        val desired = catalog(source)
        val secondId = "1".repeat(32)
        desired.getJSONArray("items").put(JSONObject(desired.getJSONArray("items").getJSONObject(0).toString())
            .put("id", secondId).put("filePath", second.path))
        val committed = disk.commit(JSONObject(), desired, listOf(source.parentFile!!))
        val items = committed.getJSONArray("items")
        assertEquals(imageId, items.getJSONObject(0).getString("id"))
        assertEquals(secondId, items.getJSONObject(1).getString("id"))
        assertEquals("image1.png", File(items.getJSONObject(0).getString("filePath")).name)
        assertEquals("image2.png", File(items.getJSONObject(1).getString("filePath")).name)
        assertArrayEquals(source.readBytes(), File(items.getJSONObject(0).getString("filePath")).readBytes())
        assertArrayEquals(second.readBytes(), File(items.getJSONObject(1).getString("filePath")).readBytes())
        assertEquals(committed.getJSONArray("items").toString(), disk.migrateNamesIfNeeded().getJSONArray("items").toString())
        assertEquals(2, root.walkTopDown().count { it.isFile && it.extension == "png" })
    }
    @Test fun movingFolderCommitsBeforeRemovingOldSharedOriginal() = fixture { root, source, disk ->
        val before = disk.commit(JSONObject(), catalog(source), listOf(source.parentFile!!))
        val old = File(before.getJSONArray("items").getJSONObject(0).getString("filePath"))
        val after = disk.commit(before, catalog(old, "Omyaru Browser/책장2"), listOf(source.parentFile!!))
        val moved = File(after.getJSONArray("items").getJSONObject(0).getString("filePath"))
        assertTrue(moved.isFile); assertFalse(old.exists())
        assertArrayEquals(source.readBytes(), moved.readBytes())
        assertEquals(1, root.walkTopDown().count { it.isFile && it.extension == "png" })
    }
    @Test fun unavailableOriginalDoesNotCommitDeletionOrOverwriteCatalog() = fixture { _, source, disk ->
        val before = disk.commit(JSONObject(), catalog(source), listOf(source.parentFile!!))
        val invalid = JSONObject(before.toString())
        invalid.getJSONArray("items").getJSONObject(0).put("filePath", File(source.parentFile, "missing.png").path)
        assertThrows(IllegalStateException::class.java) { disk.commit(before, invalid, listOf(source.parentFile!!)) }
        assertEquals(before.toString(), disk.read().toString())
    }
    @Test fun twoEditorsKeepIndependentMetadataChanges() = fixture { _, source, disk ->
        val base = disk.commit(JSONObject(), catalog(source), listOf(source.parentFile!!))
        val one = JSONObject(base.toString()).apply { getJSONObject("folderNotes").put("Umtomi/책장1", "from browser") }
        val two = JSONObject(base.toString()).apply { getJSONObject("folderColumnCounts").put("Umtomi/책장1", 6) }
        disk.commit(base, one, listOf(source.parentFile!!))
        val result = disk.commit(base, two, listOf(source.parentFile!!))
        assertEquals("from browser", result.getJSONObject("folderNotes").getString("Umtomi/책장1"))
        assertEquals(6, result.getJSONObject("folderColumnCounts").getInt("Umtomi/책장1"))
    }
    @Test fun interruptedCommitRecoversBeforeNextRead() = fixture { root, source, disk ->
        val before = disk.commit(JSONObject(), catalog(source), listOf(source.parentFile!!))
        val old = File(before.getJSONArray("items").getJSONObject(0).getString("filePath"))
        val destination = File(root, "Umtomi/recovered/$imageId.png")
        destination.parentFile!!.mkdirs(); old.copyTo(destination)
        val after = catalog(destination, "Umtomi/recovered")
        val checksum = java.security.MessageDigest.getInstance("SHA-256").digest(old.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
        File(root, ".omyaru/pending.json").writeText(JSONObject().put("catalog", after).put("cleanup", JSONArray().put(
            JSONObject().put("path", old.path).put("sha256", checksum))).toString())
        val recovered = BookshelfFileTransaction(root).read()
        assertEquals("Umtomi/recovered", recovered.getJSONArray("folders").getString(0))
        assertTrue(destination.isFile); assertFalse(old.exists())
        assertFalse(File(root, ".omyaru/pending.json").exists())
    }

}
