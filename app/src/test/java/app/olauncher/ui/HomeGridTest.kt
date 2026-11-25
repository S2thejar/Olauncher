package app.olauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeGridTest {
    @Test fun adjacentItemsDoNotOverlap() {
        val first = GridCell(0, 0, 2, 2)
        assertTrue(GridCell(2, 0, 2, 2).fits(4, listOf(first)))
        assertTrue(GridCell(0, 2, 2, 2).fits(4, listOf(first)))
        assertFalse(GridCell(1, 1, 2, 2).fits(4, listOf(first)))
    }

    @Test fun resizeCannotCrossGridEdgeOrAnotherItem() {
        val widget = GridCell(2, 1, 2, 2)
        assertFalse(widget.copy(columns = 3).fits(4, emptyList()))
        assertFalse(widget.copy(rows = 3).fits(4, listOf(GridCell(2, 3, 1, 1))))
        assertTrue(widget.copy(rows = 1).fits(4, listOf(GridCell(2, 3, 1, 1))))
    }

    @Test fun placementFillsTheEarliestGapAndCanExtendVertically() {
        val occupied = listOf(GridCell(0, 0, 2, 2), GridCell(2, 0, 2, 1))
        assertEquals(GridCell(2, 1, 2, 1), firstGridCell(2, 1, 4, occupied))
        assertEquals(GridCell(0, 2, 4, 2), firstGridCell(4, 2, 4, occupied))
    }
}
