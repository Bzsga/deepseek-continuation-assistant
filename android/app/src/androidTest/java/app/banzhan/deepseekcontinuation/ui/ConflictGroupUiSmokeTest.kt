package app.banzhan.deepseekcontinuation.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.banzhan.deepseekcontinuation.core.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConflictGroupUiSmokeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun connectedConflictsShowDistinctEntriesAndResolveAsOneObservedGroup() {
        val deleted = entry("old-deleted", "旧条目：这项计划已删除。", deleted = true)
        val edited = entry("old-edited", "旧条目：先写一小段。", edited = true)
        val proposedFirst = entry("new-first", "新条目：正在考虑写作计划。")
        val proposedSecond = entry("new-second", "新条目：可以从短段落开始。")
        val connected = listOf(
            EntryConflict(deleted, proposedFirst),
            EntryConflict(deleted, proposedSecond),
            EntryConflict(edited, proposedSecond),
        )
        val unrelated = EntryConflict(entry("other-old", "另一组旧内容。"), entry("other-new", "另一组新内容。"))
        val observed = AtomicReference<List<EntryConflict>?>(null)
        val useProposed = AtomicBoolean(false)
        val resolveCalls = AtomicInteger(0)
        val reloadCalls = AtomicInteger(0)
        compose.setContent {
            ContinuationTheme {
                ConflictGroupsDialog(
                    conflicts = connected + connected.first() + unrelated,
                    onDismiss = {},
                    onResolve = { group, accepted ->
                        observed.set(group)
                        useProposed.set(accepted)
                        resolveCalls.incrementAndGet()
                    },
                    onReload = { reloadCalls.incrementAndGet() },
                )
            }
        }
        compose.waitForIdle()
        assertEquals(0, resolveCalls.get())
        assertEquals(0, reloadCalls.get())
        listOf(deleted, edited, proposedFirst, proposedSecond).forEach {
            compose.onAllNodesWithText(it.text).assertCountEquals(1)
        }
        compose.onNodeWithText("已删除").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("采用以上 2 条，替换 2 条").performScrollTo()
        saveReorganizationScreenshot("reorganization-conflict.png")
        compose.onNodeWithText("采用以上 2 条，替换 2 条").performClick()
        compose.waitUntil(5_000) { resolveCalls.get() == 1 && reloadCalls.get() == 1 }
        assertTrue(useProposed.get())
        assertEquals(connected.toSet(), observed.get()!!.toSet())
        assertEquals(connected.size, observed.get()!!.size)
        assertFalse(observed.get()!!.contains(unrelated))
    }

    @Test fun failedResolutionStaysVisibleAndRefreshDoesNotResolveAgain() {
        val conflict = EntryConflict(entry("old", "已保存的原内容。", edited = true), entry("new", "新整理的建议。"))
        val resolveCalls = AtomicInteger(0)
        val reloadCalls = AtomicInteger(0)
        val errorText = "当前内容已变化，请刷新。"
        compose.setContent {
            ContinuationTheme {
                ConflictGroupsDialog(
                    conflicts = listOf(conflict),
                    onDismiss = {},
                    onResolve = { _, _ ->
                        resolveCalls.incrementAndGet()
                        throw IllegalStateException(errorText)
                    },
                    onReload = { reloadCalls.incrementAndGet() },
                )
            }
        }
        compose.onNodeWithText("保留当前").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(errorText).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(errorText).assertIsDisplayed()
        compose.onNodeWithText("保留当前").assertIsNotEnabled()
        assertEquals(1, resolveCalls.get())
        assertEquals(0, reloadCalls.get())
        compose.onNodeWithText("刷新").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { reloadCalls.get() == 1 }
        compose.waitForIdle()
        compose.onNodeWithText(errorText).assertDoesNotExist()
        assertEquals(1, resolveCalls.get())
    }

    private fun entry(id: String, text: String, edited: Boolean = false, deleted: Boolean = false) =
        ArchiveEntry(id, EntryCategory.RECENT, text, edited = edited, deleted = deleted)
}
