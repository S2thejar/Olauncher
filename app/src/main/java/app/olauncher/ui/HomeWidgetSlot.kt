package app.olauncher.ui

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout

/** Watches touches upstream of RemoteViews without replacing the provider's tap/scroll behavior. */
class HomeWidgetSlot @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    internal var provider: AppWidgetProviderInfo? = null
    internal var onHold: (() -> Unit)? = null
    internal var onSizeCommitted: ((Int, Int) -> Unit)? = null
    internal var previewingSize = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) requestLayout()
        }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!previewingSize && width > 0 && height > 0) onSizeCommitted?.invoke(width, height)
    }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var handled = false
    private val hold = Runnable {
        val edit = onHold ?: return@Runnable
        handled = true
        val cancel = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, downX, downY, 0)
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        edit()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handled = false
                downTime = event.downTime
                downX = event.x
                downY = event.y
                if (onHold != null) postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(event.x - downX) > slop || kotlin.math.abs(event.y - downY) > slop) removeCallbacks(hold)
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(hold)
        }
        return handled || super.dispatchTouchEvent(event) || onHold != null
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hold)
        super.onDetachedFromWindow()
    }
}
