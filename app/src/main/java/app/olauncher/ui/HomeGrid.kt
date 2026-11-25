package app.olauncher.ui

internal data class GridCell(val column: Int, val row: Int, val columns: Int, val rows: Int) {
    fun overlaps(other: GridCell): Boolean =
        column < other.column + other.columns && other.column < column + columns &&
            row < other.row + other.rows && other.row < row + rows

    fun fits(width: Int, occupied: Collection<GridCell>) =
        column >= 0 && row >= 0 && columns > 0 && rows > 0 && column + columns <= width &&
            occupied.none(::overlaps)
}

internal fun firstGridCell(columns: Int, rows: Int, width: Int, occupied: Collection<GridCell>): GridCell {
    val lastRow = occupied.maxOfOrNull { it.row + it.rows } ?: 0
    for (row in 0..lastRow) for (column in 0..width - columns) {
        val candidate = GridCell(column, row, columns, rows)
        if (candidate.fits(width, occupied)) return candidate
    }
    return GridCell(0, lastRow, columns, rows)
}

/** Resize in place when possible, or extend toward the other edge while keeping the old footprint anchored. */
internal fun GridCell.resizeTo(newWidth: Int, newHeight: Int, gridWidth: Int, occupied: Collection<GridCell>): GridCell? {
    val firstColumn = if (newWidth > columns) column + columns - newWidth else column
    val firstRow = if (newHeight > rows) row + rows - newHeight else row
    for (y in row downTo firstRow) for (x in column downTo firstColumn) {
        val candidate = GridCell(x, y, newWidth, newHeight)
        if (candidate.fits(gridWidth, occupied)) return candidate
    }
    return null
}
