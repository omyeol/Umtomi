package xyz.sabok.umtomi.ui

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import xyz.sabok.umtomi.hitomi.GalleryInfo
import xyz.sabok.umtomi.hitomi.SortMode
import xyz.sabok.umtomi.hitomi.doSearch
import xyz.sabok.umtomi.hitomi.getGalleryInfo
import xyz.sabok.umtomi.util.downloader.Cache

internal object RecommendationEngine {
    data class SearchSeed(
        val query: String,
        val baseScore: Double,
        val rankWeight: Double,
        val resultLimit: Int,
    )

    class Profile(seedInfos: List<GalleryInfo>) {
        val artists: Set<String> = seedInfos.flatMap { it.artists.orEmpty().map { a -> normalizeMatchValue(a.artist) } }.toSet()
        val groups: Set<String> = seedInfos.flatMap { it.groups.orEmpty().map { g -> normalizeMatchValue(g.group) } }.toSet()
        val series: Set<String> = seedInfos.flatMap { it.parodys.orEmpty().map { p -> normalizeMatchValue(p.parody) } }.toSet()
        val characters: Set<String> = seedInfos.flatMap { it.characters.orEmpty().map { c -> normalizeMatchValue(c.character) } }.toSet()
        val tags: Set<String> = seedInfos.flatMap { it.tags.orEmpty().map { t -> normalizeMatchValue(t.tag) } }.toSet()

        val topArtists: List<String> = topValues(seedInfos.flatMap { it.artists.orEmpty().map { a -> normalizeMatchValue(a.artist) } }, 4)
        val topGroups: List<String> = topValues(seedInfos.flatMap { it.groups.orEmpty().map { g -> normalizeMatchValue(g.group) } }, 3)
        val topSeries: List<String> = topValues(seedInfos.flatMap { it.parodys.orEmpty().map { p -> normalizeMatchValue(p.parody) } }, 3)
        val topCharacters: List<String> = topValues(seedInfos.flatMap { it.characters.orEmpty().map { c -> normalizeMatchValue(c.character) } }, 3)
        val topTags: List<String> = topValues(seedInfos.flatMap { it.tags.orEmpty().map { t -> normalizeMatchValue(t.tag) } }, 8)
        val topLanguage: String? = topValues(seedInfos.map { normalizeMatchValue(it.language.orEmpty().ifBlank { "unknown" }) }, 1).firstOrNull()
        val topType: String? = topValues(seedInfos.map { normalizeMatchValue(it.type) }, 1).firstOrNull()

