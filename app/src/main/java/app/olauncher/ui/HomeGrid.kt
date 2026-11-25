package app.olauncher.ui

import kotlin.math.roundToInt

internal const val HOME_GRID_COLUMNS = 14
internal const val MAX_WIDGET_SPAN = 14

internal data class GridCell(val column: Int, val row: Int, val columns: Int, val rows: Int) {
    fun overlaps(other: GridCell): Boolean =
        column < other.column + other.columns && other.column < column + columns &&
            row < other.row + other.rows && other.row < row + rows

    fun fits(width: Int, occupied: Collection<GridCell>) =
        column >= 0 && row >= 0 && columns > 0 && rows > 0 && column + columns <= width &&
            occupied.none(::overlaps)

    fun encode() = "$column,$row,$columns,$rows"
}

internal fun firstGridCell(columns: Int, rows: Int, width: Int, occupied: Collection<GridCell>): GridCell {
    val lastRow = occupied.maxOfOrNull { it.row + it.rows } ?: 0
    for (row in 0..lastRow) for (column in 0..width - columns) {
        val candidate = GridCell(column, row, columns, rows)
        if (candidate.fits(width, occupied)) return candidate
    }
    return GridCell(0, lastRow, columns, rows)
}

internal enum class ResizeHandle(val horizontal: Int, val vertical: Int) {
    LEFT(-1, 0), TOP(0, -1), RIGHT(1, 0), BOTTOM(0, 1),
    TOP_LEFT(-1, -1), TOP_RIGHT(1, -1), BOTTOM_LEFT(-1, 1), BOTTOM_RIGHT(1, 1),
}

/** Snap the dragged edges to grid cells and keep the opposite edges anchored. */
internal fun GridCell.resizeFrom(handle: ResizeHandle, dx: Int, dy: Int): GridCell {
    var x1 = column
    var x2 = column + columns
    var y1 = row
    var y2 = row + rows
    when (handle.horizontal) {
        -1 -> x1 = (x1 + dx).coerceIn((x2 - MAX_WIDGET_SPAN).coerceAtLeast(0), x2 - 1)
        1 -> x2 = (x2 + dx).coerceIn(x1 + 1, minOf(HOME_GRID_COLUMNS, x1 + MAX_WIDGET_SPAN))
    }
    when (handle.vertical) {
        -1 -> y1 = (y1 + dy).coerceIn((y2 - MAX_WIDGET_SPAN).coerceAtLeast(0), y2 - 1)
        1 -> y2 = (y2 + dy).coerceIn(y1 + 1, y1 + MAX_WIDGET_SPAN)
    }
    return GridCell(x1, y1, x2 - x1, y2 - y1)
}

/** Convert legacy cell boundaries (4/6 columns, ~48dp rows) without accumulating rounding gaps. */
internal fun GridCell.onFineGrid(legacyColumns: Int, isWidget: Boolean): GridCell {
    fun mapColumn(x: Int) = (x.toFloat() * HOME_GRID_COLUMNS / legacyColumns).roundToInt()
    val start = mapColumn(column)
    val end = mapColumn(column + columns)
    val height = if (isWidget) (rows * 2).coerceAtMost(MAX_WIDGET_SPAN) else rows * 2
    return GridCell(start, row * 2, end - start, height)
}
