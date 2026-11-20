package xyz.sabok.umtomi.util

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import xyz.sabok.umtomi.favorites
import xyz.sabok.umtomi.favoriteTags
import xyz.sabok.umtomi.types.Tag
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class FavoriteFolder(
    val id: String = UUID.randomUUID().toString(),
    val parentID: String? = null,
    val name: String,
    val manualGalleryIDs: List<Int> = emptyList(),
    val autoRuleQuery: String = "",
    val createdAt: String = isoDate(),
) {
    fun json() = JSONObject().apply {
        put("id", id); put("parentID", parentID ?: JSONObject.NULL); put("name", name)
        put("manualGalleryIDs", JSONArray(manualGalleryIDs)); put("autoRuleQuery", autoRuleQuery)
        put("createdAt", createdAt)
    }
}

private fun isoDate(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date())

/** Folder membership is separate from the existing global favorites set. */
class FavoriteFolders(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "favorite-folders.json"))
    var folders: List<FavoriteFolder> = emptyList()
        private set
    var artists = JSONArray()
        private set

    init {
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            val json = JSONObject(file.openRead().bufferedReader().use { it.readText() })
            folders = parseFolders(json.optJSONArray("folders") ?: JSONArray())
            artists = json.optJSONArray("favoriteArtists") ?: JSONArray()
        }
    }

    fun save() {
        val bytes = JSONObject().put("folders", JSONArray(folders.map { it.json() }))
            .put("favoriteArtists", artists).toString().toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }

    fun create(name: String, parentID: String?): FavoriteFolder {
        val cleaned = name.trim()
        require(cleaned.isNotEmpty()) { "Folder name is empty" }
        require(parentID == null || folders.any { it.id == parentID })
        require(folders.none { it.parentID == parentID && it.name.equals(cleaned, true) }) { "Folder name already exists" }
        return FavoriteFolder(parentID = parentID, name = cleaned).also { folders = folders + it; save() }
    }

    fun rename(id: String, name: String) {
        val folder = folders.first { it.id == id }
        val cleaned = name.trim()
        require(cleaned.isNotEmpty() && folders.none { it.id != id && it.parentID == folder.parentID && it.name.equals(cleaned, true) }) { "Invalid or duplicate folder name" }
        folders = folders.map { if (it.id == id) it.copy(name = cleaned) else it }; save()
    }

    fun setAutoRule(id: String, query: String) {
        val rule = query.trim()
        require(rule.length <= 512) { "Rule is too long" }
        require(folders.any { it.id == id })
        folders = folders.map { if (it.id == id) it.copy(autoRuleQuery = rule) else it }
        save()
    }

    fun artistNames(): List<String> = (0 until artists.length())
        .mapNotNull { artists.optJSONObject(it)?.optString("name")?.trim()?.takeIf(String::isNotEmpty) }

    fun addArtist(name: String) {
        val cleaned = name.trim()
        require(cleaned.isNotEmpty() && cleaned.length <= 200) { "Invalid artist name" }
        if (artistNames().none { it.replace('_', ' ').equals(cleaned.replace('_', ' '), ignoreCase = true) }) {
            artists.put(JSONObject().put("name", cleaned).put("createdAt", isoDate()))
            save()
        }
    }

    fun removeArtist(name: String) {
        artists = JSONArray((0 until artists.length()).map { artists.getJSONObject(it) }
            .filterNot { it.optString("name") == name })
        save()
    }

    fun delete(id: String) {
        val removed = mutableSetOf(id)
        do { val before = removed.size; folders.filter { it.parentID in removed }.forEach { removed += it.id } }
        while (removed.size != before)
        folders = folders.filterNot { it.id in removed }; save()
    }

    fun assign(galleryID: Int, selected: Set<String>) {
        require(galleryID > 0)
        folders = folders.map { folder ->
            folder.copy(manualGalleryIDs = if (folder.id in selected) (folder.manualGalleryIDs + galleryID).distinct()
                else folder.manualGalleryIDs.filterNot { it == galleryID })
        }; save()
    }

    fun addToFolder(folderID: String, ids: Collection<Int>) {
        folders = folders.map { if (it.id == folderID) it.copy(manualGalleryIDs = (it.manualGalleryIDs + ids).filter { id -> id > 0 }.distinct()) else it }; save()
    }

    fun setMembers(folderID: String, ids: Collection<Int>) {
        folders = folders.map { if (it.id == folderID) it.copy(manualGalleryIDs = ids.filter { id -> id > 0 }.distinct()) else it }
        save()
    }

    fun removeFromFolder(folderID: String, galleryID: Int) {
        folders = folders.map {
            if (it.id == folderID) it.copy(manualGalleryIDs = it.manualGalleryIDs.filterNot { id -> id == galleryID })
            else it
        }
        save()
    }

    fun saveFolder(
        id: String?,
        parentID: String?,
        name: String,
        autoRuleQuery: String,
        manualGalleryIDs: List<Int>,
    ): FavoriteFolder {
        val cleanedName = name.trim()
        require(cleanedName.isNotEmpty()) { "Folder name is empty" }
        val cleanedRule = autoRuleQuery.trim()
        require(cleanedRule.length <= 512) { "Rule is too long" }
        val validIDs = manualGalleryIDs.filter { it > 0 }.distinct()

        return if (id == null) {
            require(parentID == null || folders.any { it.id == parentID })
            require(folders.none { it.parentID == parentID && it.name.equals(cleanedName, true) }) { "Folder name already exists" }
            FavoriteFolder(
                parentID = parentID,
                name = cleanedName,
                manualGalleryIDs = validIDs,
                autoRuleQuery = cleanedRule,
            ).also { folders = folders + it; save() }
        } else {
            val existing = folders.first { it.id == id }
            require(folders.none { it.id != id && it.parentID == existing.parentID && it.name.equals(cleanedName, true) }) { "Invalid or duplicate folder name" }
            val updated = existing.copy(
                name = cleanedName,
                manualGalleryIDs = validIDs,
                autoRuleQuery = cleanedRule,
            )
            folders = folders.map { if (it.id == id) updated else it }
            save()
            updated
        }
    }

    fun path(folder: FavoriteFolder): String {
        val names = mutableListOf(folder.name)
        val visited = mutableSetOf(folder.id)
        var parent = folder.parentID
        while (parent != null && visited.add(parent)) {
            val item = folders.find { it.id == parent } ?: break
            names.add(0, item.name); parent = item.parentID
        }
        return names.joinToString(" / ")
    }

    companion object {
        fun parseFolders(array: JSONArray): List<FavoriteFolder> {
            val result = (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                val id = UUID.fromString(item.getString("id")).toString()
                FavoriteFolder(id, item.optString("parentID").takeIf { it.isNotBlank() && it != "null" }?.let { UUID.fromString(it).toString() },
                    item.getString("name").trim().also { require(it.isNotEmpty()) },
                    ids(item.optJSONArray("manualGalleryIDs") ?: JSONArray()), item.optString("autoRuleQuery"),
                    item.optString("createdAt").ifBlank { isoDate() })
            }
            require(result.map { it.id }.distinct().size == result.size) { "Duplicate folder IDs" }
            val byID = result.associateBy { it.id }
            result.forEach { folder ->
                val visited = mutableSetOf(folder.id)
                var parent = folder.parentID
                while (parent != null) {
                    require(visited.add(parent)) { "Cyclic folder hierarchy" }
                    val ancestor = byID[parent] ?: error("Unknown parent folder")
                    parent = ancestor.parentID
                }
            }
            return result
        }

        fun ids(array: JSONArray): List<Int> = (0 until array.length()).map { array.getInt(it).also { id -> require(id > 0) } }.distinct()
    }

    fun replace(imported: List<FavoriteFolder>, importedArtists: JSONArray) {
        folders = parseFolders(JSONArray(imported.map { it.json() }))
        artists = JSONArray(importedArtists.toString())
        save()
    }

    fun merge(imported: List<FavoriteFolder>, importedArtists: JSONArray) {
        val merged = folders.associateBy { it.id }.toMutableMap()
        imported.forEach { incoming ->
            val old = merged[incoming.id]
            merged[incoming.id] = if (old == null) incoming else incoming.copy(manualGalleryIDs = (old.manualGalleryIDs + incoming.manualGalleryIDs).distinct())
        }
        val checked = parseFolders(JSONArray(merged.values.map { it.json() }))
        folders = checked
        val artistsByName = linkedMapOf<String, JSONObject>()
        for (array in listOf(artists, importedArtists)) for (i in 0 until array.length()) {
            val artist = array.getJSONObject(i); artistsByName[artist.getString("name").lowercase(Locale.ROOT)] = artist
        }
        artists = JSONArray(artistsByName.values.toList()); save()
    }
}

