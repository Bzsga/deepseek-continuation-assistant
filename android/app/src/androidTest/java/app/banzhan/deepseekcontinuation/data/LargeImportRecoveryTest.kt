package app.banzhan.deepseekcontinuation.data

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run the two stages separately on the QA emulator, force-stopping between them. No model calls. */
@RunWith(AndroidJUnit4::class)
class LargeImportRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = instrumentation.targetContext.applicationContext as Application

    private fun controller(): Pair<ViewModelStore, AppController> {
        val store = ViewModelStore()
        lateinit var instance: AppController
        instrumentation.runOnMainSync {
            instance = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[AppController::class.java]
        }
        return store to instance
    }

    @Test fun importLargeAccountZip() = runBlocking {
        val (store, controller) = controller()
        try {
            controller.clearLocalData()
            controller.saveSettings(ServiceSettings(mode = "byok"))
            val output = ByteArrayOutputStream()
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("conversations.json"))
                zip.write('['.code)
                repeat(COUNT) { index ->
                    if (index > 0) zip.write(','.code)
                    zip.write(buildJsonObject {
                        put("id", "large-qa-$index")
                        put("title", "Large synthetic $index")
                        put("messages", buildJsonArray {
                            add(buildJsonObject {
                                put("id", "message-$index")
                                put("role", "user")
                                put("content", body(index))
                            })
                        })
                    }.toString().toByteArray())
                }
                zip.write(']'.code)
                zip.closeEntry()
            }
            val bytes = output.toByteArray()
            assertTrue("fixture must match the reported 11 MiB ZIP scale", bytes.size >= 11 * 1024 * 1024)
            val ids = controller.importFile(bytes, "large-synthetic.zip")
            assertEquals(COUNT, ids.size)
            verify(controller)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    @Test fun verifyLargeImportAfterProcessRestart() = runBlocking {
        val (store, controller) = controller()
        try {
            controller.revisions("no-synthetic-archive") // Wait for startup without materializing a full backup.
            verify(controller)
            val before = controller.state.value.conversations.first { it.title == "Large synthetic 0" }
            val changed = buildJsonObject {
                put("id", "large-qa-0")
                put("title", "Large synthetic 0")
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "message-0")
                        put("role", "user")
                        put("content", body(0))
                    })
                })
            }.toString().toByteArray()
            assertEquals(listOf(before.id), controller.importFile(changed, "reimport-synthetic.json"))
            verify(controller)
            controller.clearLocalData()
            controller.saveSettings(ServiceSettings())
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    private fun verify(controller: AppController) {
        val conversations = controller.state.value.conversations
        assertEquals(COUNT, conversations.size)
        repeat(COUNT) { index ->
            val item = conversations.single { it.title == "Large synthetic $index" }
            assertEquals(body(index), item.branches.single().messages.single().content)
        }
    }

    private fun body(index: Int): String {
        val random = Random(index.toLong())
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
        return String(CharArray(480_000) { alphabet[random.nextInt(alphabet.length)] })
    }

    private companion object { const val COUNT = 32 }
}
