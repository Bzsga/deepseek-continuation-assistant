package app.banzhan.deepseekcontinuation.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingImportPolicyTest {
    private val send = "android.intent.action.SEND"
    private val view = "android.intent.action.VIEW"
    private val contentUri = "content://com.example.files/document/export.json"

    @Test
    fun supportedActionsAndMimeTypesAllowContentProviders() {
        listOf(send, view).forEach { action ->
            listOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "application/json", "text/json").forEach { type ->
                assertNull(IncomingImportPolicy.validate(action, type, contentUri))
            }
        }
        assertNull(IncomingImportPolicy.validate(send, " Application/JSON ; charset=UTF-8", contentUri + "?version=1"))
        assertNull(IncomingImportPolicy.validate(view, "APPLICATION/ZIP", "CONTENT://com.example.files/document/1"))
    }

    @Test
    fun unsupportedActionsMissingMetadataAndUnexpectedMimeTypesAreRejected() {
        listOf(null, "", "android.intent.action.SEND_MULTIPLE", "android.intent.action.EDIT").forEach { action ->
            assertNotNull(IncomingImportPolicy.validate(action, "application/json", contentUri))
        }
        listOf(null, "", "text/plain", "application/pdf", "image/png").forEach { type ->
            assertNotNull(IncomingImportPolicy.validate(send, type, contentUri))
        }
        assertNotNull(IncomingImportPolicy.validate(send, "application/json", null))
        assertNotNull(IncomingImportPolicy.validate(send, "application/json", " "))
    }

    @Test
    fun nonContentSchemesMissingAuthorityAndUserInfoAreRejected() {
        listOf("file:///storage/export.json", "https://example.com/export.json", "http://example.com/export.json",
            "intent://example/export.json", "javascript:alert(1)", "content:opaque", "content:///export.json", "content://",
            "content://user:secret@example.com/export.json", "content://user@example_provider/export.json",
            "content://user%40example.com/export.json", "content://%09/export.json", "content://example.com/bad space.json")
            .forEach { uri -> assertNotNull(IncomingImportPolicy.validate(send, "application/json", uri)) }
        val error = IncomingImportPolicy.validate(send, "application/json", "content://secret-user:secret-password@example.com/file")
        assertFalse(error.orEmpty().contains("secret"))
    }

    @Test
    fun uriSizeHasAnExplicitBound() {
        val prefix = "content://provider/"
        val exactly = prefix + "x".repeat(4096 - prefix.length)
        assertNull(IncomingImportPolicy.validate(send, "application/zip", exactly))
        assertNotNull(IncomingImportPolicy.validate(send, "application/zip", exactly + "x"))
        assertEquals(33_554_432, IncomingImportPolicy.MAX_BYTES)
    }

    @Test
    fun fileNamesRemovePathsAndHiddenControls() {
        assertEquals("export.json", IncomingImportPolicy.safeFileName("C:\\temp\\..\\export.json", "application/json"))
        assertEquals("export.json", IncomingImportPolicy.safeFileName("../../folder/export.json", "application/json"))
        assertEquals("export.json", IncomingImportPolicy.safeFileName("C:export.json", "application/json"))
        assertEquals("abcd.JSON", IncomingImportPolicy.safeFileName("ab\nc\u0000\u0085\u2028\u2029\u061c\u200e\u2067d.JSON", "application/json"))
        assertEquals("export.json", IncomingImportPolicy.safeFileName("export\u202E.json", "application/json"))
        assertEquals("note%2Fpart.json", IncomingImportPolicy.safeFileName("note%2Fpart.json", "application/json"))
    }

    @Test
    fun missingOrInvisibleNamesUseMimeAppropriateDefaults() {
        listOf(null, "", " ", ".", "..", "folder/", "\u0000\u202E\u2066").forEach { name ->
            assertEquals("分享的聊天记录.json", IncomingImportPolicy.safeFileName(name, " Text/JSON; charset=utf-8"))
        }
        assertEquals("分享的聊天记录.zip", IncomingImportPolicy.safeFileName(null, "application/octet-stream"))
        assertEquals("分享的聊天记录.zip", IncomingImportPolicy.safeFileName(null, null))
        assertEquals("聊天记录", IncomingImportPolicy.safeFileName("聊天记录", "application/json"))
    }

    @Test
    fun longNamesPreserveSupportedExtensionsAndUnicodeBoundaries() {
        val json = IncomingImportPolicy.safeFileName("😀".repeat(100) + ".JSON", "application/json")
        assertTrue(json.length <= 160)
        assertTrue(json.endsWith(".JSON"))
        assertEquals(json, json.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
        val zip = IncomingImportPolicy.safeFileName("x".repeat(200) + ".zip", "application/zip")
        assertEquals(160, zip.length)
        assertTrue(zip.endsWith(".zip"))
        assertEquals("name.json", IncomingImportPolicy.safeFileName("name\uD800.json", "application/json"))
    }
}
