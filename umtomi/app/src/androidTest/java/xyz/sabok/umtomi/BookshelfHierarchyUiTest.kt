package xyz.sabok.umtomi

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import xyz.sabok.umtomi.ui.BookshelfActivity
import xyz.sabok.umtomi.ui.BookshelfStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookshelfHierarchyUiTest {
    @Test fun virtualParentCanBeRenamedFromFolderManager() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val initial = BookshelfStore(context).activeFolder
        val parent = "검증-${UUID.randomUUID().toString().take(8)}"
        val renamed = "$parent-이동"
        val store = BookshelfStore(context)
        store.addFolder("$parent/하위")
        val id = store.folderId()
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        try {
            ActivityScenario.launch(BookshelfActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    descendants(activity.window.decorView).filterIsInstance<Button>().single { it.text.toString().startsWith("책장/$parent/") }.performClick()
                }
                onView(withText("📁 $parent")).inRoot(isDialog()).perform(click())
                onView(withText("폴더 관리")).inRoot(isDialog()).perform(click())
                onView(withText("이름 변경")).inRoot(isDialog()).perform(click())
                onView(isAssignableFrom(EditText::class.java)).inRoot(isDialog()).perform(replaceText(renamed), closeSoftKeyboard())
                onView(withText(android.R.string.ok)).inRoot(isDialog()).perform(click())
                scenario.onActivity {
                    val after = BookshelfStore(context)
                    assertEquals(id, after.folderId("$renamed/하위"))
                    assertTrue("$renamed/하위" in after.folders)
                    assertFalse("$parent/하위" in after.folders)
                }
            }
        } finally {
            val current = BookshelfStore(context)
            current.deleteFolder("$parent/하위"); current.deleteFolder("$renamed/하위")
            current.selectFolder(initial)
        }
    }
}