        private fun topValues(values: List<String>, limit: Int): List<String> {
            val counts = mutableMapOf<String, Int>()
            for (v in values) {
                if (v.isNotEmpty()) counts[v] = (counts[v] ?: 0) + 1
            }
            return counts.entries.sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }
            ).take(limit).map { it.key }
        }
    }

    fun normalizeMatchValue(value: String): String =
        value.lowercase().replace('_', ' ').trim()

    fun tagTerms(raw: String): List<String> =
        raw.replace('，', ',').split(',').mapNotNull { term ->
            val cleaned = term.trim().trimStart('-').trim().replace(Regex("\\s+"), "_")
            cleaned.takeIf { it.isNotEmpty() }
        }

    fun matchesPersistentTagFilters(info: GalleryInfo, includedRaw: String, excludedRaw: String): Boolean {
        val included = tagTerms(includedRaw)
        val excluded = tagTerms(excludedRaw)
        if (included.any { !matchesFilterTerm(it, info) }) return false
        if (excluded.any { matchesFilterTerm(it, info) }) return false
        return true
    }

    fun matchesFilterTerm(term: String, info: GalleryInfo): Boolean {
        val normalized = normalizeMatchValue(term)
        if (normalized.isEmpty()) return true
        val parts = normalized.split(':', limit = 2)
        val namespace = if (parts.size == 2) parts[0] else "global"
        val value = if (parts.size == 2) parts[1] else normalized

        val tagNames = info.tags.orEmpty().map { normalizeMatchValue(it.tag) }.toSet()
        val femaleTags = info.tags.orEmpty().filter { !it.female.isNullOrEmpty() }.map { normalizeMatchValue(it.tag) }.toSet()
        val maleTags = info.tags.orEmpty().filter { !it.male.isNullOrEmpty() }.map { normalizeMatchValue(it.tag) }.toSet()
        val artists = info.artists.orEmpty().map { normalizeMatchValue(it.artist) }.toSet()
        val groups = info.groups.orEmpty().map { normalizeMatchValue(it.group) }.toSet()
        val series = info.parodys.orEmpty().map { normalizeMatchValue(it.parody) }.toSet()
        val characters = info.characters.orEmpty().map { normalizeMatchValue(it.character) }.toSet()
        val language = normalizeMatchValue(info.language.orEmpty())
        val type = normalizeMatchValue(info.type)
        val title = normalizeMatchValue(info.title)
        val japaneseTitle = normalizeMatchValue(info.japanese_title.orEmpty())

        return when (namespace) {
            "language" -> language == value || value in language
            "female" -> value in femaleTags
            "male" -> value in maleTags
            "tag" -> value in tagNames
            "artist" -> value in artists
            "group" -> value in groups
            "parody", "series" -> value in series
            "character" -> value in characters
            "type" -> type == value || value in type
            else -> value in title || value in japaneseTitle || value in language || value in type ||
                value in tagNames || value in femaleTags || value in maleTags ||
                value in artists || value in groups || value in series || value in characters
        }
    }

    fun similarityScore(info: GalleryInfo, profile: Profile): Double {
        var score = 0.0
        val language = normalizeMatchValue(info.language.orEmpty().ifBlank { "unknown" })
        if (profile.topLanguage != null && language == profile.topLanguage) score += 18.0
        if (profile.topType != null && normalizeMatchValue(info.type) == profile.topType) score += 12.0

        val artists = info.artists.orEmpty().map { normalizeMatchValue(it.artist) }.toSet()
        val groups = info.groups.orEmpty().map { normalizeMatchValue(it.group) }.toSet()
        val series = info.parodys.orEmpty().map { normalizeMatchValue(it.parody) }.toSet()
        val characters = info.characters.orEmpty().map { normalizeMatchValue(it.character) }.toSet()
        val tags = info.tags.orEmpty().map { normalizeMatchValue(it.tag) }.toSet()

        score += artists.intersect(profile.artists).size * 26.0
        score += groups.intersect(profile.groups).size * 22.0
        score += series.intersect(profile.series).size * 28.0
        score += characters.intersect(profile.characters).size * 14.0
        score += tags.intersect(profile.tags).size * 8.0
        return score
    }

    fun searchSeeds(profile: Profile): List<SearchSeed> {
        val seeds = mutableListOf<SearchSeed>()
        val seenQueries = mutableSetOf<String>()
        fun append(prefix: String, values: List<String>, baseScore: Double, scoreStep: Double, rankWeight: Double, resultLimit: Int) {
            values.forEachIndexed { index, value ->
                val query = "$prefix:${normalizeMatchValue(value).replace(' ', '_')}"
                if (seenQueries.add(query)) {
                    seeds += SearchSeed(query, maxOf(baseScore - index * scoreStep, 6.0), rankWeight, resultLimit)
                }
            }
        }
        append("series", profile.topSeries.take(3), 72.0, 8.0, 0.90, 80)
        append("artist", profile.topArtists.take(4), 66.0, 6.0, 0.82, 90)
        append("group", profile.topGroups.take(3), 54.0, 6.0, 0.64, 70)
        append("character", profile.topCharacters.take(3), 40.0, 5.0, 0.48, 60)
        append("tag", profile.topTags.take(8), 24.0, 2.0, 0.28, 55)
        profile.topLanguage?.takeIf { it != "unknown" }?.let {
            append("language", listOf(it), 12.0, 0.0, 0.14, 120)
        }
        return seeds
    }
}

