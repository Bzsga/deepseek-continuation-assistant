package app.banzhan.deepseekcontinuation.data

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
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

    private fun copySavedState(source: SavedStateHandle) = SavedStateHandle(source.keys().associateWith { source.get<Any?>(it) })
}
