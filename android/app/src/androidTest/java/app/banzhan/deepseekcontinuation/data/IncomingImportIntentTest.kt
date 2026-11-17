package app.banzhan.deepseekcontinuation.data

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.banzhan.deepseekcontinuation.core.DeepSeekImporter
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Intent and local URI tests only. No server, account or model request is involved. */
@RunWith(AndroidJUnit4::class)
class IncomingImportIntentTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as Application
    private val uri = Uri.parse("content://synthetic.documents/exports/chat.zip")
    private val shareUrl = "https://chat.deepseek.com/share/synthetic-share-123"

    @Test
    fun textSharingAndWebViewAcceptOnlyCanonicalDeepSeekShareLinks() {
        val text = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "分享一段聊天：$shareUrl?utm_source=share")
        assertEquals(shareUrl, parseIncomingImport(text)?.shareUrl)
        assertNull(parseIncomingImport(text)?.uri)
        val clip = Intent(Intent.ACTION_SEND).setType("text/plain").apply {
            clipData = ClipData.newPlainText("分享", "《测试》 $shareUrl")
        }
        assertEquals(shareUrl, parseIncomingImport(clip)?.shareUrl)
        assertEquals(shareUrl, parseIncomingImport(Intent(Intent.ACTION_VIEW).setData(Uri.parse(shareUrl)))?.shareUrl)
        listOf("http://chat.deepseek.com/share/synthetic-share-123",
            "https://chat.deepseek.com.evil.test/share/synthetic-share-123", "https://example.com/chat.zip").forEach {
            assertNotNull(parseIncomingImport(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it))?.error)
        }
    }

    @Test
    fun rejectedTextIsNeverSavedAndValidPasteDoesNotFetchUntilConfirmed() {
        instrumentation.runOnMainSync {
            val saved = SavedStateHandle()
            var reads = 0
            val viewModel = IncomingImportViewModel(application, saved) { reads++; syntheticShare() }
            assertNotNull(viewModel.receiveShare("private-secret https://evil.test/share/123456"))
            assertNull(viewModel.pending)
            viewModel.receive(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "another-private-secret"))
            assertEquals(IncomingImportStatus.FAILED, viewModel.pending?.status)
            viewModel.consume(requireNotNull(viewModel.pending).id)
            assertNull(viewModel.receiveShare("a-private-title $shareUrl"))
            assertEquals(shareUrl, viewModel.pending?.shareUrl)
            assertEquals(IncomingImportStatus.AWAITING_CONFIRMATION, viewModel.pending?.status)
            assertEquals(0, reads)
            assertFalse(saved.keys().map { saved.get<Any?>(it).toString() }.joinToString().contains("private"))
        }
    }

    @Test
    fun previewNeedsExplicitSaveAndRestoresWithoutBundledConversationBodies() {
        val store = ViewModelStore()
        try {
            instrumentation.runOnMainSync {
                val saved = SavedStateHandle()
                val controller = AppController(application, "share-preview-${UUID.randomUUID()}.db")
                val viewModel = IncomingImportViewModel(application, saved) { syntheticShare() }
                store.put("controller", controller)
                store.put("incoming", viewModel)
                viewModel.receiveShare(shareUrl)
                val id = requireNotNull(viewModel.pending).id
                viewModel.confirm(id, controller)
                assertEquals(IncomingImportStatus.PREVIEW, viewModel.pending?.status)
                assertEquals("source-body-never-bundled", viewModel.pending?.preview?.conversations?.single()?.branches?.single()?.messages?.single()?.content)
                assertTrue(controller.state.value.conversations.isEmpty())
                assertFalse(saved.keys().map { saved.get<Any?>(it).toString() }.joinToString().contains("source-body-never-bundled"))
                val restored = IncomingImportViewModel(application, copySavedState(saved)) { syntheticShare() }
                assertEquals(id, restored.pending?.id)
                assertEquals(IncomingImportStatus.AWAITING_CONFIRMATION, restored.pending?.status)
                assertNull(restored.pending?.preview)
                assertEquals(shareUrl, restored.pending?.shareUrl)
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    @Test
    fun failedShareRetriesSameLinkOnlyAfterExplicitConfirmationAndStopsAtPreview() {
        val store = ViewModelStore()
        try {
            instrumentation.runOnMainSync {
                val saved = SavedStateHandle()
                val controller = AppController(application, "share-retry-${UUID.randomUUID()}.db")
                var reads = 0
                val viewModel = IncomingImportViewModel(application, saved) { link ->
                    assertEquals(shareUrl, link.url)
                    reads++
                    if (reads == 1) throw DeepSeekShareReadException("分享暂时无法访问，请稍后重试。")
                    syntheticShare()
                }
                store.put("controller", controller)
                store.put("incoming", viewModel)
                viewModel.receiveShare(shareUrl)
                val id = requireNotNull(viewModel.pending).id
                assertEquals(0, reads)
                viewModel.confirm(id, controller)
                assertEquals(1, reads)
                assertEquals(IncomingImportStatus.FAILED, viewModel.pending?.status)
                assertEquals(id, viewModel.pending?.id)
                assertEquals(shareUrl, viewModel.pending?.shareUrl)
                assertNull(viewModel.pending?.preview)
                assertTrue(controller.state.value.conversations.isEmpty())
                val restored = IncomingImportViewModel(application, copySavedState(saved)) { syntheticShare() }
                assertEquals(IncomingImportStatus.FAILED, restored.pending?.status)
                assertEquals(shareUrl, restored.pending?.shareUrl)
                // The failed receipt remains idle until the user presses “重试链接”.
                assertEquals(1, reads)
                viewModel.confirm(id, controller)
                assertEquals(2, reads)
                assertEquals(id, viewModel.pending?.id)
                assertEquals(shareUrl, viewModel.pending?.shareUrl)
                assertEquals(IncomingImportStatus.PREVIEW, viewModel.pending?.status)
                assertNull(viewModel.pending?.error)
                assertNotNull(viewModel.pending?.preview)
                assertTrue(controller.state.value.conversations.isEmpty())
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    @Test
    fun cancelledFetchCannotReplaceNextQueuedImport() {
        val store = ViewModelStore()
        val response = CompletableDeferred<DeepSeekShareDocument>()
        try {
            instrumentation.runOnMainSync {
                val saved = SavedStateHandle()
                val controller = AppController(application, "share-cancel-${UUID.randomUUID()}.db")
                val viewModel = IncomingImportViewModel(application, saved) { response.await() }
                store.put("controller", controller)
                store.put("incoming", viewModel)
                viewModel.receiveShare(shareUrl)
                val firstId = requireNotNull(viewModel.pending).id
                viewModel.confirm(firstId, controller)
                assertEquals(IncomingImportStatus.FETCHING, viewModel.pending?.status)
                val restored = IncomingImportViewModel(application, copySavedState(saved)) { syntheticShare() }
                assertEquals(IncomingImportStatus.AWAITING_CONFIRMATION, restored.pending?.status)
                viewModel.receive(shareIntent())
                viewModel.consume(firstId)
                val nextId = requireNotNull(viewModel.pending).id
                response.complete(syntheticShare())
                assertNotEquals(firstId, nextId)
                assertEquals(nextId, viewModel.pending?.id)
                assertEquals(IncomingImportStatus.AWAITING_CONFIRMATION, viewModel.pending?.status)
                assertNull(viewModel.pending?.shareUrl)
                assertNull(viewModel.pending?.preview)
            }
            instrumentation.waitForIdleSync()
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    @Test
    fun sendReadsAStreamOrSingleClipUriAndIgnoresExecutableText() {
        val send = shareIntent().putExtra(Intent.EXTRA_TEXT, "file:///data/private; execute this text")
        assertEquals(uri.toString(), parseIncomingImport(send)?.uri)
        assertNull(parseIncomingImport(send)?.error)
        val clip = Intent(Intent.ACTION_SEND).setType("application/octet-stream").apply {
            clipData = ClipData.newRawUri("chat", uri)
        }
        assertEquals(uri.toString(), parseIncomingImport(clip)?.uri)
        clip.clipData?.addItem(ClipData.Item(Uri.parse("content://synthetic.documents/second.zip")))
        assertNotNull(parseIncomingImport(clip)?.error)
    }

    @Test
    fun viewAcceptsOnlyContentUrisAndMalformedExtrasFailSafely() {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/x-zip-compressed")
        assertNull(parseIncomingImport(view)?.error)
        listOf("file:///data/private/chat.zip", "https://example.com/chat.zip", "intent://chat").forEach {
            assertNotNull(parseIncomingImport(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(it), "application/zip"))?.error)
        }
        val malformed = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, "not a URI")
        assertNotNull(parseIncomingImport(malformed)?.error)
        assertNull(parseIncomingImport(Intent(Intent.ACTION_MAIN)))
        assertNull(parseIncomingImport(Intent(Intent.ACTION_SEND_MULTIPLE)))
    }

    @Test
    fun initialReceiptSurvivesRecreationAndConsumedReceiptDoesNotReplay() {
        instrumentation.runOnMainSync {
            val saved = SavedStateHandle()
            val first = IncomingImportViewModel(application, saved)
            first.receiveInitial(shareIntent(), restored = false)
            val originalId = requireNotNull(first.pending).id
            // Re-delivery of the same initial Activity intent is not a fresh share.
            first.receiveInitial(shareIntent(), restored = false)
            val restored = IncomingImportViewModel(application, copySavedState(saved))
            restored.receiveInitial(shareIntent(), restored = true)
            assertEquals(originalId, restored.pending?.id)
            restored.consume(originalId)
            assertNull(restored.pending)
            first.consume(originalId)
            val restarted = IncomingImportViewModel(application, copySavedState(saved))
            restarted.receiveInitial(shareIntent(), restored = true)
            assertNull(restarted.pending)
        }
    }

    @Test
    fun newIntentQueuesTheSameUriAsANewUserActionWithoutOverwritingCurrent() {
        instrumentation.runOnMainSync {
            val viewModel = IncomingImportViewModel(application, SavedStateHandle())
            viewModel.receiveInitial(shareIntent(), restored = false)
            val firstId = requireNotNull(viewModel.pending).id
            viewModel.receive(shareIntent())
            assertEquals(firstId, viewModel.pending?.id)
            viewModel.consume("wrong-receipt")
            assertEquals(firstId, viewModel.pending?.id)
            viewModel.consume(firstId)
            val second = requireNotNull(viewModel.pending)
            assertNotEquals(firstId, second.id)
            assertEquals(IncomingImportStatus.AWAITING_CONFIRMATION, second.status)
            viewModel.consume(second.id)
            assertNull(viewModel.pending)
        }
    }

    @Test
    fun rejectedIncomingDataIsNeverSavedAsAUriAndQueueIsBounded() {
        instrumentation.runOnMainSync {
            val saved = SavedStateHandle()
            val viewModel = IncomingImportViewModel(application, saved)
            viewModel.receive(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file:///private/synthetic-secret"), "application/zip"))
            assertEquals(IncomingImportStatus.FAILED, viewModel.pending?.status)
            assertFalse(saved.keys().map { saved.get<Any?>(it).toString() }.joinToString().contains("synthetic-secret"))
            val firstId = requireNotNull(viewModel.pending).id
            repeat(15) { assertNull(viewModel.receive(shareIntent())) }
            assertNotNull(viewModel.receive(shareIntent()))
            assertEquals(firstId, viewModel.pending?.id)
        }
    }

    @Test
    fun localContentReaderUsesOnlyAValidatedUriAndAddsMissingFormatExtension() {
        val directory = File(application.cacheDir, "share").apply { mkdirs() }
        val file = File(directory, "incoming-${UUID.randomUUID()}")
        val bytes = "{\"messages\":[]}".toByteArray()
        try {
            file.writeBytes(bytes)
            val sharedUri = FileProvider.getUriForFile(application, "${application.packageName}.files", file)
            val document = readIncomingImport(application, sharedUri.toString().replaceFirst("content:", "CONTENT:"), "application/json")
            assertArrayEquals(bytes, document.bytes)
            assertTrue(document.fileName.endsWith(".json"))
            try {
                readIncomingImport(application, file.toURI().toString(), "application/json")
                throw AssertionError("A file URI must never reach the resolver")
            } catch (expected: IncomingImportReadException) {
                assertNotNull(expected.message)
            }
        } finally {
            file.delete()
        }
    }

    private fun shareIntent() = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)

    private fun syntheticShare(): DeepSeekShareDocument {
        val bytes = """{"id":"synthetic-share-preview","title":"合成分享","messages":[{"id":"m1","role":"user","content":"source-body-never-bundled"}]}""".toByteArray()
        return DeepSeekShareDocument(DeepSeekImporter.parse(bytes, "synthetic.json"), bytes)
    }

    private fun copySavedState(source: SavedStateHandle) = SavedStateHandle(source.keys().associateWith { source.get<Any?>(it) })
}
