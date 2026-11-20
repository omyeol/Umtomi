package xyz.sabok.umtomi.ui

import xyz.sabok.umtomi.R

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class MobileCaptureRegionDialog(
    context: Context,
    private var source: Bitmap,
    private val folders: List<String>,
    private val activeFolder: String,
    private val showBookshelfPicker: Boolean,
    private val onExtend: ((((Bitmap?, Int) -> Unit)) -> Unit)? = null,
    private val onClosed: () -> Unit = {},
    private val onConfirmed: (Bitmap, String) -> Unit,
) : Dialog(context) {
    private val initialHeight = source.height
    private lateinit var selector: MobileCaptureRegionView
    private lateinit var sizeLabel: TextView
    private lateinit var completeButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.statusBarColor = Color.BLACK
        window?.navigationBarColor = Color.BLACK

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(TextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            text = "모서리 손잡이를 끌어 캡처 영역을 조절하세요"
            setTextColor(Color.rgb(244, 240, 238))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, dp(8), 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        selector = MobileCaptureRegionView(context, source).apply {
            onSelectionChanged = { selection ->
                completeButton.isEnabled = selection != null
                sizeLabel.text = selection?.let {
                    context.getString(R.string.capture_region_size, it.width(), it.height())
                } ?: context.getString(R.string.capture_region_none)
            }
        }
        val previewScroll = android.widget.ScrollView(context).apply {
            val margin = if (context.getSharedPreferences("capture_ui", Context.MODE_PRIVATE).getBoolean("side_margins", true)) dp(20) else 0
            setPadding(margin, 0, margin, 0)
            clipToPadding = false
            isFillViewport = true
            // Edge gestures belong to crop handles, never a draggable scrollbar.
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(selector, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(previewScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (onExtend != null) root.addView(actionButton("↓ 아래로 확장 · 스크롤 캡처") {
            if (selector.isEnabled) {
                // Refuse before scrolling so an oversized extension cannot skip source rows.
                if (source.width.toLong() * (source.height + initialHeight) > 12_000_000L) {
                    android.widget.Toast.makeText(context, "캡처 크기 제한입니다. 현재 영역을 먼저 저장하세요.", android.widget.Toast.LENGTH_LONG).show()
                    return@actionButton
                }
                selector.isEnabled = false
                completeButton.isEnabled = false
                onExtend.invoke { next, moved ->
                    if (!isShowing) { next?.recycle(); return@invoke }
                    selector.isEnabled = true
                    selector.notifySelection()
                    if (next == null || moved <= 0 || next.width != source.width) {
                        next?.recycle()
                        android.widget.Toast.makeText(context, "페이지 끝이거나 이미지가 아직 준비되지 않았습니다.", android.widget.Toast.LENGTH_SHORT).show()
                        return@invoke
                    }
                    val combined = CaptureStitch.append(source, next, moved)
                    source.recycle(); next.recycle(); source = combined
                    selector.replaceSource(combined)
                    previewScroll.post { previewScroll.smoothScrollTo(0, selector.height) }
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        val footer = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(8), dp(4), 0)
        }
        sizeLabel = TextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            text = context.getString(R.string.capture_region_none)
            setTextColor(Color.rgb(183, 188, 197))
            textSize = 13f
        }
        footer.addView(sizeLabel, LinearLayout.LayoutParams(0, dp(48), 1f))
        val folderSelect = Spinner(context).apply {
            contentDescription = context.getString(R.string.capture_bookshelf_destination)
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                folders.ifEmpty { listOf(BookshelfStore.DEFAULT_FOLDER) },
            )
            setSelection(folders.indexOf(activeFolder).takeIf { it >= 0 } ?: 0)
            background = context.getDrawable(R.drawable.tab_overview_pill_background)
            setPadding(dp(12), 0, dp(8), 0)
        }
        if (showBookshelfPicker) {
            root.addView(folderSelect, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        }
        footer.addView(actionButton(context.getString(android.R.string.cancel)) { dismiss() },
            LinearLayout.LayoutParams(dp(70), dp(44)).apply { marginStart = dp(6) })
        completeButton = actionButton(context.getString(R.string.capture_region_complete)) {
            val cropped = selector.createSelectionBitmap() ?: return@actionButton
            onConfirmed(cropped, folderSelect.selectedItem?.toString() ?: activeFolder)
            dismiss()
        }.apply {
            isEnabled = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(165, 232, 208))
            setTextColor(Color.rgb(12, 14, 18))
        }
        footer.addView(completeButton, LinearLayout.LayoutParams(dp(88), dp(44)).apply { marginStart = dp(6) })
        root.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
        setContentView(root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(12) + bars.left, dp(10) + bars.top, dp(12) + bars.right, dp(10) + bars.bottom)
            insets
        }
        setOnDismissListener { onClosed(); if (!source.isRecycled) source.recycle() }
        selector.post { selector.notifySelection() }
    }

    override fun show() {
        super.show()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onBackPressed() {
        dismiss()
    }

    private fun actionButton(label: String, action: () -> Unit): Button = Button(context).apply {
        background = context.getDrawable(R.drawable.tab_overview_pill_background)
        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(37, 42, 51))
        isAllCaps = false
        minHeight = 0
        minWidth = 0
        text = label
        setTextColor(Color.rgb(244, 240, 238))
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()
}

internal class MobileCaptureRegionView(
    context: Context,
    private var source: Bitmap,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(118, 4, 7, 12) }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(165, 232, 208)
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density)
    }
    private val lensOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val crosshair = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(165, 232, 208)
        strokeWidth = max(1f, density)
    }
    private var start: android.graphics.PointF? = android.graphics.PointF(source.width * .06f, source.height * .06f)
    private var end = android.graphics.PointF(source.width * .94f, source.height * .94f)
    private var movingLeft = false
    private var movingTop = false
    private var isTouching = false
    private var touchSourcePoint: android.graphics.PointF? = null
    private var touchDisplayPoint: android.graphics.PointF? = null
    fun notifySelection() { onSelectionChanged?.invoke(selectedSourceRect()) }
    fun replaceSource(bitmap: Bitmap) { source = bitmap; end.y = bitmap.height * .98f; requestLayout(); invalidate(); notifySelection() }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, maxOf(1, (w.toLong() * source.height / source.width).toInt()))
    }
    var onSelectionChanged: ((Rect?) -> Unit)? = null

    init {
        setBackgroundColor(Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bounds = imageBounds()
        canvas.drawBitmap(source, null, bounds, imagePaint)
        selectedSourceRect()?.let { selection ->
            val display = sourceToDisplay(selection, bounds)
            val shade = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addRect(bounds, Path.Direction.CW)
                addRect(display, Path.Direction.CCW)
            }
            canvas.drawPath(shade, shadePaint)
            canvas.drawRect(display, selectionPaint)
            listOf(display.left to display.top, display.right to display.top, display.left to display.bottom, display.right to display.bottom).forEach { (x,y) ->
                canvas.drawCircle(x,y,9f*density,Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(165, 232, 208) })
            }
        }
        if (isTouching && touchSourcePoint != null && touchDisplayPoint != null) {
            drawMagnifier(canvas, bounds, touchSourcePoint!!, touchDisplayPoint!!)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        val point = displayToSource(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val selection = selectedSourceRect() ?: return false
                val display = sourceToDisplay(selection,imageBounds())
                val nearX = min(abs(event.x-display.left),abs(event.x-display.right)) < 30*density
                val nearY = min(abs(event.y-display.top),abs(event.y-display.bottom)) < 30*density
                if (!nearX || !nearY) return false
                parent.requestDisallowInterceptTouchEvent(true)
                start = android.graphics.PointF(selection.left.toFloat(),selection.top.toFloat())
                end = android.graphics.PointF(selection.right.toFloat(),selection.bottom.toFloat())
                movingLeft = abs(event.x-display.left) < abs(event.x-display.right)
                movingTop = abs(event.y-display.top) < abs(event.y-display.bottom)
                val activeX = if (movingLeft) start!!.x else end.x
                val activeY = if (movingTop) start!!.y else end.y
                touchSourcePoint = android.graphics.PointF(activeX, activeY)
                touchDisplayPoint = android.graphics.PointF(event.x, event.y)
                isTouching = true
            }
            MotionEvent.ACTION_MOVE -> {
                if(movingLeft) start!!.x = point.x else end.x = point.x
                if(movingTop) start!!.y = point.y else end.y = point.y
                val activeX = if (movingLeft) start!!.x else end.x
                val activeY = if (movingTop) start!!.y else end.y
                touchSourcePoint = android.graphics.PointF(activeX, activeY)
                touchDisplayPoint = android.graphics.PointF(event.x, event.y)
                isTouching = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                isTouching = false
                touchSourcePoint = null
                touchDisplayPoint = null
            }
            else -> return true
        }
        onSelectionChanged?.invoke(selectedSourceRect())
        invalidate()
        return true
    }

    fun createSelectionBitmap(): Bitmap? {
        val selection = selectedSourceRect() ?: return null
        val cropped = Bitmap.createBitmap(source, selection.left, selection.top, selection.width(), selection.height())
        return if (cropped === source) source.copy(Bitmap.Config.ARGB_8888, false) else cropped
    }

    private fun selectedSourceRect(): Rect? {
        val origin = start ?: return null
        val left = min(origin.x, end.x).roundToInt().coerceIn(0, source.width)
        val top = min(origin.y, end.y).roundToInt().coerceIn(0, source.height)
        val right = max(origin.x, end.x).roundToInt().coerceIn(0, source.width)
        val bottom = max(origin.y, end.y).roundToInt().coerceIn(0, source.height)
        if (right - left < 2 || bottom - top < 2) return null
        return Rect(left, top, right, bottom)
    }

    private fun imageBounds(): RectF {
        val scale = min(width / source.width.toFloat(), height / source.height.toFloat()).coerceAtLeast(0.001f)
        val scaledWidth = source.width * scale
        val scaledHeight = source.height * scale
        return RectF(
            (width - scaledWidth) / 2f,
            (height - scaledHeight) / 2f,
            (width + scaledWidth) / 2f,
            (height + scaledHeight) / 2f,
        )
    }

    private fun displayToSource(x: Float, y: Float): android.graphics.PointF {
        val bounds = imageBounds()
        return android.graphics.PointF(
            ((x - bounds.left) / bounds.width() * source.width).coerceIn(0f, source.width.toFloat()),
            ((y - bounds.top) / bounds.height() * source.height).coerceIn(0f, source.height.toFloat()),
        )
    }

    private fun sourceToDisplay(rect: Rect, bounds: RectF): RectF = RectF(
        bounds.left + rect.left / source.width.toFloat() * bounds.width(),
        bounds.top + rect.top / source.height.toFloat() * bounds.height(),
        bounds.left + rect.right / source.width.toFloat() * bounds.width(),
        bounds.top + rect.bottom / source.height.toFloat() * bounds.height(),
    )

    private fun drawMagnifier(
        canvas: Canvas,
        imageBounds: RectF,
        sourcePoint: android.graphics.PointF,
        displayPoint: android.graphics.PointF,
    ) {
        val diameter = 116f * density
        val gap = 32f * density
        var left = displayPoint.x - diameter / 2f
        var top = displayPoint.y - diameter - gap
        if (top < 8f * density) {
            top = displayPoint.y + gap
        }
        left = left.coerceIn(8f * density, max(8f * density, width - diameter - 8f * density))
        top = top.coerceIn(8f * density, max(8f * density, height - diameter - 8f * density))
        val lens = RectF(left, top, left + diameter, top + diameter)
        val sourceSize = max(24f, min(source.width, source.height) / 8f)
        val sampleLeft = (sourcePoint.x - sourceSize / 2f).coerceIn(0f, max(0f, source.width - sourceSize))
        val sampleTop = (sourcePoint.y - sourceSize / 2f).coerceIn(0f, max(0f, source.height - sourceSize))
        val sample = Rect(
            sampleLeft.roundToInt(),
            sampleTop.roundToInt(),
            min(source.width.toFloat(), sampleLeft + sourceSize).roundToInt(),
            min(source.height.toFloat(), sampleTop + sourceSize).roundToInt(),
        )
        val saved = canvas.save()
        canvas.clipPath(Path().apply { addOval(lens, Path.Direction.CW) })
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(source, sample, lens, imagePaint)
        canvas.restoreToCount(saved)
        canvas.drawOval(lens, lensOutline)
        val centerX = lens.centerX()
        val centerY = lens.centerY()
        canvas.drawLine(lens.left, centerY, lens.right, centerY, crosshair)
        canvas.drawLine(centerX, lens.top, centerX, lens.bottom, crosshair)
    }
}
