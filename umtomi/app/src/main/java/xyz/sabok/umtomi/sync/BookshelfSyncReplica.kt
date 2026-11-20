package xyz.sabok.umtomi.sync

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

@androidx.annotation.RequiresApi(26)
internal class BookshelfSyncReplica(val state: JSONObject = JSONObject()) {
    init {
        if (!state.has("actor")) state.put("actor", uuid())
        if (!state.has("clock")) state.put("clock", 0L)
        for (key in listOf("registers", "baseline")) if (!state.has(key)) state.put(key, JSONObject())
        for (key in listOf("pending", "seenFiles")) if (!state.has(key)) state.put(key, JSONArray())
    }
    val pending: JSONArray get() = state.getJSONArray("pending")
    val seenFiles: JSONArray get() = state.getJSONArray("seenFiles")
    private val registers get() = state.getJSONObject("registers")
    fun capture(snapshot: JSONObject) {
        val next = flatten(snapshot)
        val before = state.getJSONObject("baseline")
        val changes = mutableListOf<JSONObject>()
        for (key in next.keys()) if (!before.has(key) || !same(before.get(key), next.get(key))) {
            val p = key.split('/')
            changes += change(p[0], p[1], p[2], next.get(key))
        }
        val current = next.keys().asSequence().map { it.substringBeforeLast('/') }.toSet()
        for (entity in before.keys().asSequence().map { it.substringBeforeLast('/') }.distinct()) if (entity !in current) {
            val p = entity.split('/')
            changes += change(p[0], p[1], "\$deleted", true)
        }
        for (chunk in changes.chunked(16)) {
            val batch = JSONObject().put("protocol", 1).put("id", uuid()).put("actor", state.getString("actor"))
                .put("clock", Math.addExact(state.getLong("clock"), 1)).put("changes", JSONArray(chunk))
            apply(batch); pending.put(batch)
        }
        state.put("baseline", next)
    }
    fun apply(batch: JSONObject) {
        val actor = batch.getString("actor")
        val clock = batch.getLong("clock")
        val changes = batch.getJSONArray("changes")
        require(batch.getInt("protocol") == 1 && idValid(batch.getString("id")) && idValid(actor) &&
            clock in 1..9000000000000000 && changes.length() in 1..256)
        for (i in 0 until changes.length()) validate(changes.getJSONObject(i))
        for (i in 0 until changes.length()) {
            val c = changes.getJSONObject(i)
            val key = "${c.getString("kind")}/${c.getString("id")}/${c.getString("field")}"
            val old = registers.optJSONObject(key)
            if (old == null || clock > old.getLong("clock") || clock == old.getLong("clock") && actor > old.getString("actor"))
                registers.put(key, JSONObject().put("clock", clock).put("actor", actor).put("value", copy(c.get("value"))))
        }
        state.put("clock", maxOf(state.getLong("clock"), clock))
    }
    fun project(): JSONObject {
        val snapshot = JSONObject()
        for ((kind, columns) in fields) {
            val entities = JSONArray()
            val grouped = registers.keys().asSequence().filter { it.startsWith("$kind/") }.groupBy { it.split('/')[1] }
            for ((id, keys) in grouped.toSortedMap()) {
                if (registers.has("$kind/$id/\$deleted")) continue
                val entity = JSONObject().put("id", id)
                for (key in keys) entity.put(key.split('/')[2], copy(registers.getJSONObject(key).get("value")))
                if (columns.all(entity::has)) entities.put(entity)
            }
            snapshot.put(kind, entities)
        }
        return snapshot
    }
    fun acknowledge(applied: JSONObject) { state.put("baseline", flatten(applied)) }
    companion object {
        private val fields = linkedMapOf("folders" to listOf("name", "note", "columns"),
            "images" to listOf("folderId", "manifest", "sourceUrl", "width", "height", "createdAt", "order"))
        private fun uuid() = UUID.randomUUID().toString().replace("-", "")
        private fun idValid(id: String) = id.matches(Regex("[0-9a-f]{32}")) && id != "0".repeat(32)
        private fun copy(value: Any): Any = when (value) { is JSONObject -> JSONObject(value.toString()); is JSONArray -> JSONArray(value.toString()); else -> value }
        internal fun same(a: Any, b: Any): Boolean = when {
            a is JSONObject && b is JSONObject -> a.keys().asSequence().toSet() == b.keys().asSequence().toSet() && a.keys().asSequence().all { same(a.get(it), b.get(it)) }
            a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { same(a.get(it), b.get(it)) }
            else -> a == b
        }
        private fun change(kind: String, id: String, field: String, value: Any) =
            JSONObject().put("kind", kind).put("id", id).put("field", field).put("value", copy(value))
        private fun validate(c: JSONObject) {
            val kind = c.getString("kind"); val field = c.getString("field"); val value = c.get("value")
            require(idValid(c.getString("id")) && fields.containsKey(kind) && (field == "\$deleted" || field in fields.getValue(kind)))
            fun number(min: Long, max: Long) { require(value is Number && value.toString().toLongOrNull()?.let { it in min..max } == true) }
            when (field) {
                "\$deleted" -> require(value == true)
                "name" -> require(value is String && value.isNotBlank() && value.length <= 512)
                "note" -> require(value is String && value.length <= 4000)
                "sourceUrl" -> require(value is String && value.length <= 8192)
                "folderId" -> require(value is String && idValid(value))
                "columns" -> number(1, 6)
                "width", "height" -> number(1, 1000000)
                "order" -> number(0, 1000000)
                "createdAt" -> number(0, 9000000000000000)
                "manifest" -> require(value is JSONObject && value.toString().length <= 131072)
            }
        }
        private fun flatten(snapshot: JSONObject): JSONObject {
            val flat = JSONObject()
            for ((kind, columns) in fields) {
                val entities = snapshot.getJSONArray(kind)
                require(entities.length() <= 100000)
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i); val id = entity.getString("id")
                    for (field in columns) {
                        val value = entity.get(field)
                        validate(change(kind, id, field, value))
                        val key = "$kind/$id/$field"
                        require(!flat.has(key)); flat.put(key, copy(value))
                    }
                }
            }
            return flat
        }
    }
}
