package xyz.sabok.umtomi.util

import android.content.Context

/** Persistent 0-based last read page per gallery, matching iOS lastReadPageByGallery. */
internal object LastReadPages {
    private const val PREF_NAME = "last_read_pages"

    fun get(context: Context, galleryID: Int): Int? {
        if (galleryID <= 0) return null
        val page = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt(galleryID.toString(), 0)
        return page.takeIf { it > 0 }
    }

    fun set(context: Context, galleryID: Int, page: Int) {
        if (galleryID <= 0) return
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putInt(galleryID.toString(), page.coerceAtLeast(0)).apply()
    }

    fun clear(context: Context, galleryID: Int) {
        if (galleryID <= 0) return
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().remove(galleryID.toString()).apply()
    }

    fun clearAll(context: Context) {
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    fun readerLoadOrder(pageCount: Int, anchor: Int): List<Int> {
        if (pageCount <= 0) return emptyList()
        val boundedAnchor = anchor.coerceIn(0, pageCount - 1)
        val indices = ArrayList<Int>(pageCount)
        indices.add(boundedAnchor)
        var distance = 1
        while (indices.size < pageCount) {
            val prev = boundedAnchor - distance
            if (prev >= 0) indices.add(prev)
            val next = boundedAnchor + distance
            if (next < pageCount) indices.add(next)
            distance++
        }
        return indices
    }
}