object FavoritesFiles {
    data class Imported(val ids: List<Int>, val tags: List<Tag>, val folders: List<FavoriteFolder>, val artists: JSONArray)

    fun parse(text: String): Imported {
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) return Imported(FavoriteFolders.ids(JSONArray(trimmed)), emptyList(), emptyList(), JSONArray())
        val json = JSONObject(trimmed)
        require(json.has("favorites")) { "Not a favorites backup" }
        val folderList = FavoriteFolders.parseFolders(json.optJSONObject("favoriteLibrary")?.optJSONArray("folders") ?: JSONArray())
        val ids = (FavoriteFolders.ids(json.getJSONArray("favorites")) + folderList.flatMap { it.manualGalleryIDs }).distinct()
        val tags = json.optJSONArray("favorite_tags")?.let { Json.decodeFromString<List<Tag>>(it.toString()) }.orEmpty()
        val artists = json.optJSONArray("favoriteArtists") ?: JSONArray()
        for (i in 0 until artists.length()) require(artists.getJSONObject(i).getString("name").isNotBlank())
        return Imported(ids, tags, folderList, artists)
    }

    fun export(context: Context): String {
        val library = FavoriteFolders(context)
        val allIDs = synchronized(favorites) { favorites.toList() }
        return JSONObject().apply {
            put("appName", "Umtomi"); put("exportedAt", isoDate()); put("favorites", JSONArray(allIDs))
            put("favorite_tags", JSONArray(Json.encodeToString(favoriteTags.serializer, favoriteTags.toList())))
            put("favoriteLibrary", JSONObject().put("folders", JSONArray(library.folders.map { folder ->
                folder.copy(manualGalleryIDs = folder.manualGalleryIDs.filter { it in allIDs }).json()
            })))
            put("favoriteArtists", library.artists)
        }.toString(2)
    }

    fun replace(context: Context, text: String) {
        val data = parse(text)
        FavoriteFolders(context).replace(data.folders, data.artists)
        favorites.replaceWith(data.ids)
        favoriteTags.replaceWith(data.tags)
    }

    fun restore(context: Context, text: String): Int {
        val data = parse(text) // Validate before mutating current favorites.
        FavoriteFolders(context).merge(data.folders, data.artists)
        favorites.addAll(data.ids); favoriteTags.addAll(data.tags)
        return data.ids.size
    }
}
