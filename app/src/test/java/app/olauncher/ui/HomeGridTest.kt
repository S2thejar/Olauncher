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

    @Test fun draggedCornerCanReachMinimumAndMaximum() {
        val widget = GridCell(0, 3, 7, 9)
        assertEquals(GridCell(0, 3, 1, 1), widget.resizeFrom(ResizeHandle.BOTTOM_RIGHT, -20, -20))
        assertEquals(GridCell(0, 3, 14, 14), widget.resizeFrom(ResizeHandle.BOTTOM_RIGHT, 20, 20))
    }

    @Test fun topLeftResizeKeepsBottomRightAnchored() {
        val widget = GridCell(4, 18, 8, 7)
        assertEquals(GridCell(0, 11, 12, 14), widget.resizeFrom(ResizeHandle.TOP_LEFT, -20, -20))
        assertEquals(GridCell(11, 24, 1, 1), widget.resizeFrom(ResizeHandle.TOP_LEFT, 20, 20))
    }

    @Test fun edgeDragOnlyChangesItsOwnAxisAndChecksNeighbors() {
        val widget = GridCell(7, 2, 5, 8)
        val grown = widget.resizeFrom(ResizeHandle.RIGHT, 20, 20)
        assertEquals(GridCell(7, 2, 7, 8), grown)
        assertFalse(grown.fits(HOME_GRID_COLUMNS, listOf(GridCell(12, 3, 2, 2))))
        assertTrue(grown.fits(HOME_GRID_COLUMNS, listOf(GridCell(12, 10, 2, 2))))
    }

    @Test fun migrationKeepsNeighborsAdjacentAndWidgetWithinRange() {
        val left = GridCell(0, 3, 1, 4).onFineGrid(4, false)
        val right = GridCell(1, 3, 3, 10).onFineGrid(4, true)
        assertEquals(GridCell(0, 6, 4, 8), left)
        assertEquals(GridCell(4, 6, 10, 14), right)
        assertTrue(right.fits(HOME_GRID_COLUMNS, listOf(left)))
        assertEquals(GridCell(7, 12, 7, 6), GridCell(3, 6, 3, 3).onFineGrid(6, true))
    }
}
