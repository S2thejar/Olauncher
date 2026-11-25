package app.olauncher.ui

import android.appwidget.AppWidgetProviderInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.helper.dpToPx
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

/** An opt-in grid; stock list and its preferences remain untouched until the first edit. */
internal class HomeGridEditor(private val root: HomePositioningLayout) {
    private val context = root.context
    private val prefs = context.getSharedPreferences("home_grid", 0)
    val enabled get() = prefs.getBoolean("enabled", false)
    var editing = false
        private set
    private data class Item(val key: String, val view: View, val widget: AppWidgetProviderInfo?)
    private data class Span(val width: Int, val height: Int)
    private data class Drag(val item: Item, val cell: GridCell, val x: Float, val y: Float, val startOffset: Int,
        val handle: ResizeHandle?, var lastValid: GridCell = cell, var blocked: Boolean = false,
        var moved: Boolean = false)
    private data class Handle(val edge: ResizeHandle, val x: Float, val y: Float)
    private var items = emptyList<Item>()
    private val cells = linkedMapOf<Item, GridCell>()
    private val preferred = mutableMapOf<Item, Span>()
    private val landscape get() = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private val columns = HOME_GRID_COLUMNS
    private val legacyPrefix get() = if (landscape) "land_" else "port_"
    private val prefix get() = "fine_$legacyPrefix"
    private val top get() = root.paddingTop + 48.dpToPx()
    // The activity uses FLAG_LAYOUT_NO_LIMITS, so keep editor actions outside system navigation.
    private val footerBottom get() = maxOf(root.paddingBottom, 48.dpToPx(),
        ViewCompat.getRootWindowInsets(root)?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0)
    private val footerHeight get() = 48.dpToPx()
    private val left get() = root.paddingLeft + 16.dpToPx()
    private var cellWidth = 1f
    private var cellHeight = 1f
    private var visibleRows = 1
    private var offset = 0
    private var maxOffset = 0
    private var selectedKey: String? = null
    private var drag: Drag? = null
    private var revealSelection = false
    private var pointerX = 0f
    private var pointerY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var autoScrollPending = false
    private val autoScroll = object : Runnable {
        override fun run() {
            autoScrollPending = false
            val active = drag ?: return
            val step = edgeScrollDirection(pointerY, active)
            if (step == 0) return
            offset += step
            updateDrag(active, pointerX, pointerY)
            root.requestLayout()
            scheduleAutoScroll(active, 100)
        }
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var gestureExclusions = emptyList<Rect>()
    var onResizeTitle: ((View) -> Unit)? = null
    var onRemoveWidget: ((Int) -> Unit)? = null
    var onEditChanged: ((Boolean) -> Unit)? = null
    val widgetViews = linkedMapOf<Int, HomeWidgetSlot>()

    private fun button(label: Int, action: () -> Unit) = Button(context).apply {
        setText(label)
        isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
        minimumWidth = 40.dpToPx()
        minWidth = 40.dpToPx()
        setPadding(4.dpToPx(), 0, 4.dpToPx(), 0)
        maxLines = 1
        setOnClickListener { action() }
    }
    private val previous = button(R.string.personal_grid_up) { scroll(-1) }.apply {
        contentDescription = context.getString(R.string.personal_grid_previous_rows)
    }
    private val next = button(R.string.personal_grid_down) { scroll(1) }.apply {
        contentDescription = context.getString(R.string.personal_grid_next_rows)
    }
    private val remove = button(R.string.personal_widget_remove_short) {
        selectedKey?.removePrefix("widget_")?.toIntOrNull()?.let { onRemoveWidget?.invoke(it) }
    }
    private val resize = button(R.string.personal_resize) {
        val item = cells.keys.firstOrNull { it.key == selectedKey } ?: return@button
        if (item.widget == null) onResizeTitle?.invoke(item.view)
    }
    private val done = button(R.string.personal_grid_done) { finish() }
    private val footer = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        listOf(previous, next, remove, resize, done).forEach {
            addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        visibility = View.GONE
        root.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, footerHeight,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
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
        revealSelection = widgetId != null
        updateFooter()
        onEditChanged?.invoke(true)
        root.requestLayout()
    }

    fun finish() {
        if (!editing) return
        editing = false
        selectedKey = null
        revealSelection = false
        stopDrag(commit = false)
        updateFooter()
        onEditChanged?.invoke(false)
        root.requestLayout()
        root.invalidate()
    }

    fun forgetWidget(id: Int) {
        prefs.edit {
            listOf("port_", "land_", "fine_port_", "fine_land_").forEach { remove("${it}widget_$id") }
        }
        if (selectedKey == "widget_$id") selectedKey = null
    }

    private fun stored(item: Item): GridCell? {
        fun read(key: String): GridCell? {
            val parts = prefs.getString(key + item.key, null)?.split(',') ?: return null
            if (parts.size != 4) return null
            val (x, y, w, h) = parts.map { it.toIntOrNull() ?: return null }
            return GridCell(x, y, w, h)
        }
        return read(prefix) ?: read(legacyPrefix)?.onFineGrid(if (landscape) 6 else 4, item.widget != null)
    }

    fun measure(width: Int, height: Int) {
        val usableHeight = (height - top - footerBottom - footerHeight - 8.dpToPx()).coerceAtLeast(24.dpToPx())
        // Each old row is exactly two fine rows, preserving existing vertical spacing.
        visibleRows = ((usableHeight / 48.dpToPx()) * 2).coerceAtLeast(1)
        cellHeight = usableHeight.toFloat() / visibleRows
        cellWidth = (width - left - root.paddingRight - 16.dpToPx()).coerceAtLeast(columns).toFloat() / columns
        cells.clear()
        preferred.clear()
        val visible = items.filter { it.view.visibility != View.GONE }
        fun span(px: Int, size: Float, max: Int) = ceil(px / size).toInt().coerceIn(1, max)
        visible.forEach { item ->
            val view = item.view
            val provider = item.widget
            preferred[item] = if (provider == null) {
                view.measure(View.MeasureSpec.makeMeasureSpec((columns * cellWidth).toInt(), View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                Span(span(view.measuredWidth + 1, cellWidth, columns), span(view.measuredHeight, cellHeight, visibleRows))
            } else {
                // Provider recommendations determine the starting size, not the host resize range.
                Span(span(provider.minWidth, cellWidth, MAX_WIDGET_SPAN), span(provider.minHeight, cellHeight, MAX_WIDGET_SPAN))
            }
        }
        // Honor saved placements before moving unsaved items out of their way.
        visible.sortedBy { item ->
            val previous = stored(item)
            val size = preferred.getValue(item)
            // Let an enlarged label find a new spot rather than displacing unchanged neighbors.
            when {
                previous == null -> 2
                item.widget == null && (size.width > previous.columns || size.height > previous.rows) -> 1
                else -> 0
            }
        }.forEach { item ->
            val cell = stored(item) ?: return@forEach
            val size = preferred.getValue(item)
            val w = if (item.widget == null) size.width else cell.columns.coerceIn(1, MAX_WIDGET_SPAN)
            val h = if (item.widget == null) size.height else cell.rows.coerceIn(1, MAX_WIDGET_SPAN)
            val candidate = GridCell(cell.column.coerceIn(0, columns - w), cell.row, w, h)
            if (candidate.fits(columns, cells.values)) cells[item] = candidate
        }
        visible.sortedBy { when { it.key == "clock" -> 0; it.widget != null -> 1; else -> 2 } }.forEach { item ->
            if (item in cells) return@forEach
            val size = preferred.getValue(item)
            cells[item] = firstGridCell(size.width, size.height, columns, cells.values)
        }
        val contentEnd = cells.values.maxOfOrNull { it.row + it.rows } ?: 0
        maxOffset = (contentEnd - visibleRows).coerceAtLeast(0) + if (editing) 6 else 0
        if (drag == null) offset = offset.coerceIn(0, maxOffset)
        if (revealSelection) {
            selected()?.value?.let { cell ->
                if (cell.rows <= visibleRows) offset = offset.coerceIn((cell.row + cell.rows - visibleRows).coerceAtLeast(0), cell.row)
            }
            revealSelection = false
        }
        cells.forEach { (item, cell) ->
            val bounds = bounds(displayCell(item, cell))
            val exact = View.MeasureSpec.EXACTLY
            item.view.measure(View.MeasureSpec.makeMeasureSpec(bounds.width(), exact),
                View.MeasureSpec.makeMeasureSpec(bounds.height(), if (item.widget != null) exact else View.MeasureSpec.AT_MOST))
        }
        updateFooter()
        footer.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(footerHeight, View.MeasureSpec.EXACTLY))
    }

    fun layout() {
        cells.forEach { (item, cell) ->
            val b = bounds(displayCell(item, cell))
            val y = b.top + (b.height() - item.view.measuredHeight) / 2
            item.view.layout(b.left, y, b.right, y + item.view.measuredHeight)
        }
        val x = (root.width - footer.measuredWidth) / 2
        val y = root.height - footerBottom - footerHeight
        footer.layout(x, y, x + footer.measuredWidth, y + footer.measuredHeight)
        updateGestureExclusions()
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

    private fun selected() = cells.entries.firstOrNull { it.key.key == selectedKey }
    private fun displayCell(item: Item, cell: GridCell) = drag?.takeIf { it.item == item }?.lastValid ?: cell

    private fun handles(b: Rect): List<Handle> {
        val spacious = b.width() >= 56.dpToPx() && b.height() >= 56.dpToPx()
        return ResizeHandle.entries.filter { spacious || it.horizontal == 0 || it.vertical == 0 }.map { edge ->
            Handle(edge, when (edge.horizontal) { -1 -> b.left.toFloat(); 1 -> b.right.toFloat(); else -> b.exactCenterX() },
                when (edge.vertical) { -1 -> b.top.toFloat(); 1 -> b.bottom.toFloat(); else -> b.exactCenterY() })
        }.filter { it.y >= top && it.y <= viewportBottom }
    }

    private val viewportBottom get() = top + visibleRows * cellHeight

    fun draw(canvas: Canvas) {
        if (!editing) return
        canvas.save()
        clip(canvas)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.dpToPx().toFloat()
        paint.color = 0x35ffffff
        val gridBounds = bounds(GridCell(0, offset, columns, visibleRows))
        for (col in 0..columns) {
            val x = gridBounds.left + col * cellWidth
            canvas.drawLine(x, gridBounds.top.toFloat(), x, gridBounds.bottom.toFloat(), paint)
        }
        for (row in 0..visibleRows) {
            val y = gridBounds.top + row * cellHeight
            canvas.drawLine(gridBounds.left.toFloat(), y, gridBounds.right.toFloat(), y, paint)
        }
        cells.forEach { (item, cell) ->
            val selected = item.key == selectedKey
            val b = bounds(displayCell(item, cell))
            paint.color = if (selected) 0xcc15242b.toInt() else 0x80ffffff.toInt()
            paint.strokeWidth = (if (selected) 4 else 1).dpToPx().toFloat()
            canvas.drawRect(b, paint)
            if (selected) {
                val accent = if (drag?.blocked == true) 0xffffac9c.toInt() else 0xffa4eaff.toInt()
                paint.color = accent
                paint.strokeWidth = 2.dpToPx().toFloat()
                canvas.drawRect(b, paint)
                if (item.widget != null) handles(b).forEach { h ->
                    paint.style = Paint.Style.FILL
                    paint.color = 0xff15242b.toInt()
                    canvas.drawCircle(h.x, h.y, 7.dpToPx().toFloat(), paint)
                    paint.color = accent
                    canvas.drawCircle(h.x, h.y, 5.dpToPx().toFloat(), paint)
                    paint.style = Paint.Style.STROKE
                }
            }
        }
        canvas.restore()
    }

    /** Reserve only the resize handles that sit inside Android's side-back gesture areas. */
    private fun updateGestureExclusions() {
        val item = selected()?.takeIf { editing && it.key.widget != null }
        val insets = ViewCompat.getRootWindowInsets(root)?.getInsets(WindowInsetsCompat.Type.systemGestures())
        val region = 22.dpToPx()
        val next = if (item == null || insets == null) emptyList() else
            handles(bounds(displayCell(item.key, item.value))).mapNotNull { h ->
                val x = h.x.roundToInt()
                val y = h.y.roundToInt()
                if (x - region >= insets.left && x + region <= root.width - insets.right) null
                else Rect((x - region).coerceAtLeast(0), (y - region).coerceAtLeast(0),
                    (x + region).coerceAtMost(root.width), (y + region).coerceAtMost(root.height))
            }
        if (next != gestureExclusions) {
            gestureExclusions = next
            ViewCompat.setSystemGestureExclusionRects(root, next)
        }
    }

    private fun handleAt(cell: GridCell, x: Float, y: Float): ResizeHandle? {
        val b = bounds(cell)
        val closest = handles(b).minByOrNull { hypot(it.x - x, it.y - y) } ?: return null
        // On tiny widgets, leave the center available for moving the widget.
        val inCenter = b.contains(x.toInt(), y.toInt()) && abs(b.exactCenterX() - x) < b.width() * .2f &&
            abs(b.exactCenterY() - y) < b.height() * .2f
        return closest.edge.takeIf { !inCenter && hypot(closest.x - x, closest.y - y) <= 22.dpToPx() }
    }

    fun touch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                val current = selected()
                val handle = current?.takeIf { it.key.widget != null }?.let { handleAt(it.value, x, y) }
                val hit = if (handle != null) current else if (outsideGrid(x, y)) null else
                    cells.entries.lastOrNull { bounds(it.value).contains(x.toInt(), y.toInt()) }
                selectedKey = hit?.key?.key
                if (hit != null) {
                    drag = Drag(hit.key, hit.value, x, y, offset, handle)
                    if (handle != null) (hit.key.view as HomeWidgetSlot).previewingSize = true
                }
                updateFooter()
                updateGestureExclusions()
            }
            MotionEvent.ACTION_MOVE -> drag?.let { active ->
                pointerX = event.x
                pointerY = event.y
                updateDrag(active, pointerX, pointerY)
                scheduleAutoScroll(active, 120)
            }
            MotionEvent.ACTION_UP -> {
                drag?.let { updateDrag(it, event.x, event.y) }
                stopDrag(commit = true)
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> stopDrag(commit = false)
        }
        root.invalidate()
        return true
    }

    private fun scheduleAutoScroll(active: Drag, delayMs: Long) {
        if (edgeScrollDirection(pointerY, active) == 0) {
            root.removeCallbacks(autoScroll)
            autoScrollPending = false
        } else if (!autoScrollPending) {
            autoScrollPending = true
            root.postDelayed(autoScroll, delayMs)
        }
    }

    private fun edgeScrollDirection(y: Float, active: Drag): Int {
        if (!active.moved) return 0
        val edge = 20.dpToPx()
        val verticalResize = active.handle?.vertical
        val end = maxOf(maxOffset, active.cell.row + active.cell.rows + MAX_WIDGET_SPAN - visibleRows + 2)
        return when {
            y < top + edge && offset > 0 && (verticalResize == null || verticalResize == -1 && active.lastValid.rows < MAX_WIDGET_SPAN) -> -1
            y > viewportBottom - edge && offset < end && (verticalResize == null || verticalResize == 1 && active.lastValid.rows < MAX_WIDGET_SPAN) -> 1
            else -> 0
        }
    }

    private fun updateDrag(active: Drag, x: Float, y: Float) {
        if (!active.moved && hypot(x - active.x, y - active.y) <= touchSlop) return
        active.moved = true
        val dx = ((x - active.x) / cellWidth).roundToInt()
        val dy = ((y - active.y) / cellHeight).roundToInt() + offset - active.startOffset
        val old = active.cell
        val next = active.handle?.let { old.resizeFrom(it, dx, dy) } ?: old.copy(
            column = (old.column + dx).coerceIn(0, columns - old.columns), row = (old.row + dy).coerceAtLeast(0))
        active.blocked = !canPlace(active.item, next)
        if (!active.blocked && next != active.lastValid) {
            active.lastValid = next
            root.requestLayout()
        }
        root.invalidate()
    }

    private fun stopDrag(commit: Boolean) {
        root.removeCallbacks(autoScroll)
        autoScrollPending = false
        val active = drag ?: return
        drag = null
        (active.item.view as? HomeWidgetSlot)?.previewingSize = false
        if (commit && canPlace(active.item, active.lastValid) && active.lastValid != active.cell) {
            save(active.item, active.lastValid)
            root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        root.requestLayout()
    }

    private fun canPlace(item: Item, cell: GridCell) = cell.fits(columns, cells.filterKeys { it != item }.values)
    private fun save(item: Item, cell: GridCell) {
        if (cells[item] == cell) return
        // Freeze the existing positions too, so moving one item doesn't repack its neighbors.
        prefs.edit {
            cells.forEach { (view, current) ->
                val b = if (view == item) cell else current
                putString(prefix + view.key, b.encode())
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
        resize.isVisible = editing && selectedKey?.startsWith("app_") == true
        footer.isVisible = editing || maxOffset > 0
    }
}