private suspend fun loadInfos(context: Context?, ids: List<Int>): List<GalleryInfo> = coroutineScope {
    val results = mutableListOf<GalleryInfo>()
    for (group in ids.chunked(6)) {
        val loaded = group.map { id ->
            async(Dispatchers.IO) {
                try {
                    if (context != null) Cache.getInstance(context, id).getGalleryInfo()
                    else getGalleryInfo(id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
        }.mapNotNull { it.await() }
        results += loaded
    }
    results
}

internal suspend fun workspaceRecommendations(
    history: List<Int>,
    favorites: List<Int>,
    maximum: Int = 30,
): List<Int> = workspaceRecommendations(null, history, favorites, maximum)

internal suspend fun workspaceRecommendations(
    context: Context?,
    history: List<Int>,
    favorites: List<Int>,
    maximum: Int = 30,
    includedTags: String = "",
    excludedTags: String = "",
): List<Int> = coroutineScope {
    val desiredCount = maximum.coerceAtLeast(5)
    val historySeeds = history.take(18)
    val favoriteSeeds = favorites.take(18)
    val seedIDs = (historySeeds + favoriteSeeds).distinct()
    if (seedIDs.isEmpty()) return@coroutineScope emptyList()

    val seedInfos = loadInfos(context, seedIDs)
    if (seedInfos.isEmpty()) return@coroutineScope emptyList()

    val seedIDSet = seedIDs.toSet()
    val seedInfosByID = seedInfos.associateBy { it.id.toIntOrNull() ?: 0 }
    val profile = RecommendationEngine.Profile(seedInfos)
    val candidateScores = mutableMapOf<Int, Double>()

    historySeeds.forEachIndexed { index, galleryID ->
        val info = seedInfosByID[galleryID] ?: return@forEachIndexed
        val weight = maxOf(1, 18 - index) * 1.8
        for (relatedID in info.related.take(20)) {
            if (relatedID > 0 && relatedID !in seedIDSet) {
                candidateScores[relatedID] = (candidateScores[relatedID] ?: 0.0) + 120.0 + weight
            }
        }
    }

    favoriteSeeds.forEachIndexed { index, galleryID ->
        val info = seedInfosByID[galleryID] ?: return@forEachIndexed
        val weight = maxOf(1, 18 - index) * 1.4
        for (relatedID in info.related.take(20)) {
            if (relatedID > 0 && relatedID !in seedIDSet) {
                candidateScores[relatedID] = (candidateScores[relatedID] ?: 0.0) + 96.0 + weight
            }
        }
    }

    val searchSeeds = RecommendationEngine.searchSeeds(profile)
    if (searchSeeds.isNotEmpty()) {
        val batches = searchSeeds.map { seed ->
            async(Dispatchers.IO) {
                try {
                    val ids = doSearch(seed.query, SortMode.DATE_ADDED)
                    ids.take(seed.resultLimit).mapIndexed { index, id ->
                        val rankScore = maxOf(seed.resultLimit - index, 1) * seed.rankWeight
                        id to (seed.baseScore + rankScore)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }.flatMap { it.await() }

        for ((candidateID, score) in batches) {
            if (candidateID > 0 && candidateID !in seedIDSet) {
                candidateScores[candidateID] = (candidateScores[candidateID] ?: 0.0) + score
            }
        }
    }

    val candidateIDs = candidateScores.entries
        .sortedWith(compareByDescending<Map.Entry<Int, Double>> { it.value }.thenByDescending { it.key })
        .map { it.key }
    if (candidateIDs.isEmpty()) return@coroutineScope emptyList()

    val fetchLimit = (desiredCount * 6).coerceIn(120, 320)
    val candidateInfos = loadInfos(context, candidateIDs.take(fetchLimit))

    candidateInfos.mapNotNull { info ->
        val id = info.id.toIntOrNull() ?: 0
        if (id <= 0 || id in seedIDSet) return@mapNotNull null
        if (!RecommendationEngine.matchesPersistentTagFilters(info, includedTags, excludedTags)) return@mapNotNull null
        id to ((candidateScores[id] ?: 0.0) + RecommendationEngine.similarityScore(info, profile))
    }.sortedWith(
        compareByDescending<Pair<Int, Double>> { it.second }.thenByDescending { it.first }
    ).take(desiredCount).map { it.first }
}
