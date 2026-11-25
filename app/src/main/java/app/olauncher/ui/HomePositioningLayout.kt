package app.olauncher.ui

import android.animation.LayoutTransition
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import app.olauncher.R
import app.olauncher.data.HomeVertical
import app.olauncher.helper.dpToPx

/** Moves the actual home groups, keeping their hitboxes with their rendered positions. */
class HomePositioningLayout(context: Context, attrs: AttributeSet) : FrameLayout(context, attrs) {
    internal val grid by lazy { HomeGridEditor(this) }
    private var editTouch = false
    private var beganBeforeEditing = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (grid.enabled) grid.measure(measuredWidth, measuredHeight)
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (!grid.enabled || !grid.isItem(child)) return super.drawChild(canvas, child, drawingTime)
        val save = canvas.save()
        grid.clip(canvas)
        return super.drawChild(canvas, child, drawingTime).also { canvas.restoreToCount(save) }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (grid.enabled) grid.draw(canvas)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            beganBeforeEditing = !grid.editing
            editTouch = grid.editing && !grid.isFooter(event.x, event.y)
        }
        if (editTouch || (grid.editing && beganBeforeEditing)) {
            return grid.touch(event)
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        (grid.enabled && grid.blocksClippedTap(event.x, event.y)) || super.onInterceptTouchEvent(event)

    private var custom = false
    private var clockPosition = HomeVertical.DEFAULT
    private var appsPosition = HomeVertical.DEFAULT
    private data class StockApps(val height: Int, val top: Int, val bottom: Int)
    private var stockApps: StockApps? = null
    private var stockTransition: LayoutTransition? = null

    internal fun setPositions(enabled: Boolean, clock: HomeVertical, apps: HomeVertical) {
        val list = findViewById<View>(R.id.homeAppsLayout)
        val original = stockApps ?: StockApps(list.layoutParams.height, list.paddingTop, list.paddingBottom)
            .also { stockApps = it }
        if (custom != enabled) {
            if (enabled) {
                stockTransition = layoutTransition
                layoutTransition = null
            } else {
                layoutTransition = stockTransition
            }
            list.layoutParams = list.layoutParams.apply {
                height = if (enabled) LayoutParams.WRAP_CONTENT else original.height
            }
            list.setPadding(list.paddingLeft, if (enabled) 0 else original.top,
                list.paddingRight, if (enabled) 0 else original.bottom)
        }
        if (custom != enabled || clockPosition != clock || appsPosition != apps) {
            custom = enabled
            clockPosition = clock
            appsPosition = apps
            requestLayout()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (grid.enabled) { grid.layout(); return }
        if (!custom) return
        val clock = findViewById<View>(R.id.dateTimeLayout)
        val apps = findViewById<View>(R.id.homeAppsLayout)
        val gap = 12.dpToPx()
        val first = paddingTop + gap
        val last = (height - paddingBottom - 48.dpToPx()).coerceAtLeast(first)
        val clockHeight = if (clock.visibility == View.GONE) 0 else clock.measuredHeight
        val appHeight = apps.measuredHeight
        fun proposed(position: HomeVertical, childHeight: Int, defaultTop: Int): Int {
            val limit = (last - childHeight).coerceAtLeast(first)
            return when (position) {
                HomeVertical.DEFAULT -> defaultTop
                HomeVertical.TOP -> first
                HomeVertical.MIDDLE -> first + (last - first - childHeight) / 2
                HomeVertical.BOTTOM -> limit
            }.coerceIn(first, limit)
        }
        fun move(child: View, y: Int, h: Int) {
            if (child.height == h) child.offsetTopAndBottom(y - child.top)
            else child.layout(child.left, y, child.right, y + h)
        }
        var clockTop = proposed(clockPosition, clockHeight, clock.top)
        var appsTop = proposed(appsPosition, appHeight, first + (last - first - appHeight) / 2)
        if (clockHeight > 0 && appHeight > 0 && clockTop < appsTop + appHeight + gap && appsTop < clockTop + clockHeight + gap) {
            if (clockTop <= appsTop) {
                clockTop = minOf(clockTop, (last - appHeight - gap - clockHeight).coerceAtLeast(first))
                appsTop = clockTop + clockHeight + gap
            } else {
                appsTop = minOf(appsTop, (last - clockHeight - gap - appHeight).coerceAtLeast(first))
                clockTop = appsTop + appHeight + gap
            }
        }
        if (clockHeight > 0) move(clock, clockTop, clockHeight)
        move(apps, appsTop, appHeight)
    }
}
