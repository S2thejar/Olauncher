package app.olauncher.helper

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PhotoWallpapersTest {
    @Test fun shuffleTriesOtherPhotosBeforeTheLastOne() {
        val selected = setOf("last", "different", "new")
        val result = photoCandidates(selected, "last", Random(5))
        assertEquals(selected, result.toSet())
        assertEquals(selected.size, result.size)
        assertNotEquals("last", result.first())
        assertEquals("last", result.last())
    }

    @Test fun fallsBackToTheOnlyPhoto() {
        assertEquals(listOf("last"), photoCandidates(setOf("last"), "last"))
    }

    @Test fun doesNotReintroduceAnOldUnselectedPhoto() {
        assertEquals(listOf("new"), photoCandidates(setOf("new"), "old"))
        assertEquals(emptyList<String>(), photoCandidates(emptySet(), "old"))
    }
}
