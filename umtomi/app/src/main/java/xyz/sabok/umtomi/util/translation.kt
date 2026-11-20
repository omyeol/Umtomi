/*
 *     Umtomi, Hitomi.la viewer for Android
 *     Copyright (C) 2020  tom5079
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package xyz.sabok.umtomi.util

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import xyz.sabok.umtomi.client
import java.util.Locale

private const val translationDirectory = "tag-translations"

var translations: Map<String, String> = run {
    updateTranslations()
    emptyMap()
}
    private set

fun updateTranslations() = CoroutineScope(Dispatchers.IO).launch {
    translations = emptyMap()
    kotlin.runCatching {
        val language = Preferences["tag_translation", ""].ifEmpty { Locale.getDefault().language }
        if (language !in getAvailableLanguages() || language == "en") return@launch
        val json = xyz.sabok.umtomi.Umtomi.instance.assets
            .open("$translationDirectory/$language.json").bufferedReader().use { it.readText() }
        translations = Json.decodeFromString<Map<String, String>>(json).filterValues { it.isNotEmpty() }
    }
}

fun getAvailableLanguages(): List<String> {
    return listOf("en") + xyz.sabok.umtomi.Umtomi.instance.assets.list(translationDirectory)
        .orEmpty().filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.sorted()
}
