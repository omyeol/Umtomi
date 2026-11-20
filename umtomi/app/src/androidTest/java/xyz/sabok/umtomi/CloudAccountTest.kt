package xyz.sabok.umtomi

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import xyz.sabok.umtomi.sync.CloudAccount

class CloudAccountTest {
    @Test fun waitingSyncCanBeInterrupted() {
        val started = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.atomic.AtomicBoolean(false)
        lateinit var worker: Thread
        xyz.sabok.umtomi.sync.UmtomiDriveSync.exclusive {
            worker = Thread {
                started.countDown()
                try { xyz.sabok.umtomi.sync.UmtomiDriveSync.exclusive { entered.set(true) } }
                catch (_: InterruptedException) { }
                finally { finished.countDown() }
            }
            worker.start()
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
            worker.interrupt()
            assertTrue(finished.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(entered.get())
        }
        worker.join(2000)
    }
    @Test fun migrationConflictAndUnifiedDisconnect() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "account-test-${java.util.UUID.randomUUID()}-"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
        }
        val legacy = context.getSharedPreferences("umtomi-drive", 0)
        val shelf = context.getSharedPreferences("shared-bookshelf", 0)
        try {
            shelf.edit().putString("account", "shelf@example.invalid").commit()
            assertEquals("shelf@example.invalid", CloudAccount.account(context))
            CloudAccount.preferences(context).edit().clear().commit()
            legacy.edit().putString("account", "other@example.invalid").commit()
            assertNull(CloudAccount.account(context))
            assertTrue(CloudAccount.preferences(context).getBoolean("conflict", false))
            CloudAccount.connect(context, "chosen@example.invalid")
            assertEquals("chosen@example.invalid", CloudAccount.account(context))
            assertEquals("chosen@example.invalid", shelf.getString("account", null))
            assertEquals("chosen@example.invalid", legacy.getString("account", null))
            CloudAccount.disconnect(context)
            assertNull(CloudAccount.account(context))
            assertNull(shelf.getString("account", null))
            assertNull(legacy.getString("account", null))
            assertFalse(shelf.getBoolean("enabled", true))
        } finally {
            listOf("umtomi-drive", "shared-bookshelf", "umtomi-cloud-account").forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        }
    }
}
