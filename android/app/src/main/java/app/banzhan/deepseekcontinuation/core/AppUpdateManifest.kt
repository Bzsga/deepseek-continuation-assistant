@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package app.banzhan.deepseekcontinuation.core

import java.net.URI
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

@Serializable
data class AppUpdateManifest(
    val schemaVersion: Int,
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String,
    val minSdk: Int,
    val releasedAt: String,
    val changelog: List<String>,
)

/** All downloaded and cached manifests must pass this parser before they authorize a download. */
object AppUpdateManifestParser {
    const val MANIFEST_URL = "https://digimonlegend.com/continuation-updates/latest.json"
    private const val PACKAGE_NAME = "app.banzhan.deepseekcontinuation"
    private const val APK_PREFIX = "/continuation-updates/apks/"
    const val MAX_MANIFEST_BYTES = 64 * 1024
    const val MAX_APK_BYTES = 64L * 1024 * 1024
    private val fields =
        setOf(
            "schemaVersion",
            "packageName",
            "versionCode",
            "versionName",
            "apkUrl",
            "apkSize",
            "sha256",
            "minSdk",
            "releasedAt",
            "changelog",
        )
    private val json = Json {
        isLenient = false
        allowTrailingComma = false
        allowSpecialFloatingPointValues = false
    }

    fun parse(bytes: ByteArray): AppUpdateManifest {
        require(bytes.size in 1..MAX_MANIFEST_BYTES) { "更新清单大小无效。" }
        val text =
            try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (error: CharacterCodingException) {
                throw IllegalArgumentException("更新清单必须使用有效的 UTF-8 编码。")
            }
        return parseValidatedText(text)
    }

    fun parse(text: String): AppUpdateManifest {
        require(text.length in 1..MAX_MANIFEST_BYTES) { "更新清单大小无效。" }
        val encoded =
            try {
                StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text))
            } catch (error: CharacterCodingException) {
                throw IllegalArgumentException("更新清单包含无效的 Unicode 字符。")
            }
        val bytes = ByteArray(encoded.remaining())
        encoded.get(bytes)
        return parse(bytes)
    }

    fun isAvailable(
        manifest: AppUpdateManifest,
        currentVersionCode: Int,
        currentSdk: Int,
    ): Boolean =
        manifest.schemaVersion == 1 &&
            manifest.packageName == PACKAGE_NAME &&
            manifest.versionCode > currentVersionCode &&
            currentSdk >= manifest.minSdk

    private fun parseValidatedText(text: String): AppUpdateManifest {
        rejectDuplicateKeys(text)
        val root = json.parseToJsonElement(text) as? JsonObject ?: error("更新清单必须是 JSON 对象。")
        require(root.keys == fields) { "更新清单字段缺失或包含未知字段。" }
        val schemaVersion = root.integer("schemaVersion")
        require(schemaVersion == 1) { "此更新清单版本暂不受支持。" }
        val packageName = root.text("packageName")
        require(packageName == PACKAGE_NAME) { "更新清单的应用标识不匹配。" }
        val versionCode = root.integer("versionCode")
        require(versionCode > 0) { "更新版本编号无效。" }
        val versionName = root.text("versionName")
        require(versionName.isNotBlank() && versionName.length <= 80) { "更新版本名称无效。" }
        val apkUrl = root.text("apkUrl")
        validateApkUrl(apkUrl)
        val apkSize = (root["apkSize"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        require(apkSize != null && apkSize in 1..MAX_APK_BYTES) { "更新安装包大小无效。" }
        val sha256 = root.text("sha256")
        require(
            sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        ) {
            "更新安装包校验值无效。"
        }
        val minSdk = root.integer("minSdk")
        require(minSdk in 26..10_000) { "更新系统版本要求无效。" }
        val releasedAt = Instant.parse(root.text("releasedAt")).toString()
        val changes = root["changelog"] as? JsonArray ?: error("更新说明必须是列表。")
        require(changes.size <= 8) { "更新说明条目过多。" }
        val changelog =
            changes.map { value ->
                val entry = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                require(entry != null && entry.isNotBlank() && entry.length <= 200) { "更新说明条目无效。" }
                entry
            }
        return AppUpdateManifest(
            schemaVersion,
            packageName,
            versionCode,
            versionName,
            apkUrl,
            apkSize,
            sha256.lowercase(Locale.ROOT),
            minSdk,
            releasedAt,
            changelog,
        )
    }

    private fun validateApkUrl(value: String) {
        val uri = URI(value)
        require(
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals("digimonlegend.com", ignoreCase = true) &&
                uri.port in setOf(-1, 443) &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null
        ) {
            "更新安装包地址来源无效。"
        }
        val rawPath = uri.rawPath.orEmpty()
        require(rawPath.startsWith(APK_PREFIX) && rawPath.endsWith(".apk", ignoreCase = true)) {
            "更新安装包路径无效。"
        }
        // Encoded percent is rejected as well, so another URL-decoding layer cannot reveal hidden
        // separators.
        require(!Regex("%(?:2f|5c|25)", RegexOption.IGNORE_CASE).containsMatchIn(rawPath)) {
            "更新安装包路径编码无效。"
        }
        val decodedPath = uri.path.orEmpty()
        require('\\' !in decodedPath && decodedPath.none { it.isISOControl() }) { "更新安装包路径无效。" }
        val segments = decodedPath.removePrefix(APK_PREFIX).split('/')
        require(segments.all { it.isNotEmpty() && it != "." && it != ".." }) { "更新安装包路径包含无效目录。" }
    }

    /**
     * Check duplicate names and bound nesting before JsonObject can overwrite names or build a deep
     * tree.
     */
    private fun rejectDuplicateKeys(text: String) {
        val scopes = mutableListOf<KeyScope>()
        var offset = 0
        while (offset < text.length) {
            when (text[offset]) {
                '{',
                '[' -> {
                    require(scopes.size < 16) { "更新清单嵌套层级过深。" }
                    val isObject = text[offset] == '{'
                    scopes +=
                        KeyScope(if (isObject) mutableSetOf() else null, expectingKey = isObject)
                }
                '}',
                ']' -> {
                    val scope = scopes.lastOrNull() ?: error("更新清单结构无效。")
                    require((text[offset] == '}') == (scope.names != null)) { "更新清单结构无效。" }
                    scopes.removeAt(scopes.lastIndex)
                }
                ',' -> scopes.lastOrNull()?.let { it.expectingKey = it.names != null }
                '"' -> {
                    val end = quotedEnd(text, offset)
                    val scope = scopes.lastOrNull()
                    if (scope?.expectingKey == true) {
                        val name = json.decodeFromString<String>(text.substring(offset, end))
                        require(requireNotNull(scope.names).add(name)) { "更新清单包含重复字段。" }
                        scope.expectingKey = false
                    }
                    offset = end
                    continue
                }
            }
            offset++
        }
        require(scopes.isEmpty()) { "更新清单结构不完整。" }
    }

    private fun quotedEnd(text: String, start: Int): Int {
        var offset = start + 1
        while (offset < text.length) {
            when (text[offset]) {
                '\\' -> offset += 2
                '"' -> return offset + 1
                else -> offset++
            }
        }
        error("更新清单字符串无效。")
    }

    private class KeyScope(val names: MutableSet<String>?, var expectingKey: Boolean)

    private fun JsonObject.text(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("更新清单文本字段格式无效。")

    private fun JsonObject.integer(key: String): Int =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            ?: error("更新清单数字字段格式无效。")
}
