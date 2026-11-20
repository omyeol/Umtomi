package xyz.sabok.umtomi.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SharedBookshelfCatalogTest {
    private val a = "a".repeat(32)
    private val b = "b".repeat(32)
    private fun shelf(name: String = "Umtomi/책장1") = JSONObject()
        .put("folders", JSONArray(listOf(name))).put("folderIds", JSONObject().put(name, a))
        .put("folderNotes", JSONObject().put(name, "old"))
        .put("folderColumnCounts", JSONObject().put(name, 2)).put("items", JSONArray())
        .put("activeFolder", name)
    @Test fun concurrentRenameAndNoteDoNotOverwriteEachOther() {
        val base = shelf()
        val renamed = shelf("Omyaru Browser/책장1")
        val latest = shelf().apply { getJSONObject("folderNotes").put("Umtomi/책장1", "new memo") }
        val merged = SharedBookshelfCatalog.merge(base, renamed, latest)
        assertEquals(a, merged.getJSONObject("folderIds").getString("Omyaru Browser/책장1"))
        assertEquals("new memo", merged.getJSONObject("folderNotes").getString("Omyaru Browser/책장1"))
    }
    @Test fun staleWriterCannotResurrectDeletedFolder() {
        val base = shelf()
        val deleted = SharedBookshelfCatalog.merge(base, JSONObject(), base)
        val merged = SharedBookshelfCatalog.merge(base, shelf("Umtomi/renamed"), deleted)
        assertEquals(0, merged.getJSONArray("folders").length())
        assertEquals(a, merged.getJSONArray("deletedFolderIds").getString(0))
    }
    @Test fun concurrentCreationSurvivesAndSameNamesRemainDistinct() {
        val first = shelf()
        val second = shelf().apply { getJSONObject("folderIds").put("Umtomi/책장1", b) }
        val merged = SharedBookshelfCatalog.merge(JSONObject(), second, first)
        assertEquals(2, merged.getJSONArray("folders").length())
        assertEquals(2, merged.getJSONObject("folderIds").length())
    }
    @Test fun folderOrderSurvivesSharedCatalogMerge() {
        val first = "Omyaru Browser/동아리"; val second = "Omyaru Browser/얌전한 고양이"
        val base = shelf(first).apply {
            getJSONArray("folders").put(second)
            getJSONObject("folderIds").put(second, b)
        }
        val edited = JSONObject(base.toString()).put("folders", JSONArray(listOf(second, first)))
        val merged = SharedBookshelfCatalog.merge(base, edited, base)
        assertEquals(listOf(second, first), (0 until merged.getJSONArray("folders").length()).map { merged.getJSONArray("folders").getString(it) })
    }
    @Test fun pathsRejectTraversalAndWindowsReservedNames() {
        listOf("../a", "/a", "a//b", "a/../b", "a\\b", "a/CON", "a/NUL.jpg", "a/ trailing", "a/end.").forEach {
            assertNull(it, BookshelfPath.normalize(it))
        }
        assertEquals("Omyaru Browser/책장1", BookshelfPath.normalize("Omyaru Browser/책장1"))
    }
    @Test fun subtreeMovePreservesDescendantsAndRejectsCyclesAndConflicts() {
        val folders = listOf("A", "A/one", "A/one/nested", "B", "B/other")
        assertEquals("B/moved/one/nested", BookshelfPath.relocation(folders, "A", "B/moved")?.get("A/one/nested"))
        assertNull(BookshelfPath.relocation(folders, "A", "A/one/moved"))
        assertNull(BookshelfPath.relocation(folders, "A", "B"))
        assertEquals(listOf("A/one"), BookshelfPath.children(folders, "A"))
    }
}
