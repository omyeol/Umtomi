package xyz.sabok.umtomi.ui

/** Portable, relative shelf paths. Never accept absolute paths or traversal. */
internal object BookshelfPath {
    fun normalize(value: String): String? {
        val parts = value.trim().split('/')
        if (parts.isEmpty() || parts.size > 16 || parts.first().lowercase(java.util.Locale.ROOT) in setOf(".omyaru", ".omyaru-control")) return null
        if (parts.any { part -> part.isBlank() || part != part.trim() || part in listOf(".", "..") ||
                part.length > 100 || part.endsWith('.') || part.any { it.code < 32 || it in "\\:*?\"<>|" } ||
                part.substringBefore('.').uppercase(java.util.Locale.ROOT) in reserved }) return null
        return parts.joinToString("/").takeIf { it.length <= 512 }
    }
    private val reserved = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }
    fun contains(parent: String, path: String): Boolean = path.equals(parent, true) || path.startsWith("$parent/", true)
    fun parent(path: String): String = path.substringBeforeLast('/', "")
    fun leaf(path: String): String = path.substringAfterLast('/')
    fun children(paths: List<String>, parent: String): List<String> = paths.mapNotNull { path ->
        val rest = if (parent.isEmpty()) path else if (path.startsWith("$parent/", true)) path.substring(parent.length + 1) else return@mapNotNull null
        if (parent.isEmpty()) rest.substringBefore('/') else "$parent/${rest.substringBefore('/')}"
    }.distinctBy { it.lowercase(java.util.Locale.ROOT) }

    fun moveSibling(paths: List<String>, folder: String, direction: Int): List<String>? {
        val parent = parent(folder)
        val siblings = children(paths, parent).toMutableList()
        val index = siblings.indexOfFirst { it.equals(folder, true) }
        val target = index + direction
        if (index < 0 || target !in siblings.indices) return null
        java.util.Collections.swap(siblings, index, target)
        val reordered = mutableListOf<String>()
        fun visit(path: String) {
            paths.firstOrNull { it.equals(path, true) }?.let(reordered::add)
            val next = if (path.equals(parent, true)) siblings else children(paths, path)
            next.forEach(::visit)
        }
        (if (parent.isEmpty()) siblings else children(paths, "")).forEach(::visit)
        return reordered.takeIf { it.size == paths.size }
    }

    fun relocation(paths: List<String>, source: String, destination: String): Map<String, String>? {
        val target = normalize(destination) ?: return null
        if (target.startsWith("$source/", true)) return null
        val moving = paths.filter { contains(source, it) }
        if (moving.isEmpty()) return null
        val result = moving.associateWith { target + it.substring(source.length) }
        if (result.values.any { normalize(it) == null }) return null
        if (paths.any { it !in moving && result.values.any { dest -> contains(dest, it) || dest.equals(it, true) } }) return null
        return result
    }
}
