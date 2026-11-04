package app.banzhan.deepseekcontinuation.data

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Device tests use synthetic text and make no model requests. */
@RunWith(AndroidJUnit4::class)
class PersistenceSmokeTest {
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

    @Test
    fun longTranscriptBackupAndSecureSettingsSurviveRecreation() = runBlocking<Unit> {
        val (firstStore, first) = controller()
        first.clearLocalData()
        val text = "需要完整保留的旧聊天内容。🐳".repeat(450)
        val messages = buildJsonArray {
            repeat(180) { index ->
                add(buildJsonObject {
                    put("id", "long-$index")
                    put("role", if (index % 2 == 0) "user" else "assistant")
                    put("content", "$index:$text")
                })
            }
        }
        val input = buildJsonArray { add(buildJsonObject {
            put("id", "large-device-fixture")
            put("title", "设备长记录测试")
            put("messages", messages)
        }) }.toString().toByteArray()
        assertTrue("fixture must exercise multi-MB rows", input.size > 3 * 1024 * 1024)
        first.importFile(input, "synthetic-long.json")
        val conversation = first.state.value.conversations.single()
        val branch = conversation.branches.single()
        assertEquals(180, branch.messages.size)
        assertEquals("179:$text", branch.messages.last().content)
        val archiveId = first.createArchive("设备长记录测试", conversation, branch.id,
            branch.messages.takeLast(2).map { it.id }.toSet(), "手动背景")
        val entry = first.state.value.archives.single().entries.first()
        first.saveEntry(archiveId, entry.copy(text = "请一直保留我的修订", pinned = true))
        repeat(3) { first.saveBackground(archiveId, "第${it + 1}次修订") }
        first.saveSettings(ServiceSettings(mode = "byok", apiKey = "device-test-key-no-network"))
        val backup = first.backup()
        assertFalse(backup.contains("device-test-key-no-network"))
        application.filesDir.parentFile!!.resolve("shared_prefs").listFiles().orEmpty().forEach {
            assertFalse("secret in preferences ${it.name}", it.readText().contains("device-test-key-no-network"))
        }
        instrumentation.runOnMainSync { firstStore.clear() }
        val (secondStore, second) = controller()
        // operation() waits for the asynchronous Room and Keystore initialization.
        second.backup()
        assertEquals("device-test-key-no-network", second.state.value.settings.apiKey)
        assertEquals("请一直保留我的修订", second.state.value.archives.single().entries.first().text)
        second.restoreBackup(backup)
        assertEquals(180, second.state.value.conversations.single().branches.single().messages.size)
        assertTrue(second.state.value.archives.single().entries.first().pinned)
        assertTrue(second.revisions(archiveId).size >= 3)
        second.clearLocalData()
        second.saveSettings(ServiceSettings())
        instrumentation.runOnMainSync { secondStore.clear() }
    }

    @Test
    fun multiMegabytePayloadsAreReadAcrossDatabaseReopen() = runBlocking<Unit> {
        val name = "segmentation-device-test.db"
        application.deleteDatabase(name)
        val value = "保持来源与emoji🐳。".repeat(220_000)
        val first = Room.databaseBuilder(application, AppDatabase::class.java, name).build()
        first.records().put(SourceRow("raw", "fixture.json", value, 1))
        first.records().put(ConversationRow("conversation", value))
        first.records().put(TaskRow("task", "archive", 1, value))
        first.close()
        val reopened = Room.databaseBuilder(application, AppDatabase::class.java, name).build()
        assertEquals(value, reopened.records().sources().single().bytesBase64)
        assertEquals(value, reopened.records().conversations().single().payload)
        assertEquals(value, reopened.records().tasks().single().payload)
        reopened.close()
        application.deleteDatabase(name)
    }

    @Test
    fun versionOneLargeRowsMigrateWithoutCursorWindowFailure() = runBlocking<Unit> {
        val name = "migration-device-test.db"
        application.deleteDatabase(name)
        val value = "旧版记录🐳需要完整迁移。".repeat(220_000)
        application.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("CREATE TABLE sources (id TEXT NOT NULL PRIMARY KEY, fileName TEXT NOT NULL, bytesBase64 TEXT NOT NULL, importedAt INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE conversations (id TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE archives (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE revisions (id TEXT NOT NULL PRIMARY KEY, archiveId TEXT NOT NULL, savedAt INTEGER NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE conflicts (id TEXT NOT NULL PRIMARY KEY, archiveId TEXT NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE tasks (id TEXT NOT NULL PRIMARY KEY, archiveId TEXT NOT NULL, updatedAt INTEGER NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE chunk_cache (id TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL)")
            db.execSQL("INSERT INTO sources VALUES (?, ?, ?, ?)", arrayOf("old", "old.json", value, 1))
            db.execSQL("INSERT INTO conversations VALUES (?, ?)", arrayOf("old", value))
            db.version = 1
        }
        val migrated = Room.databaseBuilder(application, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2).build()
        assertEquals(value, migrated.records().sources().single().bytesBase64)
        assertEquals(value, migrated.records().conversations().single().payload)
        migrated.close()
        application.deleteDatabase(name)
    }
}
