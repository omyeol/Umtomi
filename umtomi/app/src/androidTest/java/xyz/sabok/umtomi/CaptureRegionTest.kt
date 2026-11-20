package xyz.sabok.umtomi

import android.graphics.Bitmap
import android.graphics.Color
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import xyz.sabok.umtomi.ui.MobileCaptureRegionView

@RunWith(AndroidJUnit4::class)
class CaptureRegionTest {
    @Test fun scrollingAppendsOnlyNewRows() {
        val first = Bitmap.createBitmap(4, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val next = Bitmap.createBitmap(4, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
            for (y in 5..7) for (x in 0..3) setPixel(x, y, Color.BLUE)
        }
        val result = xyz.sabok.umtomi.ui.CaptureStitch.append(first, next, 3)
        assertEquals(11, result.height)
        assertEquals(Color.RED, result.getPixel(0, 7))
        assertEquals(Color.BLUE, result.getPixel(0, 8))
        assertEquals(Color.BLUE, result.getPixel(0, 10))
        assertTrue(runCatching { xyz.sabok.umtomi.ui.CaptureStitch.append(first, next, 9) }.isFailure)
        first.recycle(); next.recycle(); result.recycle()
    }
    @Test fun cornersMapToSourcePixelsAndFullCropSurvivesSourceDisposal() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val source = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            val view = MobileCaptureRegionView(instrumentation.targetContext, source)
            FrameLayout(instrumentation.targetContext).addView(view)
            view.layout(0, 0, 800, 600)
            val initial = view.createSelectionBitmap()!!
            assertEquals(352, initial.width); assertEquals(264, initial.height)
            initial.recycle()
            fun drag(x: Float, y: Float, endX: Float, endY: Float) {
                val time = android.os.SystemClock.uptimeMillis()
                for ((action, point) in listOf(MotionEvent.ACTION_DOWN to (x to y), MotionEvent.ACTION_MOVE to (endX to endY), MotionEvent.ACTION_UP to (endX to endY))) {
                    val event = MotionEvent.obtain(time, time, action, point.first, point.second, 0)
                    view.onTouchEvent(event); event.recycle()
                }
            }
            drag(48f, 36f, 0f, 0f)
            drag(752f, 564f, 800f, 600f)
            val full = view.createSelectionBitmap()!!
            assertEquals(400, full.width); assertEquals(300, full.height)
            assertNotSame(source, full)
            source.recycle()
            assertEquals(Color.GREEN, full.getPixel(200, 150))
            full.recycle()
        }
    }
}
