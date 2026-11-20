package xyz.sabok.umtomi

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.SwitchPreferenceCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import xyz.sabok.umtomi.ui.SettingsActivity
import xyz.sabok.umtomi.ui.FavoritesActivity
import xyz.sabok.umtomi.ui.fragment.SettingsFragment
import xyz.sabok.umtomi.ui.fragment.ManageFavoritesFragment
import xyz.sabok.umtomi.util.*
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FavoritesAndThemeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun isolated(action: (Context) -> Unit) {
        val base = instrumentation.targetContext
        val dir = File(base.cacheDir, "favorites-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = dir }
        try { action(context) } finally { dir.deleteRecursively() }
    }

    @Test fun nestedFoldersPersistAndDeletingParentDoesNotTouchFavorites() = isolated { context ->
        val before = favorites.toList()
        val store = FavoriteFolders(context)
        val parent = store.create("작가", null)
        val child = store.create("완독", parent.id)
        val sibling = store.create("다른 폴더", null)
        store.addToFolder(child.id, listOf(123, 456, 123))
        store.assign(123, setOf(child.id, sibling.id))
        store.rename(child.id, "읽은 작품")
        val reopened = FavoriteFolders(context)
        assertEquals(listOf(123, 456), reopened.folders.first { it.id == child.id }.manualGalleryIDs)
        assertEquals("작가 / 읽은 작품", reopened.path(reopened.folders.first { it.id == child.id }))
        reopened.delete(parent.id)
        assertEquals(listOf(sibling.id), FavoriteFolders(context).folders.map { it.id })
        assertEquals(before, favorites.toList())
    }

    @Test fun jsonRoundTripAndIOSFolderKeysWork() = isolated { context ->
        val before = favorites.toList()
        try {
            favorites.addAll(listOf(123, 456))
            val store = FavoriteFolders(context)
            val parent = store.create("자료", null)
            val child = store.create("하위", parent.id)
            store.addToFolder(child.id, listOf(123, 456))
            val data = FavoritesFiles.export(context)
            val restored = FavoritesFiles.parse(data)
            assertTrue(restored.ids.containsAll(listOf(123, 456)))
            assertEquals(parent.id, restored.folders.first { it.id == child.id }.parentID)
            assertEquals(listOf(123, 456), restored.folders.first { it.id == child.id }.manualGalleryIDs)
            FavoritesFiles.restore(context, data)
            assertEquals(2, FavoriteFolders(context).folders.size)
            val json = JSONObject(data)
            val array = json.getJSONObject("favoriteLibrary").getJSONArray("folders")
            for (i in 0 until array.length()) {
                val folder = array.getJSONObject(i)
                folder.put("id", folder.getString("id").uppercase())
                if (!folder.isNull("parentID")) folder.put("parentID", folder.getString("parentID").uppercase())
            }
            FavoritesFiles.restore(context, json.toString())
            assertEquals(2, FavoriteFolders(context).folders.size)
        } finally { favorites.clear(); favorites.addAll(before) }
    }

    @Test fun malformedAndCyclicImportsDoNotChangeStoredData() = isolated { context ->
        val store = FavoriteFolders(context)
        store.create("keep", null)
        val original = File(context.filesDir, "favorite-folders.json").readText()
        val id = UUID.randomUUID().toString()
        val cyclic = JSONObject().put("favorites", JSONArray().put(123)).put("favoriteLibrary",
            JSONObject().put("folders", JSONArray().put(FavoriteFolder(id, id, "cycle").json())))
        val before = favorites.toList()
        assertTrue(runCatching { FavoritesFiles.restore(context, cyclic.toString()) }.isFailure)
        assertTrue(runCatching { FavoritesFiles.restore(context, "{broken") }.isFailure)
        assertEquals(original, File(context.filesDir, "favorite-folders.json").readText())
        assertEquals(before, favorites.toList())
        assertEquals(listOf(1, 2), FavoritesFiles.parse("[1,2,1]").ids)
    }

    @Test fun repeatedThemeChangesPersistAndKeepSingleSettingsFragment() {
        val original = Preferences["dark_mode", false]
        try {
            ActivityScenario.launch<SettingsActivity>(Intent(instrumentation.targetContext, SettingsActivity::class.java)).use { scenario ->
                repeat(6) { index ->
                    val dark = index % 2 == 0
                    scenario.onActivity { activity ->
                        val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings) as SettingsFragment
                        val preference = fragment.findPreference<SwitchPreferenceCompat>("dark_mode")!!
                        fragment.onPreferenceChange(preference, dark)
                    }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        assertFalse(activity.isFinishing)
                        assertEquals(dark, Preferences["dark_mode", false])
                        assertEquals(if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
                            activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                        assertEquals(1, activity.supportFragmentManager.fragments.count { it is SettingsFragment })
                        val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings) as SettingsFragment
                        assertEquals(dark, fragment.findPreference<SwitchPreferenceCompat>("dark_mode")!!.isChecked)
                    }
                }
                scenario.onActivity { activity ->
                    activity.supportFragmentManager.beginTransaction().replace(R.id.settings, ManageFavoritesFragment()).commitNow()
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertTrue(activity.supportFragmentManager.findFragmentById(R.id.settings) is ManageFavoritesFragment)
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                Preferences["dark_mode"] = original
                AppCompatDelegate.setDefaultNightMode(if (original) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
            }
        }
    }

    @Test fun favoritesScreenRecreates() {
        ActivityScenario.launch<FavoritesActivity>(Intent(instrumentation.targetContext, FavoritesActivity::class.java)).use { scenario ->
            scenario.onActivity { assertFalse(it.isFinishing) }
            scenario.recreate()
            scenario.onActivity { assertFalse(it.isFinishing) }
        }
    }
    @Test fun updateScreenRecreatesWhileChecking() {
        ActivityScenario.launch<xyz.sabok.umtomi.ui.UpdateActivity>(
            Intent(instrumentation.targetContext, xyz.sabok.umtomi.ui.UpdateActivity::class.java)
        ).use { scenario ->
            scenario.onActivity { assertFalse(it.isFinishing) }
            scenario.recreate()
            scenario.onActivity { assertFalse(it.isFinishing) }
        }
    }

}
