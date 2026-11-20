package xyz.sabok.umtomi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import xyz.sabok.umtomi.util.ReaderTabStore

@RunWith(AndroidJUnit4::class)
class ReaderTabsTest {
    @Test fun tabsKeepIndependentPagesAndSurviveStoreReload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("reader-tabs", 0)
        val original = preferences.getString("tabs", null)
        val selected = preferences.getInt("selected", 0)
        try {
            preferences.edit().clear().commit()
            val tabs = ReaderTabStore(context)
            tabs.open(111)
            tabs.setPage(111, 4)
            tabs.open(222)
            tabs.setPage(222, 9)
            tabs.open(111)
            assertEquals(2, tabs.all().size)
            assertEquals(111, tabs.selectedID)
            val restored = ReaderTabStore(context)
            assertEquals(4, restored.page(111))
            assertEquals(9, restored.page(222))
            assertEquals(222, restored.close(111))
            assertEquals(222, ReaderTabStore(context).selectedID)
            assertEquals(0, restored.close(222))
        } finally {
            val edit = preferences.edit().clear().putInt("selected", selected)
            if (original != null) edit.putString("tabs", original)
            edit.commit()
        }
    }
}
