package app.banzhan.deepseekcontinuation.ui

import app.banzhan.deepseekcontinuation.core.Archive
import app.banzhan.deepseekcontinuation.data.AppState
import app.banzhan.deepseekcontinuation.data.OrganizationTask
import org.junit.Assert.*
import org.junit.Test
import androidx.lifecycle.SavedStateHandle

class MessageSelectionMemoryTest {
    @Test fun savedCompactRangeRestoresAfterViewModelRecreation() {
        val saved = SavedStateHandle()
        val all = (0 until 10_000).map { "long-source-id-$it" }.toSet()
        val selected = all.filterIndexed { index, _ -> index >= 100 && index < 9900 }.toSet()
        MessageSelectionMemory(saved).range("prepare:c:b", all, false).select(selected)
        val restored = MessageSelectionMemory(saved).range("prepare:c:b", all, true)
        assertEquals(selected, restored.ids)
        assertFalse(restored.needsReview)
        assertTrue(saved.get<ArrayList<String>>("selection_ranges")!!.sumOf { it.length } < 200)
        val changed = MessageSelectionMemory(saved).range("prepare:c:b", all + "new-message", true)
        assertTrue(changed.ids.isEmpty())
        assertTrue(changed.needsReview)
    }

    @Test fun compactSnapshotsPreserveEmptySelectionsAndRefuseOversizeOrMismatchedRanges() {
        val all = (0 until 10_000).map { "m$it" }.toSet()
        assertEquals(emptySet<String>(), restoreSelection(selectionSnapshot(all, emptySet())!!, all))
        assertEquals(all, restoreSelection(selectionSnapshot(all, all)!!, all))
        assertNull(selectionSnapshot(all, all.filterIndexed { index, _ -> index % 2 == 0 }.toSet()))
        assertNull(restoreSelection(selectionSnapshot(all, setOf("m1"))!!, all.reversed().toSet()))
        assertNull(restoreSelection(selectionSnapshot(all, setOf("m1"))!! + ",10000", all))
    }

    @Test fun lostCustomRangeNeverSilentlyExpandsToAllMessagesAndCanBeReselected() {
        val memory = MessageSelectionMemory()
        val all = setOf("m1", "m2")
        val restored = memory.range("prepare:c:b", all, hadCustomSelection = true)
        assertTrue(restored.ids.isEmpty())
        assertTrue(restored.needsReview)
        restored.select(all)
        val afterQuotaRefresh = memory.range("prepare:c:b", all, hadCustomSelection = true)
        assertEquals(all, afterQuotaRefresh.ids)
        assertFalse(afterQuotaRefresh.needsReview)
    }

    @Test fun defaultRangeRestoresAllButExplicitEmptySelectionStaysEmpty() {
        val memory = MessageSelectionMemory()
        val all = setOf("m1", "m2")
        val range = memory.range("prepare:c:b", all, false)
        assertEquals(all, range.ids)
        range.select(emptySet())
        assertTrue(memory.range("prepare:c:b", all, true).ids.isEmpty())
        assertFalse(range.needsReview)
    }

    @Test fun staleSelectedIdsAreRemovedWithoutAddingOtherMessages() {
        val memory = MessageSelectionMemory()
        val range = memory.range("prepare:c:b", setOf("m1", "m2"), false)
        range.select(setOf("m1"))
        memory.range("prepare:c:b", setOf("m2", "m3"), true)
        assertTrue(range.ids.isEmpty())
        assertTrue(range.needsReview)
    }

    @Test fun preparationFindsSavedProgressOnlyForThisConversationAndUnfinishedTasks() {
        val archive = Archive("a", "已有记录", listOf("c"), emptyList())
        val task = OrganizationTask("t", "a", "已有记录", "paused", 8, 10)
        val state = AppState(archives = listOf(archive), tasks = listOf(task))
        assertEquals(task, resumablePreparationTask("c", state))
        assertNull(resumablePreparationTask("other", state))
        assertNull(resumablePreparationTask("c", state.copy(tasks = listOf(task.copy(status = "completed")))))
        assertNull(resumablePreparationTask("c", state.copy(tasks = listOf(task.copy(status = "cancelled")))))
    }
}
