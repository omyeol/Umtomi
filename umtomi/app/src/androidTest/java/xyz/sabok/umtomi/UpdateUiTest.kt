package xyz.sabok.umtomi

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import xyz.sabok.umtomi.ui.UpdateActivity
import java.io.File

@RunWith(AndroidJUnit4::class)
class UpdateUiTest {
    @Test fun completedLegacyDownloadIsClearedAcrossRecreation() = staleDownload(true)
    @Test fun installedTargetWithoutArchiveIsCleared() = staleDownload(false)

    private fun staleDownload(legacy: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val state = context.getSharedPreferences("app-updater", 0)
        val oldId = state.getLong("download_id", -1)
        val oldVersion = state.getString("target_version", null)
        val target = File(context.getExternalFilesDir(null), "Umtomi-update.apk")
        val backup = File(context.cacheDir, "updater-test-${System.nanoTime()}.apk")
        val existed = target.exists()
        if (existed) target.copyTo(backup)
        try {
            if (legacy) File(context.applicationInfo.sourceDir).copyTo(target, overwrite = true)
            else target.delete()
            state.edit().putLong("download_id", Long.MAX_VALUE).apply {
                if (legacy) remove("target_version") else putString("target_version", "v${BuildConfig.VERSION_NAME}")
            }.commit()
            ActivityScenario.launch<UpdateActivity>(Intent(context, UpdateActivity::class.java)).use { scenario ->
                instrumentation.waitForIdleSync()
                assertEquals(-1L, state.getLong("download_id", -1))
                assertFalse(state.contains("target_version"))
                assertFalse(target.exists())
                scenario.recreate()
                instrumentation.waitForIdleSync()
                assertEquals(-1L, state.getLong("download_id", -1))
            }
        } finally {
            state.edit().apply {
                if (oldId == -1L) remove("download_id") else putLong("download_id", oldId)
                if (oldVersion == null) remove("target_version") else putString("target_version", oldVersion)
            }.commit()
            target.delete()
            if (existed) { backup.copyTo(target); backup.delete() }
        }
    }
}
