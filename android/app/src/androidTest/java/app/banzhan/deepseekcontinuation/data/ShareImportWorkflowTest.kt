package app.banzhan.deepseekcontinuation.data

import android.app.Application
import android.util.Base64
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.banzhan.deepseekcontinuation.core.DeepSeekShareLink
import app.banzhan.deepseekcontinuation.core.DeepSeekShareParser
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated .updateqa installation only; all sources are synthetic and no model is called. */
@RunWith(AndroidJUnit4::class)
class ShareImportWorkflowTest {
    /** Explicit opt-in only: real links belong in runner arguments, never in fixtures or logs. */
    @Test fun suppliedPublicLinkCanBeReadAndSavedOnThePhone() = runBlocking {
        val supplied = InstrumentationRegistry.getArguments().getString("shareSmokeUrl")
        org.junit.Assume.assumeTrue(!supplied.isNullOrBlank())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        check(application.packageName.endsWith(".updateqa"))
        val link = DeepSeekShareLink.parse(requireNotNull(supplied))
        val proxies = java.net.ProxySelector.getDefault()?.select(java.net.URI(link.url)).orEmpty()
        val direct = proxies.all { it.type() == java.net.Proxy.Type.DIRECT }
        if (InstrumentationRegistry.getArguments().getString("shareSmokeRequireDirect") == "true") {
            check(direct) { "This smoke test requires an Android connection without a system proxy." }
        }
        val document = DeepSeekShareReader().read(link)
        val messages = document.result.conversations.flatMap { it.branches }.flatMap { it.messages }.distinctBy { it.id }
        assertTrue(messages.isNotEmpty())
        val store = ViewModelStore()
        lateinit var controller: AppController
        instrumentation.runOnMainSync {
            controller = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[AppController::class.java]
        }
        try {
            controller.clearLocalData()
            controller.saveSettings(ServiceSettings(mode = "byok"))
            val ids = controller.importShare(document, link)
            assertEquals(ids, controller.importShare(document, link))
            assertEquals(document.result.conversations.toSet(), controller.state.value.conversations.toSet())
            val backup = appJson.decodeFromString<BackupDocument>(controller.backup())
            assertArrayEquals(document.bytes, Base64.decode(backup.sources.single().bytesBase64, Base64.NO_WRAP))
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("share_import_smoke", "messages=${messages.size}; bytes=${document.bytes.size}; saved=true; direct=$direct")
            })
        } finally {
            controller.clearLocalData()
            controller.saveSettings(ServiceSettings())
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    @Test fun duplicateShareAndBackupPreserveTheRawSnapshotAndManualArchiveEdits() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        check(application.packageName.endsWith(".updateqa")) { "Use an isolated QA installation." }
        val store = ViewModelStore()
        lateinit var controller: AppController
        instrumentation.runOnMainSync {
            controller = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[AppController::class.java]
        }
        try {
            controller.clearLocalData()
            controller.saveSettings(ServiceSettings(mode = "byok"))
            val link = DeepSeekShareLink.parse("https://chat.deepseek.com/share/synthetic123")
            val bytes = """{"code":0,"data":{"biz_code":0,"biz_data":{"title":"合成分享","messages":[{"id":1,"role":"USER","fragments":[{"type":"REQUEST","content":"我想把那次约定留下来。"}]},{"id":2,"role":"ASSISTANT","fragments":[{"type":"THINK","content":"synthetic reasoning retained in raw snapshot"},{"type":"RESPONSE","content":"天气好时，我们一起去看日出。"}]},{"id":3,"role":"USER","fragments":[{"type":"REQUEST","content":"下次还是原来的地点吗？"}]}]}}}""".toByteArray()
            val document = DeepSeekShareDocument(DeepSeekShareParser.parse(bytes, link.id), bytes)
            val ids = controller.importShare(document, link)
            val source = controller.state.value.conversations.single()
            val branch = source.branches.single()
            assertEquals("下次还是原来的地点吗？", branch.messages.last().content)
            assertEquals(3, branch.messages.size)
            assertNull(controller.state.value.account.currentUser)
            val archiveId = controller.createArchive("分享原文", source, branch.id, branch.messages.map { it.id }.toSet(), "")
            val entry = controller.state.value.archives.single { it.id == archiveId }.entries.first()
            controller.saveEntry(archiveId, entry.copy(text = "这是我亲手补上的约定。", pinned = true))

            assertEquals(ids, controller.importShare(document, link))
            assertEquals(1, controller.state.value.conversations.size)
            assertTrue(controller.state.value.tasks.isEmpty())
            assertPreservedEdit(controller, archiveId, entry.id)
            val backup = controller.backup()
            val backedUp = appJson.decodeFromString<BackupDocument>(backup)
            assertEquals(1, backedUp.sources.size)
            assertArrayEquals(bytes, Base64.decode(backedUp.sources.single().bytesBase64, Base64.NO_WRAP))
            controller.clearLocalData()
            controller.restoreBackup(backup)
            assertEquals(source, controller.state.value.conversations.single())
            assertPreservedEdit(controller, archiveId, entry.id)
            val restored = appJson.decodeFromString<BackupDocument>(controller.backup())
            assertArrayEquals(bytes, Base64.decode(restored.sources.single().bytesBase64, Base64.NO_WRAP))
        } finally {
            controller.clearLocalData()
            controller.saveSettings(ServiceSettings())
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    private fun assertPreservedEdit(controller: AppController, archiveId: String, entryId: String) {
        val entry = controller.state.value.archives.single { it.id == archiveId }.entries.single { it.id == entryId }
        assertEquals("这是我亲手补上的约定。", entry.text)
        assertTrue(entry.edited)
        assertTrue(entry.pinned)
    }
}
