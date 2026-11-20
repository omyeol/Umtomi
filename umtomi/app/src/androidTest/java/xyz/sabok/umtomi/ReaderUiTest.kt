package xyz.sabok.umtomi

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.ViewGroup
import xyz.sabok.umtomi.ui.ContinuousReaderView
import xyz.sabok.umtomi.util.Preferences
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import xyz.sabok.umtomi.adapters.ReaderAdapter
import xyz.sabok.umtomi.hitomi.GalleryInfo
import xyz.sabok.umtomi.hitomi.GalleryFiles
import xyz.sabok.umtomi.ui.ReaderActivity
import xyz.sabok.umtomi.util.downloader.Cache
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class ReaderUiTest {
    @Test fun navigationAndChromeWorkWithTheNewControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = 2000000010
        val prefs = context.getSharedPreferences("reader-tabs", 0)
        val oldTabs = prefs.getString("tabs", "[]")
        val oldSelected = prefs.getInt("selected", 0)
        val oldLongPress = Preferences["reader_long_press_single_page", true]
        Preferences["reader_long_press_single_page"] = true
        val oldVolume = Preferences["reader_volume_navigation", false]
        val captureFolder = "Capture test " + System.nanoTime()
        val shelf = xyz.sabok.umtomi.ui.BookshelfStore(context)
        val oldFolder = shelf.activeFolder
        val cache = Cache.getInstance(context, id)
        val image = Bitmap.createBitmap(160,220,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }
        val bytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
        image.recycle()
        cache.setMetadata {
            it.galleryInfo = GalleryInfo(id.toString(), "Reader UI fixture", type="test", date="", files=List(3) { GalleryFiles(160,"0".repeat(64),name="$it.png",height=220) })
            it.imageList = MutableList(3) { null }
        }
        runBlocking { repeat(3) { cache.putImage(it,"$it.png",bytes) } }
        try {
            ActivityScenario.launch<ReaderActivity>(Intent(context,ReaderActivity::class.java).putExtra("galleryID",id)).use { scenario ->
                instrumentation.waitForIdleSync()
                var imageReady = false
                for (attempt in 0 until 50) {
                    scenario.onActivity { activity ->
                        val imageView = activity.findViewById<RecyclerView>(R.id.recyclerview).getChildAt(0)?.findViewById<com.github.piasy.biv.view.BigImageView>(R.id.image)
                        imageReady = (imageView?.mainView as? com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView)?.isReady == true
                    }
                    if (imageReady) break
                    Thread.sleep(100)
                }
                assertTrue("Cached image should finish decoding", imageReady)
                scenario.onActivity { activity ->
                    assertEquals(View.VISIBLE,activity.findViewById<View>(R.id.reader_dock).visibility)
                    assertEquals("1 / 3",activity.findViewById<TextView>(R.id.reader_page_label).text.toString())
                    activity.findViewById<View>(R.id.reader_next).performClick()
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertEquals("2 / 3",activity.findViewById<TextView>(R.id.reader_page_label).text.toString())
                    activity.findViewById<View>(R.id.reader_previous).performClick()
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertEquals("1 / 3",activity.findViewById<TextView>(R.id.reader_page_label).text.toString())
                    val list = activity.findViewById<RecyclerView>(R.id.recyclerview)
                    val strip = list as ContinuousReaderView
                    val margins = list.getChildAt(0).layoutParams as ViewGroup.MarginLayoutParams
                    assertEquals(0, margins.bottomMargin)
                    Preferences["reader_volume_navigation"] = true
                    assertTrue(activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)))
                    Preferences["reader_volume_navigation"] = false
                    val down = android.os.SystemClock.uptimeMillis()
                    fun touch(action: Int, spread: Float, time: Long, count: Int = 2) {
                        val props = Array(count) { MotionEvent.PointerProperties().apply { this.id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
                        val coords = Array(count) { MotionEvent.PointerCoords().apply { x = strip.width / 2f + (if (it == 0) -spread else spread); y = strip.height / 2f; pressure = 1f; size = 1f } }
                        val event = MotionEvent.obtain(down, down + time, action, count, props, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                        strip.dispatchTouchEvent(event); event.recycle()
                    }
                    touch(MotionEvent.ACTION_DOWN, 200f, 0, 1)
                    touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 200f, 20)
                    for (i in 1..8) touch(MotionEvent.ACTION_MOVE, 200f + 30*i, 20L + i*20)
                    touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 440f, 210)
                    touch(MotionEvent.ACTION_UP, 440f, 230, 1)
                    assertTrue("Whole strip should zoom", strip.stripScale > 1.1f)
                }
                instrumentation.waitForIdleSync()
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                java.io.File(context.getExternalFilesDir(null), "reader-zoom.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
                scenario.onActivity { activity ->
                    val list = activity.findViewById<ContinuousReaderView>(R.id.recyclerview)
                    val strip = list
                    assertTrue("Entire list should be wider than viewport", list.width > (list.parent as View).width)
                    assertEquals(list.width, list.getChildAt(0).width)
                    val imageView = list.getChildAt(0).findViewById<com.github.piasy.biv.view.BigImageView>(R.id.image).mainView as com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
                    assertEquals(imageView.width.toFloat(), imageView.scale * imageView.sWidth, 2f)
                    val adapter = list.adapter as ReaderAdapter
                    adapter.onItemClickListener!!.invoke()
                    assertEquals(View.GONE,activity.findViewById<View>(R.id.reader_dock).visibility)
                    assertEquals(View.GONE,activity.findViewById<View>(R.id.reader_tab_scroll).visibility)
                    adapter.onItemClickListener!!.invoke()
                    assertEquals(View.VISIBLE,activity.findViewById<View>(R.id.reader_dock).visibility)
                    adapter.onItemLongClickListener?.invoke()
                    assertTrue(strip.continuousZoom)
                    activity.findViewById<View>(R.id.reader_single_page).performClick()
                    assertFalse(strip.continuousZoom)
                    assertEquals(1f, strip.stripScale)
                    assertTrue(adapter.isFullScreen)
                    assertEquals(LinearLayoutManager.HORIZONTAL,(list.layoutManager as LinearLayoutManager).orientation)
                    assertEquals(View.VISIBLE,activity.findViewById<View>(R.id.reader_dock).visibility)
                    activity.findViewById<View>(R.id.reader_single_page).performClick()
                    assertEquals(LinearLayoutManager.VERTICAL, (list.layoutManager as LinearLayoutManager).orientation)
                    assertTrue(strip.continuousZoom)
                    assertFalse(adapter.isFullScreen)
                    assertEquals(View.VISIBLE,activity.findViewById<View>(R.id.reader_dock).visibility)
                    Preferences["reader_long_press_single_page"] = false
                    adapter.onItemLongClickListener?.invoke()
                    assertEquals(LinearLayoutManager.VERTICAL, (list.layoutManager as LinearLayoutManager).orientation)
                    assertFalse(adapter.isFullScreen)
                }
                instrumentation.waitForIdleSync()
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.reader_capture)).perform(androidx.test.espresso.action.ViewActions.click())
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("영역 확정")).check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("↓ 아래로 확장 · 스크롤 캡처")).perform(androidx.test.espresso.action.ViewActions.click())
                Thread.sleep(1500)
                val capturePreview = instrumentation.uiAutomation.takeScreenshot()
                java.io.File(context.getExternalFilesDir(null), "capture-preview.png").outputStream().use { capturePreview.compress(Bitmap.CompressFormat.PNG, 100, it) }
                capturePreview.recycle()
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("영역 확정")).perform(androidx.test.espresso.action.ViewActions.click())
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("파일로 저장")).perform(androidx.test.espresso.action.ViewActions.click())
                Thread.sleep(700)
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                Thread.sleep(500)
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("캡처 미리보기")).check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
                val resultPreview = instrumentation.uiAutomation.takeScreenshot()
                java.io.File(context.getExternalFilesDir(null), "capture-result.png").outputStream().use { resultPreview.compress(Bitmap.CompressFormat.PNG, 100, it) }
                resultPreview.recycle()
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("파일로 저장")).check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription("저장할 책장 변경")).perform(androidx.test.espresso.action.ViewActions.click())
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("새 책장 만들기")).perform(androidx.test.espresso.action.ViewActions.click())
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("만들기")).perform(androidx.test.espresso.action.ViewActions.click())
                assertFalse(xyz.sabok.umtomi.ui.BookshelfStore(context).folders.contains(captureFolder))
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription("새 책장 이름")).perform(androidx.test.espresso.action.ViewActions.replaceText(captureFolder), androidx.test.espresso.action.ViewActions.closeSoftKeyboard())
                Thread.sleep(400) // Wait for the system keyboard closing animation before tapping.
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("만들기")).perform(androidx.test.espresso.action.ViewActions.click())
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("저장할 책장 변경 · $captureFolder")).check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("책장에 추가")).perform(androidx.test.espresso.action.ViewActions.click())
                var saved = false
                for (attempt in 0 until 50) {
                    saved = xyz.sabok.umtomi.ui.BookshelfStore(context).items.any { it.folder == captureFolder && java.io.File(it.filePath).length() > 0 }
                    if (saved) break
                    Thread.sleep(100)
                }
                assertTrue("Selected region should be saved in chosen bookshelf", saved)
            }
            val oldDownloadFolder = Preferences["download_folder", ""]
            val oldWarning = Preferences["download_folder_ignore_warning", false]
            val monitor = instrumentation.addMonitor(ReaderActivity::class.java.name, null, false)
            try {
                Preferences["download_folder"] = android.net.Uri.fromFile(context.getExternalFilesDir(null)).toString()
                Preferences["download_folder_ignore_warning"] = true
                ActivityScenario.launch<xyz.sabok.umtomi.ui.MainActivity>(Intent(context, xyz.sabok.umtomi.ui.MainActivity::class.java)).use { main ->
                    androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.resume_reader)).perform(androidx.test.espresso.action.ViewActions.click())
                    val resumed = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
                    assertNotNull("Return button should reopen reader", resumed)
                    assertEquals(id, xyz.sabok.umtomi.util.ReaderTabStore(context).selectedID)
                    instrumentation.runOnMainSync { resumed?.finish() }
                    instrumentation.waitForIdleSync()
                    val tabs = xyz.sabok.umtomi.util.ReaderTabStore(context)
                    tabs.all().forEach { tabs.close(it.galleryID) }
                    main.recreate()
                    main.onActivity { assertEquals(View.GONE, it.findViewById<View>(R.id.resume_reader).visibility) }
                }
            } finally {
                instrumentation.removeMonitor(monitor)
                Preferences["download_folder"] = oldDownloadFolder
                Preferences["download_folder_ignore_warning"] = oldWarning
            }
        } finally {
            xyz.sabok.umtomi.ui.BookshelfStore(context).apply { deleteFolder(captureFolder); selectFolder(oldFolder) }
            Preferences["reader_long_press_single_page"] = oldLongPress
            Preferences["reader_volume_navigation"] = oldVolume
            prefs.edit().putString("tabs",oldTabs).putInt("selected",oldSelected).commit()
            histories.remove(id)
            Cache.delete(context,id)
        }
    }
}
