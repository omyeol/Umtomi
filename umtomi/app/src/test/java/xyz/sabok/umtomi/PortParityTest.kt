package xyz.sabok.umtomi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.sabok.umtomi.hitomi.Artist
import xyz.sabok.umtomi.hitomi.Character
import xyz.sabok.umtomi.hitomi.GalleryInfo
import xyz.sabok.umtomi.hitomi.Group
import xyz.sabok.umtomi.hitomi.Parody
import xyz.sabok.umtomi.hitomi.Tag
import xyz.sabok.umtomi.ui.RecommendationEngine
import xyz.sabok.umtomi.ui.WorkspaceTab
import xyz.sabok.umtomi.ui.WorkspaceTabConfig
import xyz.sabok.umtomi.util.LastReadPages

class PortParityTest {
    @Test
    fun readerLoadOrderExpandsAroundAnchor() {
        assertEquals(emptyList<Int>(), LastReadPages.readerLoadOrder(0, 0))
        assertEquals(listOf(3, 2, 4, 1, 5, 0), LastReadPages.readerLoadOrder(6, 3))
        assertEquals(listOf(0, 1, 2, 3), LastReadPages.readerLoadOrder(4, -5))
        assertEquals(listOf(3, 2, 1, 0), LastReadPages.readerLoadOrder(4, 99))
    }

    @Test
    fun workspaceTabConfigPreservesAllTabsAndBarMinimum() {
        val sanitized = WorkspaceTabConfig.sanitizeOrder(listOf("BOOKSHELF", "INVALID", "SEARCH", "BOOKSHELF"))
        assertEquals(WorkspaceTab.entries.size, sanitized.size)
        assertEquals(WorkspaceTab.BOOKSHELF, sanitized[0])
        assertEquals(WorkspaceTab.SEARCH, sanitized[1])

        val allNames = WorkspaceTab.entries.map { it.name }.toSet()
        val sanitizedMore = WorkspaceTabConfig.sanitizeMore(sanitized, allNames)
        assertEquals(WorkspaceTab.entries.size - 1, sanitizedMore.size)

        val visible = WorkspaceTabConfig.visibleBarTabs(sanitized, moreEnabled = true, inMore = sanitizedMore)
        assertEquals(1, visible.size)

        val reordered = WorkspaceTabConfig.moveGroupOrder(
            listOf(WorkspaceTab.SEARCH, WorkspaceTab.RECOMMENDATIONS, WorkspaceTab.DOWNLOADS, WorkspaceTab.HISTORY, WorkspaceTab.FAVORITES, WorkspaceTab.BOOKSHELF),
            listOf(WorkspaceTab.FAVORITES, WorkspaceTab.SEARCH, WorkspaceTab.BOOKSHELF)
        )
        assertEquals(
            listOf(WorkspaceTab.FAVORITES, WorkspaceTab.RECOMMENDATIONS, WorkspaceTab.DOWNLOADS, WorkspaceTab.HISTORY, WorkspaceTab.SEARCH, WorkspaceTab.BOOKSHELF),
            reordered
        )
    }

    @Test
    fun recommendationEngineFiltersAndScoresMetadata() {
        val sample = GalleryInfo(
            id = "100",
            title = "Sample Work",
            language = "korean",
            type = "doujinshi",
            date = "2026-01-01",
            artists = listOf(Artist("Artist One", "/artist/one")),
            groups = listOf(Group("Circle A", "/group/a")),
            parodys = listOf(Parody("Series X", "/series/x")),
            characters = listOf(Character("Heroine", "/char/heroine")),
            tags = listOf(Tag("glasses", "/tag/glasses", female = "1"), Tag("sole_male", "/tag/sole_male", male = "1")),
        )

        assertTrue(RecommendationEngine.matchesPersistentTagFilters(sample, "language:korean, female:glasses", "male:yaoi"))
        assertFalse(RecommendationEngine.matchesPersistentTagFilters(sample, "language:korean", "female:glasses"))
        assertFalse(RecommendationEngine.matchesPersistentTagFilters(sample, "artist:other", ""))
        assertTrue(RecommendationEngine.matchesPersistentTagFilters(sample, "series:series_x，artist:artist_one", ""))

        val profile = RecommendationEngine.Profile(listOf(sample))
        assertEquals("korean", profile.topLanguage)
        assertTrue(RecommendationEngine.similarityScore(sample, profile) > 100.0)
        assertTrue(RecommendationEngine.searchSeeds(profile).any { it.query == "artist:artist_one" })
    }
}
