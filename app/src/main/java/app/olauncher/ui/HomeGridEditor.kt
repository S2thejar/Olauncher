package app.olauncher.ui

import android.appwidget.AppWidgetProviderInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.edit
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.helper.dpToPx
import kotlin.math.ceil
import kotlin.math.roundToInt

/** An opt-in grid; stock list and its preferences remain untouched until the first edit. */
internal class HomeGridEditor(private val root: HomePositioningLayout) {
    private val context = root.context
    private val prefs = context.getSharedPreferences("home_grid", 0)
    val enabled get() = prefs.getBoolean("enabled", false)
    var editing = false
        private set
    private data class Item(val key: String, val view: View, val widget: AppWidgetProviderInfo?)
    private data class Limits(val minW: Int, val maxW: Int, val minH: Int, val maxH: Int)
    private data class Drag(val item: Item, val cell: GridCell, val x: Float, val y: Float, val resize: Boolean)
    private var items = emptyList<Item>()
    private val cells = linkedMapOf<Item, GridCell>()
    private val limits = mutableMapOf<Item, Limits>()
    private val landscape get() = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private val columns get() = if (landscape) 6 else 4
    private val prefix get() = if (landscape) "land_" else "port_"
    private val top get() = root.paddingTop + 48.dpToPx()
    private val left get() = root.paddingLeft + 16.dpToPx()
    private var cellWidth = 1f
    private var cellHeight = 1f
    private var visibleRows = 1
    private var offset = 0
    private var maxOffset = 0
    private var selectedKey: String? = null
    private var drag: Drag? = null
    private var preview: Pair<GridCell, Boolean>? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var onRemoveWidget: ((Int) -> Unit)? = null
    var onEditChanged: ((Boolean) -> Unit)? = null
    val widgetViews = linkedMapOf<Int, HomeWidgetSlot>()

