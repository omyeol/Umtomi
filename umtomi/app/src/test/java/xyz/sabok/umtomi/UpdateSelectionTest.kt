package xyz.sabok.umtomi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.*
import org.junit.Test
import xyz.sabok.umtomi.util.isNewerVersion
import xyz.sabok.umtomi.util.selectUpdate

class UpdateSelectionTest {
    @Test fun comparesNumericVersionsAndRejectsSameOrOlderVersions() {
        assertTrue(isNewerVersion("v5.10.0", "5.9.9-DEBUG"))
        assertFalse(isNewerVersion("v5.5.0", "5.5.0-DEBUG"))
        assertFalse(isNewerVersion("v5.4.1", "5.5.0-DEBUG"))
        assertFalse(isNewerVersion("garbage", "5.5.0"))
        assertFalse(isNewerVersion("v5.6.0-beta", "5.5.0"))
    }
    private fun release(version: String, extra: String = "", suffix: String = "", host: String = "https://github.com/omyeol/Umtomi-releases") =
        """{"tag_name":"$version",$extra "assets":[{"name":"Umtomi-$version$suffix.apk","browser_download_url":"$host/releases/download/$version/Umtomi-$version$suffix.apk"}]}"""

    @Test fun skipsUninstallableAndPrereleaseAssetsAndChoosesHighestVersion() {
        val json = "[" + listOf(
            release("v6.0.0", "\"prerelease\":true,"),
            release("v6.0.1", "\"draft\":true,"),
            release("v7.0.0", suffix="-debug"),
            release("v7.0.1", host="https://example.com"),
            release("v5.6.0"), release("v5.10.0"), release("v5.5.0")
        ).joinToString(",") + "]"
        val selected = selectUpdate(Json.parseToJsonElement(json).jsonArray, "5.5.0-DEBUG")
        assertEquals("\"v5.10.0\"", selected?.get("tag_name").toString())
        assertNull(selectUpdate(Json.parseToJsonElement("[${release("v5.5.0")}]").jsonArray, "5.5.0-DEBUG"))
    }

    @Test fun acceptsNewReleaseRepositoryAndIgnoresIosTags() {
        val canonical = release("v5.16.0", host = "https://github.com/omyeol/Umtomi")
        val ios = """{"tag_name":"ios-v9.0.0","assets":[{"name":"Umtomi-iOS-9.0.0.ipa"}]}"""
        val result = selectUpdate(Json.parseToJsonElement("[$ios,$canonical]").jsonArray, "5.15.0")
        assertEquals("\"v5.16.0\"", result?.get("tag_name").toString())
    }
}
