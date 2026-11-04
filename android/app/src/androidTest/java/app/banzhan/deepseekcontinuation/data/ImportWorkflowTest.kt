package app.banzhan.deepseekcontinuation.data

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** QA emulator only. Synthetic imports and local BYOK validation never call a model. */
@RunWith(AndroidJUnit4::class)
class ImportWorkflowTest {
    @Test
    fun importBatchIdentityAndArchiveModesPreserveOriginalSources() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        val store = ViewModelStore()
        lateinit var controller: AppController
        instrumentation.runOnMainSync {
            controller = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[AppController::class.java]
        }
        try {
            controller.clearLocalData()
            controller.saveSettings(ServiceSettings(mode = "byok"))
            assertNotNull(controller.checkOrganizationAvailability())
            controller.saveSettings(ServiceSettings(mode = "byok", apiKey = "synthetic-not-used-for-network"))
            assertNull(controller.checkOrganizationAvailability())

            val zip = ByteArrayOutputStream().also { output ->
                ZipOutputStream(output).use { stream ->
                    listOf("first", "second").forEach { id ->
                        stream.putNextEntry(ZipEntry("$id.json"))
                        stream.write(conversationJson(id, "原始消息 $id").toByteArray())
                        stream.closeEntry()
                    }
                }
            }.toByteArray()
            val firstIds = controller.importFile(zip, "synthetic-chats.zip")
            assertEquals(2, firstIds.size)
            assertEquals(firstIds, controller.state.value.lastImportedConversationIds)
            assertEquals(firstIds, controller.importFile(zip, "synthetic-chats.zip"))
            assertEquals(firstIds, controller.state.value.lastImportedConversationIds)

            val changedIds = controller.importFile(conversationJson("first", "被改写的消息").toByteArray(), "synthetic-changed.json")
            assertEquals(1, changedIds.size)
            assertFalse(changedIds.single() in firstIds)
            assertEquals(changedIds, controller.state.value.lastImportedConversationIds)
            val source = controller.state.value.conversations.first { it.id == changedIds.single() }
            val branch = source.branches.single()
            val selected = branch.messages.map { it.id }.toSet()
            val summaryId = controller.createArchive("智能模式", source, branch.id, selected, "", includeRawEntries = false)
            val originalId = controller.createArchive("原文模式", source, branch.id, selected, "")
            val summary = controller.state.value.archives.first { it.id == summaryId }
            val original = controller.state.value.archives.first { it.id == originalId }
            assertEquals("summary", summary.generationMode)
            assertTrue(summary.entries.isEmpty())
            assertEquals("original", original.generationMode)
            assertTrue(original.entries.isNotEmpty())
            assertEquals(source, controller.state.value.conversations.first { it.id == source.id })
            assertEquals(3, controller.state.value.conversations.size)

            controller.addConversation(summaryId, source, branch.id, selected)
            val mixed = controller.state.value.archives.first { it.id == summaryId }
            assertEquals("legacy", mixed.generationMode)
            assertTrue(mixed.entries.isNotEmpty())
            val pureSummaryVersion = controller.revisions(summaryId).first { it.generationMode == "summary" }
            controller.restore(summaryId, pureSummaryVersion)
            assertEquals("summary", controller.state.value.archives.first { it.id == summaryId }.generationMode)

            val backup = controller.backup()
            controller.restoreBackup(backup)
            assertTrue(controller.state.value.lastImportedConversationIds.isEmpty())
            assertEquals("summary", controller.state.value.archives.first { it.id == summaryId }.generationMode)
            assertEquals("original", controller.state.value.archives.first { it.id == originalId }.generationMode)
            assertNotEquals(summaryId, originalId)
        } finally {
            controller.clearLocalData()
            controller.saveSettings(ServiceSettings())
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    private fun conversationJson(id: String, content: String): String = buildJsonObject {
        put("id", id)
        put("title", "测试 $id")
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("id", "message-$id")
                put("role", "user")
                put("content", content)
            })
        })
    }.toString()
}
