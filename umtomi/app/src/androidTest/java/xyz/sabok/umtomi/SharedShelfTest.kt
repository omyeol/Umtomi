package xyz.sabok.umtomi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.json.JSONArray
import xyz.sabok.umtomi.sync.shared.*

@RunWith(AndroidJUnit4::class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 26)
class SharedShelfTest {
    @Test fun readsWindowsBatchAndProducesAndroidReply() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val password = "synthetic-shared-shelf-fixture-only".toCharArray()
        val envelope = instrumentation.context.assets.open("shared-windows.json").bufferedReader().use { it.readText() }
        val batch = JSONObject(BrowserSyncCrypto.decrypt(envelope, password).toString(Charsets.UTF_8))
        val replica = BookshelfSyncReplica()
        replica.apply(batch)
        val snapshot = replica.project()
        assertEquals("Windows fixture", snapshot.getJSONArray("folders").getJSONObject(0).getString("name"))
        replica.acknowledge(snapshot)
        snapshot.getJSONArray("folders").getJSONObject(0).put("name", "Android fixture")
        replica.capture(snapshot)
        // Full Android initial batch for an independent Windows reader.
        val fresh = BookshelfSyncReplica(); fresh.capture(snapshot)
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "shared-android.json").writeText(
            BrowserSyncCrypto.encrypt(fresh.pending.getJSONObject(0).toString().toByteArray(), password))
        assertTrue(runCatching { xyz.sabok.umtomi.sync.UmtomiSyncCrypto.decrypt(envelope, password) }.isFailure)
    }
    @Test fun sharedKeysAndPermanentDeletesConverge() {
        val id = "1".repeat(32)
        val key = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        val keys = DriveAccountKeys().apply { add(id, DriveAccountKeys.record(id, key, null)) }
        assertEquals("fixture", keys.decrypt(keys.encrypt("fixture")))
        val a = BookshelfSyncReplica(); val b = BookshelfSyncReplica()
        val folder = JSONObject().put("id", id).put("name", "shared").put("note", "").put("columns", 2)
        val snapshot = JSONObject().put("folders", JSONArray().put(folder)).put("images", JSONArray())
        a.capture(snapshot); b.apply(a.pending.getJSONObject(0)); b.acknowledge(b.project())
        a.capture(JSONObject().put("folders", JSONArray()).put("images", JSONArray()))
        b.apply(a.pending.getJSONObject(1)); b.apply(a.pending.getJSONObject(0))
        assertEquals(0, b.project().getJSONArray("folders").length())
        assertTrue(DriveAppDataClient.validShelfName("omyaru-shelf-v1-$id.json"))
        assertFalse(DriveAppDataClient.validShelfName("umtomi-shelf-v1-$id.json"))
    }
}