    private fun button(label: Int, action: () -> Unit) = Button(context).apply {
        setText(label)
        isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
        minimumWidth = 0
        minWidth = 0
        setPadding(4.dpToPx(), 0, 4.dpToPx(), 0)
        setOnClickListener { action() }
    }
    private val previous = button(R.string.personal_grid_up) { scroll(-1) }
    private val next = button(R.string.personal_grid_down) { scroll(1) }
    private val remove = button(R.string.personal_widget_remove_short) {
        selectedKey?.removePrefix("widget_")?.toIntOrNull()?.let { onRemoveWidget?.invoke(it) }
    }
    private val done = button(R.string.personal_grid_done) { finish() }
    private val footer = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        listOf(previous, next, remove, done).forEach { addView(it) }
        visibility = View.GONE
        root.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 48.dpToPx(),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 16.dpToPx() })
    }

    fun bind(clock: View, labels: List<View>) {
        if (!enabled) return
        root.layoutTransition = null
        val current = listOf(Item("clock", clock, null)) +
            labels.mapIndexed { index, view -> Item("app_$index", view, null) } +
            widgetViews.map { (id, view) -> Item("widget_$id", view, view.provider) }
        current.forEach { (_, view) ->
            if (view.parent != root) {
                (view.parent as? ViewGroup)?.removeView(view)
                root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        items = current
        footer.bringToFront()
        root.requestLayout()
    }

    fun enable() { prefs.edit { putBoolean("enabled", true) } }

    fun start(widgetId: Int? = null, bindGrid: () -> Unit) {
        enable()
        editing = true
        bindGrid()
        selectedKey = widgetId?.let { "widget_$it" }
        updateFooter()
        onEditChanged?.invoke(true)
        root.requestLayout()
    }

    fun finish() {
        if (!editing) return
        editing = false
        selectedKey = null
        drag = null
        preview = null
        updateFooter()
        onEditChanged?.invoke(false)
        root.requestLayout()
        root.invalidate()
    }

    fun forgetWidget(id: Int) {
        prefs.edit { remove("port_widget_$id"); remove("land_widget_$id") }
        if (selectedKey == "widget_$id") selectedKey = null
    }

    private fun stored(item: Item): GridCell? {
        val parts = prefs.getString(prefix + item.key, null)?.split(',')?.mapNotNull(String::toIntOrNull) ?: return null
        if (parts.size != 4) return null
        return GridCell(parts[0], parts[1], parts[2], parts[3])
    }

    fun measure(width: Int, height: Int) {
        val usableHeight = (height - top - root.paddingBottom - 72.dpToPx()).coerceAtLeast(48.dpToPx())
        visibleRows = (usableHeight / 48.dpToPx()).coerceAtLeast(1)
        cellHeight = usableHeight.toFloat() / visibleRows
        cellWidth = (width - left - root.paddingRight - 16.dpToPx()).coerceAtLeast(columns).toFloat() / columns
        cells.clear()
        limits.clear()
        val visible = items.filter { it.view.visibility != View.GONE }
        fun span(px: Int, size: Float, max: Int) = ceil(px / size).toInt().coerceIn(1, max)
        visible.forEach { item ->
            val view = item.view
            val provider = item.widget
            limits[item] = if (provider == null) {
                view.measure(View.MeasureSpec.makeMeasureSpec((columns * cellWidth).toInt(), View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                Limits(span(view.measuredWidth + 1, cellWidth, columns), columns, span(view.measuredHeight, cellHeight, visibleRows), visibleRows)
            } else {
                val horizontal = provider.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
                val vertical = provider.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
                val defaultW = span(provider.minWidth, cellWidth, columns)
                val defaultH = span(provider.minHeight, cellHeight, visibleRows)
                val minW = if (horizontal && provider.minResizeWidth > 0) span(provider.minResizeWidth, cellWidth, columns) else defaultW
                val minH = if (vertical && provider.minResizeHeight > 0) span(provider.minResizeHeight, cellHeight, visibleRows) else defaultH
                val maxW = if (!horizontal) defaultW else if (Build.VERSION.SDK_INT >= 31 && provider.maxResizeWidth > 0)
                    (provider.maxResizeWidth / cellWidth).toInt().coerceIn(minW, columns) else columns
                val maxH = if (!vertical) defaultH else if (Build.VERSION.SDK_INT >= 31 && provider.maxResizeHeight > 0)
                    (provider.maxResizeHeight / cellHeight).toInt().coerceIn(minH, visibleRows) else visibleRows
                Limits(minW, maxW, minH, maxH)
            }
        }
        // Honor saved placements before moving unsaved items out of their way.
        visible.forEach { item ->
            val cell = stored(item) ?: return@forEach
            val limit = limits.getValue(item)
            val w = if (item.widget == null) limit.minW else cell.columns.coerceIn(limit.minW, limit.maxW)
            val h = if (item.widget == null) limit.minH else cell.rows.coerceIn(limit.minH, limit.maxH)
            val candidate = GridCell(cell.column.coerceIn(0, columns - w), cell.row, w, h)
            if (candidate.fits(columns, cells.values)) cells[item] = candidate
        }
        visible.sortedBy { when { it.key == "clock" -> 0; it.widget != null -> 1; else -> 2 } }.forEach { item ->
            if (item in cells) return@forEach
            val limit = limits.getValue(item)
            val provider = item.widget
            val w = provider?.let { span(it.minWidth, cellWidth, columns).coerceIn(limit.minW, limit.maxW) } ?: limit.minW
            val h = provider?.let { span(it.minHeight, cellHeight, visibleRows).coerceIn(limit.minH, limit.maxH) } ?: limit.minH
            cells[item] = firstGridCell(w, h, columns, cells.values)
        }
        maxOffset = ((cells.values.maxOfOrNull { it.row + it.rows } ?: 0) - visibleRows + if (editing) 2 else 0).coerceAtLeast(0)
        offset = offset.coerceIn(0, maxOffset)
        cells.forEach { (item, cell) ->
            val bounds = bounds(cell)
            val exact = View.MeasureSpec.EXACTLY
            item.view.measure(View.MeasureSpec.makeMeasureSpec(bounds.width(), exact),
                View.MeasureSpec.makeMeasureSpec(bounds.height(), if (item.widget != null) exact else View.MeasureSpec.AT_MOST))
        }
        updateFooter()
        footer.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(48.dpToPx(), View.MeasureSpec.EXACTLY))
    }

    fun layout() {
        cells.forEach { (item, cell) ->
            val b = bounds(cell)
            val y = b.top + (b.height() - item.view.measuredHeight) / 2
            item.view.layout(b.left, y, b.right, y + item.view.measuredHeight)
        }
        val x = (root.width - footer.measuredWidth) / 2
        val y = root.height - root.paddingBottom - 64.dpToPx()
        footer.layout(x, y, x + footer.measuredWidth, y + footer.measuredHeight)
    }

    private fun bounds(cell: GridCell) = Rect(
        left + (cell.column * cellWidth).roundToInt(), top + ((cell.row - offset) * cellHeight).roundToInt(),
        left + ((cell.column + cell.columns) * cellWidth).roundToInt(), top + ((cell.row - offset + cell.rows) * cellHeight).roundToInt(),
    )

    fun clip(canvas: Canvas) {
        canvas.clipRect(0, top, root.width, top + (visibleRows * cellHeight).roundToInt())
    }
    fun isItem(view: View) = items.any { it.view === view }
    fun outsideGrid(x: Float, y: Float) = x < left || x >= left + columns * cellWidth || y < top || y >= top + visibleRows * cellHeight
    fun blocksClippedTap(x: Float, y: Float): Boolean = outsideGrid(x, y) && !isFooter(x, y) &&
        cells.values.any { bounds(it).contains(x.toInt(), y.toInt()) } &&
        listOf(R.id.tvScreenTime, R.id.setDefaultLauncher, R.id.firstRunTips).none { id ->
            val view = root.findViewById<View>(id)
            view.isVisible && x >= view.left && x < view.right && y >= view.top && y < view.bottom
        }
    fun isFooter(x: Float, y: Float) = footer.isVisible && x >= footer.left && x < footer.right && y >= footer.top && y < footer.bottom

    fun draw(canvas: Canvas) {
        if (!editing) return
        canvas.save()
        clip(canvas)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.dpToPx().toFloat()
        paint.color = 0x66ffffff
        for (row in 0 until visibleRows) for (column in 0 until columns) {
            val b = bounds(GridCell(column, row + offset, 1, 1))
            b.inset(2.dpToPx(), 2.dpToPx())
            canvas.drawRect(b, paint)
        }
        cells.forEach { (item, cell) ->
            val selected = item.key == selectedKey
            val target = if (selected) preview?.first ?: cell else cell
            val b = bounds(target)
            b.inset(3.dpToPx(), 3.dpToPx())
            paint.color = if (!selected) Color.WHITE else if (preview?.second == false) 0xffff7676.toInt() else 0xff83e9ff.toInt()
            paint.strokeWidth = if (selected) 3.dpToPx().toFloat() else 1.dpToPx().toFloat()
            canvas.drawRect(b, paint)
            if (selected && resizable(item)) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(b.right - 9.dpToPx().toFloat(), b.bottom - 9.dpToPx().toFloat(), 7.dpToPx().toFloat(), paint)
                paint.style = Paint.Style.STROKE
            }
        }
        canvas.restore()
    }

    private fun resizable(item: Item) = item.widget != null && limits[item]?.let { it.minW < it.maxW || it.minH < it.maxH } == true

    fun touch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (outsideGrid(event.x, event.y)) return true
                val hit = cells.entries.lastOrNull { bounds(it.value).contains(event.x.toInt(), event.y.toInt()) }
                if (hit != null) {
                    val b = bounds(hit.value)
                    val resize = hit.key.key == selectedKey && resizable(hit.key) &&
                        event.x >= b.right - 34.dpToPx() && event.y >= b.bottom - 34.dpToPx()
                    selectedKey = hit.key.key
                    drag = Drag(hit.key, hit.value, event.x, event.y, resize)
                } else {
                    val selected = cells.entries.firstOrNull { it.key.key == selectedKey }
                    if (selected != null) {
                        val (item, cell) = selected
                        val column = ((event.x - left) / cellWidth).toInt().coerceIn(0, columns - cell.columns)
                        val row = ((event.y - top) / cellHeight).toInt() + offset
                        val target = cell.copy(column = column, row = row)
                        if (canPlace(item, target)) save(item, target)
                    }
                }
                updateFooter()
            }
            MotionEvent.ACTION_MOVE -> drag?.let { d ->
                val dx = ((event.x - d.x) / cellWidth).roundToInt()
                val dy = ((event.y - d.y) / cellHeight).roundToInt()
                val cell = d.cell
                val limit = limits.getValue(d.item)
                val candidate = if (d.resize) cell.copy(
                    columns = (cell.columns + dx).coerceIn(limit.minW, minOf(limit.maxW, columns - cell.column)),
                    rows = (cell.rows + dy).coerceIn(limit.minH, limit.maxH),
                ) else cell.copy(column = (cell.column + dx).coerceIn(0, columns - cell.columns), row = (cell.row + dy).coerceIn(0, offset + visibleRows - 1))
                preview = candidate to canPlace(d.item, candidate)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val d = drag
                val target = preview
                if (event.actionMasked == MotionEvent.ACTION_UP && d != null && target?.second == true) save(d.item, target.first)
                drag = null
                preview = null
            }
        }
        root.invalidate()
        return true
    }

    private fun canPlace(item: Item, cell: GridCell) = cell.fits(columns, cells.filterKeys { it != item }.values)
    private fun save(item: Item, cell: GridCell) {
        if (cells[item] == cell) return
        // Freeze the existing positions too, so moving one item doesn't repack its neighbors.
        prefs.edit {
            cells.forEach { (view, current) ->
                val b = if (view == item) cell else current
                putString(prefix + view.key, "${b.column},${b.row},${b.columns},${b.rows}")
            }
        }
        root.requestLayout()
    }

    private fun scroll(direction: Int) {
        offset = (offset + direction * (visibleRows - 1).coerceAtLeast(1)).coerceIn(0, maxOffset)
        root.requestLayout()
    }

    private fun updateFooter() {
        previous.isVisible = maxOffset > 0
        previous.isEnabled = offset > 0
        next.isVisible = maxOffset > 0
        next.isEnabled = offset < maxOffset
        done.isVisible = editing
        remove.isVisible = editing && selectedKey?.startsWith("widget_") == true
        footer.isVisible = editing || maxOffset > 0
    }
}
