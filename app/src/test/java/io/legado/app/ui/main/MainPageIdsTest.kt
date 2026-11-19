package io.legado.app.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainPageIdsTest {
    @Test
    fun optionalTabsKeepStableIdentitiesAndOrder() {
        for (discovery in listOf(false, true)) {
            for (rss in listOf(false, true)) {
                val pages = MainPageIds.visible(discovery, rss).toList()
                assertTrue(MainPageIds.BOOKSHELF in pages)
                assertEquals(MainPageIds.MY, pages.last())
                assertEquals(discovery, MainPageIds.EXPLORE in pages)
                assertEquals(rss, MainPageIds.RSS in pages)
                assertEquals(pages.size, pages.distinct().size)
                assertTrue(pages.size in 2..4)
                assertEquals(
                    listOf(0, 1, 2, 3).filter { it in pages },
                    pages
                )
            }
        }
    }

    @Test
    fun existingPageIdsRemainStable() {
        assertEquals(0, MainPageIds.BOOKSHELF)
        assertEquals(1, MainPageIds.EXPLORE)
        assertEquals(2, MainPageIds.RSS)
        assertEquals(3, MainPageIds.MY)
    }

    @Test
    fun explicitDefaultPagesResolveToVisiblePageOrBookshelf() {
        val keys = mapOf(
            "home" to MainPageIds.BOOKSHELF,
            "bookshelf" to MainPageIds.BOOKSHELF,
            "explore" to MainPageIds.EXPLORE,
            "rss" to MainPageIds.RSS,
            "my" to MainPageIds.MY
        )
        for (discovery in listOf(false, true)) {
            for (rss in listOf(false, true)) {
                val pages = MainPageIds.visible(discovery, rss)
                keys.forEach { (key, pageId) ->
                    val position = MainPageIds.defaultPosition(key, pages)
                    assertTrue(position in pages.indices)
                    if (pageId in pages) {
                        assertEquals(pageId, pages[position])
                    } else {
                        assertEquals(0, position)
                    }
                }
                assertEquals(0, MainPageIds.defaultPosition(null, pages))
                assertEquals(0, MainPageIds.defaultPosition("unknown", pages))
            }
        }
    }

    @Test
    fun hiddenDiscoveryAndRssFallbackToBookshelf() {
        val pages = MainPageIds.visible(false, false)
        assertEquals(0, MainPageIds.defaultPosition("explore", pages))
        assertEquals(0, MainPageIds.defaultPosition("rss", pages))
        assertEquals(MainPageIds.BOOKSHELF, pages.first())
    }

    @Test
    fun currentPageIdentitySurvivesEveryVisibleTabChange() {
        val variants = listOf(false, true).flatMap { discovery ->
            listOf(false, true).map { rss ->
                MainPageIds.visible(discovery, rss)
            }
        }
        for (previousPages in variants) {
            for (pages in variants) {
                for (previousPageId in previousPages) {
                    val position = MainPageIds.preservePosition(previousPageId, pages)
                    assertTrue(position in pages.indices)
                    assertEquals(
                        if (previousPageId in pages) previousPageId else MainPageIds.BOOKSHELF,
                        pages[position]
                    )
                }
            }
        }
    }

    @Test
    fun missingCurrentPageFallsBackToBookshelf() {
        val pages = MainPageIds.visible(false, false)
        assertEquals(
            MainPageIds.BOOKSHELF,
            pages[MainPageIds.preservePosition(null, pages)]
        )
        assertEquals(
            MainPageIds.BOOKSHELF,
            pages[MainPageIds.preservePosition(99, pages)]
        )
    }
}
