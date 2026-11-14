package app.banzhan.deepseekcontinuation.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.banzhan.deepseekcontinuation.core.Archive
import app.banzhan.deepseekcontinuation.core.ArchiveEntry
import app.banzhan.deepseekcontinuation.core.EntryCategory
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic archives and local callbacks only; these controls must never invoke a model. */
@RunWith(AndroidJUnit4::class)
class ArchivesSelectionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun manageAndLongPressSupportSelectionAndCancellationWithoutDeleting() {
        val archives = mutableStateOf(fixtures())
        val opened = AtomicReference<String?>(null)
        val deletes = AtomicInteger()
        compose.setContent {
            ContinuationTheme {
                ArchivesTestFrame {
                    ArchivesScreen(archives.value, opened::set, {}, onDelete = { deletes.incrementAndGet() })
                }
            }
        }
        compose.onNodeWithText("管理").performClick()
        compose.onNodeWithText("已选 0 项").assertIsDisplayed()
        compose.onNodeWithText("删除（0）").assertIsNotEnabled()
        row("selection-a").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText("已选 1 项").assertIsDisplayed()
        assertNull(opened.get())
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("已选 3 项").assertIsDisplayed()
        listOf("selection-a", "selection-b", "selection-c").forEach { row(it).assertIsOn() }
        // An externally removed record must also disappear from the current selection.
        compose.runOnIdle { archives.value = archives.value.filterNot { it.id == "selection-c" } }
        compose.onNodeWithText("已选 2 项").assertIsDisplayed()
        compose.onNodeWithTag("archive-row:selection-c").assertDoesNotExist()
        screenshot("archives-selected.png")
        compose.onNodeWithText("取消全选").performClick()
        compose.onNodeWithText("已选 0 项").assertIsDisplayed()
        compose.onNodeWithText("删除（0）").assertIsNotEnabled()
        row("selection-a").assertIsOff()
        row("selection-b").assertIsOff()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("管理").assertIsDisplayed()

        row("selection-b").performTouchInput { longClick() }
        row("selection-b").assertIsOn()
        compose.onNodeWithText("已选 1 项").assertIsDisplayed()
        assertNull(opened.get())
        assertEquals(0, deletes.get())
        compose.onNodeWithText("删除（1）").performClick()
        compose.onNodeWithText("删除 1 条续聊记录？").assertIsDisplayed()
        compose.onNodeWithText("原始导入和未选记录会保留", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("archive-delete-confirm").assertIsEnabled()
        screenshot("archives-delete-confirmation.png")
        assertEquals(0, deletes.get())
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("删除 1 条续聊记录？").assertDoesNotExist()
        row("selection-b").assertIsOn()
        assertEquals(0, deletes.get())
        assertEquals(setOf("selection-a", "selection-b"), archives.value.map { it.id }.toSet())
    }

    @Test fun successfulConfirmationDeletesOnlySelectedIdsAndReturnsToNormalBrowsing() {
        val archives = mutableStateOf(fixtures())
        val requested = AtomicReference<Set<String>?>(null)
        val calls = AtomicInteger()
        val opened = AtomicReference<String?>(null)
        compose.setContent {
            ContinuationTheme {
                ArchivesTestFrame {
                    ArchivesScreen(archives.value, opened::set, {}, onDelete = { ids ->
                        calls.incrementAndGet()
                        requested.set(ids.toSet())
                        archives.value = archives.value.filterNot { it.id in ids }
                    })
                }
            }
        }
        compose.onNodeWithText("管理").performClick()
        row("selection-a").performClick()
        row("selection-c").performClick()
        compose.onNodeWithText("删除（2）").performClick()
        compose.onNodeWithText("删除 2 条续聊记录？").assertIsDisplayed()
        assertNull(requested.get())
        compose.onNodeWithTag("archive-delete-confirm").performClick()
        awaitText("管理")
        assertEquals(1, calls.get())
        assertEquals(setOf("selection-a", "selection-c"), requested.get())
        compose.onNodeWithTag("archive-row:selection-a").assertDoesNotExist()
        compose.onNodeWithTag("archive-row:selection-c").assertDoesNotExist()
        compose.onNodeWithText("已选 2 项").assertDoesNotExist()
        compose.onNodeWithTag("archive-delete-confirm").assertDoesNotExist()
        assertEquals(listOf("selection-b"), archives.value.map { it.id })
        screenshot("archives-after-delete.png")
        row("selection-b").assertIsDisplayed().performClick()
        assertEquals("selection-b", opened.get())
    }

