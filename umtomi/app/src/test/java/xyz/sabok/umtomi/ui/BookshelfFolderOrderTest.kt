package xyz.sabok.umtomi.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BookshelfFolderOrderTest {
    @Test fun parentShowsChildrenInCreationOrderAndMoveKeepsSubtree() {
        val folders = listOf("Omyaru Browser/동아리", "Omyaru Browser/얌전한 고양이",
            "Omyaru Browser/이세계 밀프헌터", "Omyaru Browser/동아리/1화")
        assertEquals(listOf("Omyaru Browser/동아리", "Omyaru Browser/얌전한 고양이",
            "Omyaru Browser/이세계 밀프헌터"), BookshelfPath.children(folders, "Omyaru Browser"))
        val moved = BookshelfPath.moveSibling(folders, "Omyaru Browser/동아리", 1)!!
        assertEquals(listOf("Omyaru Browser/얌전한 고양이", "Omyaru Browser/동아리",
            "Omyaru Browser/동아리/1화", "Omyaru Browser/이세계 밀프헌터"), moved)
    }
}
