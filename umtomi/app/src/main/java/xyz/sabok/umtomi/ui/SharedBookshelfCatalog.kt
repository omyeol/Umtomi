package xyz.sabok.umtomi.ui

import org.json.JSONArray
import org.json.JSONObject

/** Three-way catalog merge, keyed by persistent IDs, not names or file paths. */
internal object SharedBookshelfCatalog {
    private fun folders(json: JSONObject): Map<String, JSONObject> {
        val names = json.optJSONArray("folders") ?: JSONArray()
        val ids = json.optJSONObject("folderIds") ?: JSONObject()
        return (0 until names.length()).associate { index ->
            val name = names.getString(index)
            val id = ids.getString(name)
            require(id.matches(Regex("[0-9a-f]{32}")))
            id to JSONObject().put("name", name).put("note", json.optJSONObject("folderNotes")?.optString(name).orEmpty())
                .put("columns", json.optJSONObject("folderColumnCounts")?.optInt(name, 2) ?: 2).put("order", index)
        }
    }
    private fun images(json: JSONObject): Map<String, JSONObject> {
        val rows = json.optJSONArray("items") ?: JSONArray()
        val ids = json.optJSONObject("folderIds") ?: JSONObject()
        return (0 until rows.length()).associate { index ->
            val row = JSONObject(rows.getJSONObject(index).toString())
            row.put("folderId", ids.getString(row.getString("folder")))
            row.remove("folder")
            row.getString("id") to row
        }
    }
    private fun same(a: Any?, b: Any?): Boolean = a?.toString() == b?.toString()
    private fun mergeRows(base: Map<String, JSONObject>, edited: Map<String, JSONObject>, latest: Map<String, JSONObject>, deleted: MutableSet<String>): Map<String, JSONObject> {
        deleted += base.keys - edited.keys
        val merged = latest.mapValues { JSONObject(it.value.toString()) }.toMutableMap()
        edited.forEach { (id, desired) ->
            if (id in deleted) return@forEach
            val before = base[id]
            val current = merged[id]
            // Do not resurrect rows deleted since this editor loaded its snapshot.
            if (before != null && current == null) return@forEach
            if (before == null && current == null) merged[id] = JSONObject(desired.toString())
            else if (current != null) desired.keys().forEach { key ->
                if (before != null && !same(before.opt(key), desired.opt(key))) current.put(key, desired.get(key))
            }
        }
        deleted.forEach(merged::remove)
        return merged
    }
    fun merge(base: JSONObject, edited: JSONObject, latest: JSONObject): JSONObject {
        val deletedFolders = strings(latest.optJSONArray("deletedFolderIds")).apply { addAll(strings(edited.optJSONArray("deletedFolderIds"))) }
        val deletedImages = strings(latest.optJSONArray("deletedImageIds")).apply { addAll(strings(edited.optJSONArray("deletedImageIds"))) }
        val mergedFolders = mergeRows(folders(base), folders(edited), folders(latest), deletedFolders)
        val mergedImages = mergeRows(images(base), images(edited), images(latest), deletedImages)
        val names = mutableMapOf<String, String>()
        mergedFolders.entries.sortedWith(compareBy<Map.Entry<String, JSONObject>> { it.value.optInt("order", Int.MAX_VALUE) }.thenBy { it.key }).forEach { (id, value) ->
            var name = value.getString("name")
            require(BookshelfPath.normalize(name) != null) { "Invalid shelf path" }
            if (names.values.any { it.equals(name, true) }) name = name.take(480) + " (${id.take(8)})"
            require(BookshelfPath.normalize(name) != null)
            names[id] = name
        }
        val ids = JSONObject(); val notes = JSONObject(); val columns = JSONObject()
        names.forEach { (id, name) ->
            ids.put(name, id); notes.put(name, mergedFolders.getValue(id).getString("note"))
            columns.put(name, mergedFolders.getValue(id).getInt("columns"))
        }
        val items = JSONArray()
        // LinkedHashMap order follows the current catalog; sorting by ID reshuffles photos.
        mergedImages.forEach { (id, row) ->
            val folder = names[row.getString("folderId")]
            if (folder == null) deletedImages += id
            else { row.remove("folderId"); row.put("folder", folder); items.put(row) }
        }
        val activeId = edited.optJSONObject("folderIds")?.optString(edited.optString("activeFolder"))
        return JSONObject().put("folders", JSONArray(names.values.toList())).put("folderIds", ids)
            .put("folderNotes", notes).put("folderColumnCounts", columns).put("items", items)
            .put("activeFolder", names[activeId] ?: names.values.firstOrNull().orEmpty())
            .put("deletedFolderIds", JSONArray(deletedFolders.sorted())).put("deletedImageIds", JSONArray(deletedImages.sorted()))
            .put("revision", latest.optLong("revision") + 1)
    }
    private fun strings(array: JSONArray?): MutableSet<String> = (0 until (array?.length() ?: 0)).map { array!!.getString(it) }.toMutableSet()
}
