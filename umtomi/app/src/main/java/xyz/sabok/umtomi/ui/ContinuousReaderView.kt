package xyz.sabok.umtomi.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.roundToInt

/** Resize the whole strip, so every page has the same scale and remains adjacent. */
class ContinuousReaderView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : RecyclerView(context, attrs) {
    var continuousZoom = true
        set(value) { field = value; resetZoom() }
    var stripScale = 1f
        private set
    private var viewport = 0
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var captured = false
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector) = continuousZoom
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val manager = layoutManager as? LinearLayoutManager ?: return false
            val index = manager.findFirstVisibleItemPosition()
            val top = manager.findViewByPosition(index)?.top ?: 0
            val old = stripScale
            stripScale = (old * detector.scaleFactor).coerceIn(1f, 4f)
            val ratio = stripScale / old
            translationX = (translationX - detector.focusX * (ratio - 1)).coerceIn(-viewport * (stripScale - 1), 0f)
            requestLayout()
            manager.scrollToPositionWithOffset(index, (detector.focusY - (detector.focusY - top) * ratio).roundToInt())
            return true
        }
    })
    fun resetZoom() { stripScale = 1f; translationX = 0f; requestLayout() }
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        viewport = MeasureSpec.getSize(widthSpec)
        super.onMeasure(MeasureSpec.makeMeasureSpec((viewport * stripScale).roundToInt(), MeasureSpec.EXACTLY), heightSpec)
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!continuousZoom) return super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            captured = false; dragging = false
            downX = event.rawX; downY = event.rawY
            lastX = event.rawX; lastY = event.rawY
        }
        detector.onTouchEvent(event)
        if (event.pointerCount > 1 || (stripScale > 1f && event.actionMasked == MotionEvent.ACTION_MOVE &&
                    (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop))) {
            if (!captured) {
                MotionEvent.obtain(event).also { it.action = MotionEvent.ACTION_CANCEL; super.dispatchTouchEvent(it); it.recycle() }
                stopScroll(); captured = true
            }
            dragging = event.pointerCount == 1 && !detector.isInProgress
        }
        if (captured) {
            if (dragging && event.actionMasked == MotionEvent.ACTION_MOVE) {
                translationX = (translationX + event.rawX - lastX).coerceIn(-viewport * (stripScale - 1), 0f)
                scrollBy(0, (lastY - event.rawY).roundToInt())
            }
            lastX = event.rawX; lastY = event.rawY
            return true
        }
        return super.dispatchTouchEvent(event)
    }
}
