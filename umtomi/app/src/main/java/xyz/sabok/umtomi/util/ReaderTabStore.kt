package xyz.sabok.umtomi.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Reader tabs and their last visible pages, shared by every reader entry point. */
class ReaderTabStore(context: Context) {
    data class Tab(val galleryID: Int, val page: Int)
    private val preferences = context.applicationContext.getSharedPreferences("reader-tabs", Context.MODE_PRIVATE)
    private val tabs = mutableListOf<Tab>()
    var selectedID: Int = preferences.getInt("selected", 0)
        private set

    init {
        runCatching {
            val entries = JSONArray(preferences.getString("tabs", "[]"))
            for (i in 0 until entries.length()) {
                val item = entries.getJSONObject(i)
                val id = item.getInt("galleryID")
                if (id > 0 && tabs.none { it.galleryID == id }) {
                    tabs += Tab(id, item.optInt("page", 0).coerceAtLeast(0))
                }
            }
        }.onFailure { tabs.clear() }
        if (tabs.none { it.galleryID == selectedID }) selectedID = tabs.firstOrNull()?.galleryID ?: 0
    }

    fun all(): List<Tab> = tabs.toList()
    fun page(id: Int) = tabs.firstOrNull { it.galleryID == id }?.page ?: 0

    fun open(id: Int, activate: Boolean = true) {
        require(id > 0)
        if (tabs.none { it.galleryID == id }) tabs += Tab(id, 0)
        if (activate || selectedID == 0) selectedID = id
        save()
    }

    fun select(id: Int) {
        require(tabs.any { it.galleryID == id })
        selectedID = id
        save()
    }

    fun setPage(id: Int, page: Int) {
        val index = tabs.indexOfFirst { it.galleryID == id }
        if (index < 0 || tabs[index].page == page) return
        tabs[index] = tabs[index].copy(page = page.coerceAtLeast(0))
        save()
    }

    fun close(id: Int): Int {
        val index = tabs.indexOfFirst { it.galleryID == id }
        if (index < 0) return selectedID
        tabs.removeAt(index)
        if (selectedID == id) selectedID = tabs.getOrNull(index.coerceAtMost(tabs.lastIndex))?.galleryID ?: 0
        save()
        return selectedID
    }

    private fun save() {
        val json = JSONArray()
        tabs.forEach { json.put(JSONObject().put("galleryID", it.galleryID).put("page", it.page)) }
        preferences.edit().putString("tabs", json.toString()).putInt("selected", selectedID).apply()
    }
}
