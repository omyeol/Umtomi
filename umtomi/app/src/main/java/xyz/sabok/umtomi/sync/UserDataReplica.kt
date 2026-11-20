package xyz.sabok.umtomi.sync

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Per-entry logical clocks; absent entries produce tombstones, not an empty replacement. */
internal class UserDataReplica(val state: JSONObject = JSONObject()) {
    init {
        if (!state.has("actor")) state.put("actor", UUID.randomUUID().toString())
        if (!state.has("clock")) state.put("clock", 0L)
        for (key in listOf("baseline", "registers")) if (!state.has(key)) state.put(key, JSONObject())
        for (key in listOf("pending", "seen")) if (!state.has(key)) state.put(key, JSONArray())
    }
    val pending: JSONArray get() = state.getJSONArray("pending")
    fun capture(local: JSONObject) {
        val before = state.getJSONObject("baseline")
        val keys = (before.keys().asSequence() + local.keys().asSequence()).toSet()
        val changes = keys.filter { before.opt(it)?.toString() != local.opt(it)?.toString() }
        for (chunk in changes.chunked(64)) {
            val batch = JSONObject().put("format", "umtomi-userdata").put("id", UUID.randomUUID().toString())
                .put("actor", state.getString("actor")).put("clock", state.getLong("clock") + 1)
                .put("changes", JSONObject().apply { chunk.forEach { put(it, local.opt(it) ?: JSONObject.NULL) } })
            apply(batch); pending.put(batch)
        }
        acknowledge(local)
    }
    fun acknowledge(local: JSONObject) { state.put("baseline", JSONObject(local.toString())) }
    fun apply(batch: JSONObject) {
        require(batch.getString("format") == "umtomi-userdata")
        UUID.fromString(batch.getString("id")); UUID.fromString(batch.getString("actor"))
        val clock = batch.getLong("clock"); require(clock in 1..9000000000000000)
        val changes = batch.getJSONObject("changes"); require(changes.length() <= 64)
        changes.keys().forEach { require(it.matches(Regex("(favorite|history|folder|tag|artist)/.{1,2048}"))) }
        val registers = state.getJSONObject("registers")
        changes.keys().forEach { key ->
            val old = registers.optJSONObject(key)
            if (old == null || clock > old.getLong("clock") || clock == old.getLong("clock") && batch.getString("actor") > old.getString("actor"))
                registers.put(key, JSONObject().put("clock", clock).put("actor", batch.getString("actor")).put("value", changes.get(key)))
        }
        state.put("clock", maxOf(clock, state.getLong("clock")))
    }
    fun project() = JSONObject().apply {
        val registers = state.getJSONObject("registers")
        registers.keys().asSequence().sorted().forEach { key ->
            val record = registers.getJSONObject(key)
            if (!record.isNull("value")) put(key, record.get("value"))
        }
    }
}
