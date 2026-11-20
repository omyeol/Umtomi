package xyz.sabok.umtomi

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import xyz.sabok.umtomi.ui.BookshelfActivity
import xyz.sabok.umtomi.ui.BookshelfStore
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookshelfTest {
    private fun isolated(test: (Context) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "shelf-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = directory }
        try { test(context) } finally { directory.deleteRecursively() }
    }

    @Test fun folderNotesColumnsAndImagesSurviveRenameAndRestart() = isolated { context ->
        val store = BookshelfStore(context)
        store.addFolder("여행")
        store.setColumnCount(6)
        store.setNote("서울 · Tokyo")
        val bitmap = Bitmap.createBitmap(31, 67, Bitmap.Config.ARGB_8888)
        try { store.addImage(bitmap, "test") } finally { bitmap.recycle() }
        assertTrue(store.renameFolder("여행", "Trip"))
        val reopened = BookshelfStore(context)
        assertEquals("Trip", reopened.activeFolder)
        assertEquals(6, reopened.columnCount())
        assertEquals("서울 · Tokyo", reopened.note())
        assertEquals("Trip", reopened.items.single().folder)
        assertEquals(31, reopened.items.single().width)
        val renamedPhoto = reopened.items.single()
        assertTrue(File(renamedPhoto.filePath).exists())
        assertEquals("image1.png", File(renamedPhoto.filePath).name)
        reopened.deleteFolder("Trip")
        assertFalse(File(renamedPhoto.filePath).exists())
        assertTrue(BookshelfStore(context).items.isEmpty())
        assertTrue(BookshelfStore(context).folders.isNotEmpty())
    }

    @Test fun importPreservesBytesAndOriginalDestination() = isolated { context ->
        val bitmap = Bitmap.createBitmap(71, 23, Bitmap.Config.ARGB_8888)
        val source = File(context.filesDir, "source.png")
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val store = BookshelfStore(context)
        val folder = store.addFolder("A")
        val item = store.prepareImport(Uri.fromFile(source), folder)
        store.addFolder("B")
        store.commitImport(item)
        assertEquals("A", store.items.single().folder)
        assertArrayEquals(source.readBytes(), File(store.items.single().filePath).readBytes())
        assertEquals(71, item.width)
        assertEquals(23, item.height)
    }

    @Test fun columnsAreClampedAndDuplicateNamesAreRejected() = isolated { context ->
        val store = BookshelfStore(context)
        store.addFolder("A")
        store.setColumnCount(100)
        assertEquals(6, store.columnCount())
        store.setColumnCount(-1)
        assertEquals(1, store.columnCount())
        store.addFolder("B")
        assertFalse(store.renameFolder("B", "a"))
        assertFalse(store.renameFolder("B", "  "))
        assertEquals(3, store.folders.size)
    }

    @Test fun corruptCatalogIsPreserved() = isolated { context ->
        val catalog = File(context.filesDir, "bookshelf/catalog.json")
        catalog.parentFile!!.mkdirs()
        catalog.writeText("damaged catalog")
        val store = BookshelfStore(context)
        assertNotNull(store.loadError)
        assertTrue(runCatching { store.save() }.isFailure)
        assertEquals("damaged catalog", catalog.readText())
    }

    @Test fun bookshelfActivityOpensAndRecreates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<BookshelfActivity>(Intent(context, BookshelfActivity::class.java)).use { scenario ->
            scenario.onActivity { assertFalse(it.isFinishing) }
            scenario.recreate()
            scenario.onActivity { assertFalse(it.isFinishing) }
        }
    }

    @Test fun pdfExportCreatesMultiplePagesWithCorrectOrientation() = isolated { context ->
        val store = BookshelfStore(context)
        val bitmap = Bitmap.createBitmap(60, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        try { repeat(3) { store.addImage(bitmap, "fixture") } } finally { bitmap.recycle() }
        val application = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<BookshelfActivity>(Intent(application, BookshelfActivity::class.java)).use { scenario ->
            val orientationClass = Class.forName("xyz.sabok.umtomi.ui.BookshelfActivity\$PdfPageOrientation")
            val method = BookshelfActivity::class.java.getDeclaredMethod("writePdf", Uri::class.java,
                String::class.java, Int::class.javaPrimitiveType, List::class.java, orientationClass).apply { isAccessible = true }
            for (orientation in orientationClass.enumConstants!!) {
                val file = File(context.filesDir, "$orientation.pdf")
                scenario.onActivity { activity ->
                    method.invoke(activity, Uri.fromFile(file), store.activeFolder, 2, store.items.toList(), orientation)
                }
                android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    android.graphics.pdf.PdfRenderer(descriptor).use { pdf ->
                        assertTrue(pdf.pageCount >= 2)
                        pdf.openPage(0).use { page ->
                            assertEquals(orientation.toString() == "PORTRAIT", page.height > page.width)
                            val rendered = Bitmap.createBitmap(108, 192, Bitmap.Config.ARGB_8888)
                            try {
                                page.render(rendered, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                assertEquals(android.graphics.Color.BLUE, rendered.getPixel(10, 10))
                            } finally { rendered.recycle() }
                        }
                    }
                }
            }
        }
    }
}
