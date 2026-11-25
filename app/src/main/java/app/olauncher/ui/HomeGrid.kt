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
