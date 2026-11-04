package app.banzhan.deepseekcontinuation.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManifestTest {
    @Test
    fun validManifestNormalizesHashAndSurvivesCacheSerialization() {
        val parsed = AppUpdateManifestParser.parse(manifest(mapOf("sha256" to JsonPrimitive("AB".repeat(32)))))
        assertEquals(7, parsed.versionCode)
        assertEquals("ab".repeat(32), parsed.sha256)
        assertEquals(1024L, parsed.apkSize)
        assertEquals("2026-11-04T12:34:56Z", parsed.releasedAt)
        assertEquals(parsed, AppUpdateManifestParser.parse(Json.encodeToString(parsed)))
        assertEquals("https://digimonlegend.com/continuation-updates/latest.json", AppUpdateManifestParser.MANIFEST_URL)
    }

    @Test
    fun releaseAvailabilityRequiresANewerVersionAndSupportedSdk() {
        val parsed = AppUpdateManifestParser.parse(manifest())
        assertTrue(AppUpdateManifestParser.isAvailable(parsed, 6, 26))
        assertFalse(AppUpdateManifestParser.isAvailable(parsed, 7, 35))
        assertFalse(AppUpdateManifestParser.isAvailable(parsed, 8, 35))
        assertFalse(AppUpdateManifestParser.isAvailable(parsed, 6, 25))
        assertFalse(AppUpdateManifestParser.isAvailable(parsed.copy(packageName = "other.application"), 6, 35))
    }

    @Test
    fun unknownSchemaMissingFieldsAndWrongPrimitiveTypesAreRejected() {
        listOf(
            mapOf("schemaVersion" to JsonPrimitive(2)),
            mapOf("schemaVersion" to JsonPrimitive(1.0)),
            mapOf("packageName" to JsonPrimitive("other.application")),
            mapOf("versionCode" to JsonPrimitive("7")),
            mapOf("apkSize" to JsonPrimitive("1024")),
            mapOf("minSdk" to JsonPrimitive(true)),
            mapOf("versionName" to JsonNull),
            mapOf("changelog" to JsonPrimitive("a string")),
            mapOf("unexpected" to JsonPrimitive("field"))
        ).forEach { overrides -> assertThrows(Exception::class.java) { AppUpdateManifestParser.parse(manifest(overrides)) } }
        val missing = JsonObject(fields().filterKeys { it != "sha256" }).toString()
        assertThrows(Exception::class.java) { AppUpdateManifestParser.parse(missing) }
    }

    @Test
    fun numericBoundariesNamesHashesAndReleaseTimeAreValidated() {
        listOf(
            mapOf("versionCode" to JsonPrimitive(0)),
            mapOf("versionCode" to JsonPrimitive(2_147_483_648L)),
            mapOf("versionName" to JsonPrimitive(" ")),
            mapOf("versionName" to JsonPrimitive("v".repeat(81))),
            mapOf("apkSize" to JsonPrimitive(0)),
            mapOf("apkSize" to JsonPrimitive(AppUpdateManifestParser.MAX_APK_BYTES + 1)),
            mapOf("apkSize" to JsonPrimitive(1024.0)),
            mapOf("sha256" to JsonPrimitive("a".repeat(63))),
            mapOf("sha256" to JsonPrimitive("g".repeat(64))),
            mapOf("minSdk" to JsonPrimitive(25)),
            mapOf("minSdk" to JsonPrimitive(10001)),
            mapOf("releasedAt" to JsonPrimitive("2026-11-04"))
        ).forEach { overrides -> assertThrows(Exception::class.java) { AppUpdateManifestParser.parse(manifest(overrides)) } }
        assertEquals(AppUpdateManifestParser.MAX_APK_BYTES,
            AppUpdateManifestParser.parse(manifest(mapOf("apkSize" to JsonPrimitive(AppUpdateManifestParser.MAX_APK_BYTES)))).apkSize)
        assertEquals(1L, AppUpdateManifestParser.parse(manifest(mapOf("apkSize" to JsonPrimitive(1)))).apkSize)
    }

    @Test
    fun apkOriginAndPathCannotBeChangedOrTraversed() {
        val invalid = listOf(
            "http://digimonlegend.com/continuation-updates/apks/release.apk",
            "https://other.example/continuation-updates/apks/release.apk",
            "https://digimonlegend.com.evil.example/continuation-updates/apks/release.apk",
            "https://digimonlegend.com:8443/continuation-updates/apks/release.apk",
            "https://user:secret@digimonlegend.com/continuation-updates/apks/release.apk",
            "https://digimonlegend.com/continuation-updates/apks/release.apk?token=secret",
            "https://digimonlegend.com/continuation-updates/apks/release.apk#fragment",
            "https://digimonlegend.com/other/release.apk",
            "https://digimonlegend.com/continuation-updates/apks/../release.apk",
            "https://digimonlegend.com/continuation-updates/apks/./release.apk",
            "https://digimonlegend.com/continuation-updates/apks/%2e%2e/release.apk",
            "https://digimonlegend.com/continuation-updates/apks/.%2E/release.apk",
            "https://digimonlegend.com/continuation-updates/apks/folder%2Frelease.apk",
            "https://digimonlegend.com/continuation-updates/apks/folder%5crelease.apk",
            "https://digimonlegend.com/continuation-updates/apks/%252e%252e/release.apk",
            "https://digimonlegend.com/continuation-updates/apks/folder%252frelease.apk",
            "https://digimonlegend.com/continuation-updates/apks/%00release.apk",
            "https://digimonlegend.com/continuation-updates/apks//release.apk",
            "https://digimonlegend.com/continuation-updates/apks/release.zip"
        )
        invalid.forEach { url -> assertThrows(Exception::class.java) { AppUpdateManifestParser.parse(manifest(mapOf("apkUrl" to JsonPrimitive(url)))) } }
        val explicitPort = "https://DIGIMONLEGEND.COM:443/continuation-updates/apks/preview/release.APK"
        assertEquals(explicitPort, AppUpdateManifestParser.parse(manifest(mapOf("apkUrl" to JsonPrimitive(explicitPort)))).apkUrl)
    }

    @Test
    fun changelogHasBoundedNonblankStringEntriesAndCanBeEmpty() {
        assertTrue(AppUpdateManifestParser.parse(manifest(mapOf("changelog" to JsonArray(emptyList())))).changelog.isEmpty())
        listOf(JsonArray(List(9) { JsonPrimitive("change") }), JsonArray(listOf(JsonPrimitive(" "))),
            JsonArray(listOf(JsonPrimitive("x".repeat(201)))), JsonArray(listOf(JsonPrimitive(1))))
            .forEach { value -> assertThrows(Exception::class.java) { AppUpdateManifestParser.parse(manifest(mapOf("changelog" to value))) } }
    }

    @Test
    fun duplicateKeysIncludingEscapedNamesAreRejected() {
        val base = manifest().dropLast(1)
        listOf("$base,\"versionCode\":8}", "$base,\"\\u0076ersionCode\":8}",
            "$base,\"extra\":{\"x\":1,\"\\u0078\":2}}")
            .forEach { duplicate ->
                val error = assertThrows(Exception::class.java) { AppUpdateManifestParser.parse(duplicate) }
                assertTrue(error.message.orEmpty().contains("重复"))
            }
        val quoted = "Text {\"versionCode\":1} and escaped quote \\\" stay text"
        assertEquals(listOf(quoted), AppUpdateManifestParser.parse(manifest(mapOf("changelog" to JsonArray(listOf(JsonPrimitive(quoted)))))).changelog)
    }

    @Test
    fun byteLimitIs64KiBAndUtf8IsStrict() {
        val bytes = manifest().toByteArray(Charsets.UTF_8)
        val exact = bytes + ByteArray(AppUpdateManifestParser.MAX_MANIFEST_BYTES - bytes.size) { 0x20.toByte() }
        assertEquals(7, AppUpdateManifestParser.parse(exact).versionCode)
        assertEquals(65_536, AppUpdateManifestParser.MAX_MANIFEST_BYTES)
        assertThrows(IllegalArgumentException::class.java) { AppUpdateManifestParser.parse(exact + byteArrayOf(0x20)) }
        assertThrows(IllegalArgumentException::class.java) { AppUpdateManifestParser.parse(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows(IllegalArgumentException::class.java) { AppUpdateManifestParser.parse(manifest() + '\uD800') }
        val tooManyBytes = manifest() + "汉".repeat(30_000)
        val error = assertThrows(IllegalArgumentException::class.java) { AppUpdateManifestParser.parse(tooManyBytes) }
        assertTrue(error.message.orEmpty().contains("大小"))
    }

    @Test
    fun invalidJsonAndExcessiveNestingFailBeforeTreeAllocation() {
        listOf(manifest().dropLast(1) + ",}", "[]", "{", "[}", "\"unterminated")
            .forEach { text -> assertThrows(Exception::class.java) { AppUpdateManifestParser.parse(text) } }
        val deeplyNested = "[".repeat(100) + "0" + "]".repeat(100)
        val error = assertThrows(IllegalArgumentException::class.java) { AppUpdateManifestParser.parse(deeplyNested) }
        assertTrue(error.message.orEmpty().contains("嵌套"))
    }

    private fun manifest(overrides: Map<String, JsonElement> = emptyMap()): String = JsonObject(fields() + overrides).toString()

    private fun fields(): Map<String, JsonElement> = linkedMapOf(
        "schemaVersion" to JsonPrimitive(1),
        "packageName" to JsonPrimitive("app.banzhan.deepseekcontinuation"),
        "versionCode" to JsonPrimitive(7),
        "versionName" to JsonPrimitive("1.0.0-preview.7"),
        "apkUrl" to JsonPrimitive("https://digimonlegend.com/continuation-updates/apks/release.apk"),
        "apkSize" to JsonPrimitive(1024),
        "sha256" to JsonPrimitive("ab".repeat(32)),
        "minSdk" to JsonPrimitive(26),
        "releasedAt" to JsonPrimitive("2026-11-04T12:34:56Z"),
        "changelog" to JsonArray(listOf(JsonPrimitive("Improve import")))
    )
}