    @Test fun suspendedDeletionCannotSubmitTwiceAndFailureKeepsSelectionForRetry() {
        val archives = mutableStateOf(fixtures())
        val attempts = CopyOnWriteArrayList<Set<String>>()
        val releaseFirstAttempt = CompletableDeferred<Unit>()
        val failure = "合成：删除暂时失败，请重试。"
        compose.setContent {
            ContinuationTheme {
                ArchivesTestFrame {
                    ArchivesScreen(archives.value, {}, {}, onDelete = { ids ->
                        attempts.add(ids.toSet())
                        if (attempts.size == 1) {
                            releaseFirstAttempt.await()
                            error(failure)
                        }
                        archives.value = archives.value.filterNot { it.id in ids }
                    })
                }
            }
        }
        compose.onNodeWithText("管理").performClick()
        row("selection-b").performClick()
        compose.onNodeWithText("删除（1）").performClick()
        compose.onNodeWithTag("archive-delete-confirm").performClick()
        compose.waitUntil(5_000) { attempts.size == 1 }
        compose.onNodeWithTag("archive-delete-confirm").assertIsNotEnabled()
            .performTouchInput { click(); click() }
        compose.onNodeWithText("取消").assertIsNotEnabled()
        compose.waitForIdle()
        assertEquals(listOf(setOf("selection-b")), attempts.toList())
        assertEquals(3, archives.value.size)
        screenshot("archives-delete-pending.png")
        compose.runOnIdle { releaseFirstAttempt.complete(Unit) }
        awaitText(failure)
        compose.onNodeWithTag("archive-delete-confirm").assertDoesNotExist()
        compose.onNodeWithText("已选 1 项").assertIsDisplayed()
        row("selection-b").assertIsOn()
        compose.onNodeWithText("删除（1）").assertIsEnabled()
        assertEquals(1, attempts.size)
        assertEquals(3, archives.value.size)
        screenshot("archives-delete-retry.png")

        compose.onNodeWithText("删除（1）").performClick()
        compose.onNodeWithText("删除 1 条续聊记录？").assertIsDisplayed()
        assertEquals(1, attempts.size)
        compose.onNodeWithTag("archive-delete-confirm").performClick()
        awaitText("管理")
        assertEquals(listOf(setOf("selection-b"), setOf("selection-b")), attempts.toList())
        assertEquals(setOf("selection-a", "selection-c"), archives.value.map { it.id }.toSet())
        compose.onNodeWithTag("archive-row:selection-b").assertDoesNotExist()
    }

    /** Mirror the records route's shared app bar; navigation callbacks stay inert. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ArchivesTestFrame(content: @Composable () -> Unit) {
        Scaffold(topBar = {
            TopAppBar(
                title = { Text("Deepseek续聊助手", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold) },
                actions = { IconButton(onClick = {}) { Icon(Icons.Outlined.Checklist, "整理进度") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Mist),
            )
        }, bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(false, {}, icon = { Icon(Icons.Outlined.Home, null) }, label = { Text("首页") })
                NavigationBarItem(true, {}, icon = { Icon(Icons.Outlined.FolderOpen, null) }, label = { Text("续聊记录") })
                NavigationBarItem(false, {}, icon = { Icon(Icons.Outlined.Settings, null) }, label = { Text("设置") })
            }
        }) { innerPadding ->
            Column(Modifier.fillMaxSize().padding(innerPadding)) { content() }
        }
    }

    private fun row(id: String): SemanticsNodeInteraction {
        val tag = "archive-row:$id"
        compose.onNodeWithTag("archives-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }

    private fun awaitText(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun fixtures(): List<Archive> = listOf(
        Archive("selection-a", "合成写作记录", emptyList(), listOf(
            ArchiveEntry("entry-a", EntryCategory.RECENT, "这次只讨论短篇开头。", edited = true),
        ), updatedAt = 3, generationMode = "summary"),
        Archive("selection-b", "合成待保留记录", emptyList(), listOf(
            ArchiveEntry("entry-b", EntryCategory.RECENT, "这个想法还没有决定。", edited = true),
        ), updatedAt = 2, generationMode = "summary"),
        Archive("selection-c", "合成周末记录", emptyList(), listOf(
            ArchiveEntry("entry-c", EntryCategory.RECENT, "下次继续聊周末安排。", edited = true),
        ), updatedAt = 1, generationMode = "summary"),
    )

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrument = InstrumentationRegistry.getInstrumentation()
        instrument.waitForIdleSync()
        instrument.uiAutomation.waitForIdle(100, 2_000)
        val bitmap = instrument.uiAutomation.takeScreenshot() ?: error("Could not capture archive selection")
        val directory = File(instrument.targetContext.filesDir, "archive-selection-qa")
        check(directory.isDirectory || directory.mkdirs())
        try {
            File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }
}
