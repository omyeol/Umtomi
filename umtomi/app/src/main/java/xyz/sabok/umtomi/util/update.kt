package xyz.sabok.umtomi.util

import android.content.Context
import android.content.Intent
import android.webkit.URLUtil
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import xyz.sabok.umtomi.*
import xyz.sabok.umtomi.types.Tag
import xyz.sabok.umtomi.ui.UpdateActivity
import java.io.IOException
import java.util.concurrent.TimeUnit

fun versionNumbers(version: String): List<Int>? =
    Regex("^[vV]?(\\d+)\\.(\\d+)\\.(\\d+)(?:-DEBUG)?$").matchEntire(version)
        ?.groupValues?.drop(1)?.map { it.toIntOrNull() ?: return null }

fun isNewerVersion(candidate: String, installed: String): Boolean {
    val next = versionNumbers(candidate) ?: return false
    val current = versionNumbers(installed) ?: return false
    return next.zip(current).firstOrNull { it.first != it.second }?.let { it.first > it.second } ?: false
}

fun getReleases(url: String): JsonArray {
    val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()
    return client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build().newCall(request).execute().use {
        if (!it.isSuccessful) throw IOException("GitHub HTTP ${it.code()}")
        Json.parseToJsonElement(it.body()?.string() ?: throw IOException("Empty response")).jsonArray
    }
}

private const val ANDROID_CURRENT_RELEASE_REPO = "Umtomi-releases"
private const val ANDROID_NEXT_RELEASE_REPO = "Umtomi"

fun findAndroidUpdate(installed: String): JsonObject? {
    val primary = "https://api.github.com/repos/omyeol/$ANDROID_NEXT_RELEASE_REPO/releases"
    val legacy = "https://api.github.com/repos/omyeol/$ANDROID_CURRENT_RELEASE_REPO/releases"
    // Primary releases live on omyeol/Umtomi; fall back to Umtomi-releases for older tags.
    val candidate = runCatching { selectUpdate(getReleases(primary), installed) }.getOrNull()
    return candidate ?: selectUpdate(getReleases(legacy), installed)
}

fun getApkUrl(release: JsonObject): String? = release["assets"]?.jsonArray?.firstNotNullOfOrNull {
    val asset = it.jsonObject
    val name = asset["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val matchingBuild = if (BuildConfig.APPLICATION_ID.endsWith(".debug")) name.endsWith("-debug.apk") else !name.endsWith("-debug.apk")
    val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
    if (name.startsWith("Umtomi-v") && name.endsWith(".apk") && matchingBuild &&
        listOf(ANDROID_NEXT_RELEASE_REPO, ANDROID_CURRENT_RELEASE_REPO).any { repo ->
            url.startsWith("https://github.com/omyeol/$repo/releases/download/")
        }) url else null
}

fun selectUpdate(releases: JsonArray, installed: String = BuildConfig.VERSION_NAME): JsonObject? =
    releases.map { it.jsonObject }.filter {
        it["draft"]?.jsonPrimitive?.booleanOrNull != true &&
        it["prerelease"]?.jsonPrimitive?.booleanOrNull != true &&
        isNewerVersion(it["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty(), installed) && getApkUrl(it) != null
    }.maxWithOrNull(compareBy<JsonObject>(
        { versionNumbers(it["tag_name"]!!.jsonPrimitive.content)!![0] },
        { versionNumbers(it["tag_name"]!!.jsonPrimitive.content)!![1] },
        { versionNumbers(it["tag_name"]!!.jsonPrimitive.content)!![2] }
    ))

fun installedVersion(context: Context): String =
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

private val updateChecks = java.util.WeakHashMap<FragmentActivity, Boolean>()

fun checkUpdate(context: Context, force: Boolean = false) {
    if (force) {
        context.startActivity(Intent(context, UpdateActivity::class.java))
        return
    }
    val activity = context as? FragmentActivity ?: return
    val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    if (preferences.getLong("ignore_update_until", 0) > System.currentTimeMillis()) return
    if (updateChecks[activity] == true) return
    updateChecks[activity] = true
    activity.lifecycleScope.launch {
      var shown = false
      try {
        val update = withContext(Dispatchers.IO) {
            runCatching { findAndroidUpdate(installedVersion(context)) }.getOrNull()
        } ?: return@launch
        if (activity.isFinishing || activity.isDestroyed ||
            !isNewerVersion(update["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty(), installedVersion(context))) return@launch
        AlertDialog.Builder(activity).setTitle(R.string.update_title)
            .setMessage(update["tag_name"]?.jsonPrimitive?.contentOrNull)
            .setPositiveButton(R.string.updater_open) { _, _ -> checkUpdate(activity, true) }
            .setNegativeButton(R.string.ignore) { _, _ ->
                preferences.edit().putLong("ignore_update_until", System.currentTimeMillis() + 86400000).apply()
            }.show().also { dialog ->
                shown = true
                dialog.setOnDismissListener { updateChecks.remove(activity) }
            }
      } finally {
          // Keep only the visible prompt locked, release failed/cancelled checks.
          if (!shown) updateChecks.remove(activity)
      }
    }
}

fun restore(url: String, onFailure: ((Throwable) -> Unit)? = null, onSuccess: ((Int) -> Unit)? = null) {
    if (!URLUtil.isValidUrl(url)) {
        onFailure?.invoke(IllegalArgumentException())
        return
    }

    val request = Request.Builder()
        .url(url)
        .get()
        .build()

    client.newCall(request).enqueue(object: Callback {
        override fun onFailure(call: Call, e: IOException) {
            onFailure?.invoke(e)
        }

        override fun onResponse(call: Call, response: Response) {
            kotlin.runCatching {
                val data = Json.parseToJsonElement(response.also { if (it.code() != 200) throw IOException() }.body().use { it?.string() } ?: "[]")

                when (data) {
                    is JsonArray -> favorites.addAll(data.map { it.jsonPrimitive.int })
                    is JsonObject -> {
                        val newFavorites = data["favorites"]?.let { Json.decodeFromJsonElement<List<Int>>(it) }.orEmpty()
                        val newFavoriteTags = data["favorite_tags"]?.let { Json.decodeFromJsonElement<List<Tag>>(it) }.orEmpty()

                        favorites.addAll(newFavorites)
                        favoriteTags.addAll(newFavoriteTags)

                        onSuccess?.invoke(favorites.size + favoriteTags.size)
                    }
                    else -> error("data is neither JsonArray or JsonObject")
                }
            }.onFailure { onFailure?.invoke(it) }
        }
    })
}