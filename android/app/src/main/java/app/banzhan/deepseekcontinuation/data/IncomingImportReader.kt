package app.banzhan.deepseekcontinuation.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream

internal class IncomingImportReadException(message: String) : Exception(message)

internal data class IncomingImportDocument(val fileName: String, val bytes: ByteArray)

/** Never resolves file/http URLs, extracts archives, or acquires persistent storage access. */
internal fun readIncomingImport(context: Context, uriText: String, mimeType: String?): IncomingImportDocument {
    val started = System.nanoTime()
    DiagnosticLog.record("import", "start")
    try {
        return readIncomingImportDocument(context, uriText, mimeType).also {
            DiagnosticLog.record("import", "read", mapOf("input_bytes" to it.bytes.size.toLong(),
                "elapsed_ms" to (System.nanoTime() - started) / 1_000_000))
        }
    } catch (error: Throwable) {
        DiagnosticLog.record("import", "failed", mapOf("elapsed_ms" to (System.nanoTime() - started) / 1_000_000), error)
        throw error
    }
}

private fun readIncomingImportDocument(context: Context, uriText: String, mimeType: String?): IncomingImportDocument {
    val validation = IncomingImportPolicy.validate(android.content.Intent.ACTION_VIEW, mimeType, uriText)
    if (validation != null) throw IncomingImportReadException(validation)
    val uri = Uri.parse(uriText).normalizeScheme()
    val resolver = context.contentResolver
    val metadataName = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    } catch (_: Exception) {
        null
    }
    val bytes = try {
        resolver.openInputStream(uri)?.use(::readIncomingImportBytes)
            ?: throw IncomingImportReadException("无法读取分享文件，请重新分享 ZIP 或 JSON。")
    } catch (_: SecurityException) {
        throw IncomingImportReadException("分享文件的临时读取权限已失效，请从文件管理器重新分享。")
    } catch (_: FileNotFoundException) {
        throw IncomingImportReadException("分享文件已不可用，请从文件管理器重新分享。")
    }
    val zip = bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()
    val detectedMime = if (zip) "application/zip" else "application/json"
    var fileName = IncomingImportPolicy.safeFileName(metadataName?.takeIf { it.isNotBlank() } ?: "聊天文件", detectedMime)
    // Providers sometimes omit an extension. The importer still validates the actual format.
    if (!fileName.endsWith(".zip", true) && !fileName.endsWith(".json", true)) {
        fileName = IncomingImportPolicy.safeFileName(fileName + if (zip) ".zip" else ".json", detectedMime)
    }
    return IncomingImportDocument(fileName, bytes)
}

internal fun readIncomingImportBytes(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(32 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) throw IncomingImportReadException("分享文件读取中断，请从文件管理器重新分享。")
        if (output.size().toLong() + count > IncomingImportPolicy.MAX_BYTES) {
            throw IncomingImportReadException("导入文件超过 32 MiB，请拆分后导入。")
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
