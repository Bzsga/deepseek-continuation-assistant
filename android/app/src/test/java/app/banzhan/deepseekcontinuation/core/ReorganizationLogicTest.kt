package app.banzhan.deepseekcontinuation.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ReorganizationLogicTest {
    @Test fun rebuildCreatesSeparateSummaryAndKeepsEveryUserDecisionAndSetting() {
        val automatic = entry("automatic", "旧自动摘要", "s0")
        val edited = entry("edited", "手工修订", "s1").copy(edited = true)
        val deleted = entry("deleted", "已删除", "s2").copy(deleted = true)
        val pinned = entry("pinned", "固定内容", "s3").copy(pinned = true)
        val manual = entry("manual", "无来源手填内容")
        val original = Archive("old", "原名称", listOf("c"), listOf(automatic, edited, deleted, pinned, manual),
            updatedAt = 10, background = "完整背景", generationMode = "legacy",
            continuity = ContinuitySettings(enabled = false, includeRecent = false, recentTurns = 2,
                selectedFragmentIds = emptyList(), interactionNotes = "手填交流方式", nextMessage = "手填下一句"),
            sourceScopes = listOf(ArchiveSourceScope("c", "b", listOf("s0", "s1", "s2", "s3"))))
        val snapshot = Json.encodeToString(original)
        val rebuilt = ReorganizationLogic.rebuildArchive(original, "new", updatedAt = 20)
        assertEquals("new", rebuilt.id)
        assertEquals("old", rebuilt.rebuiltFromArchiveId)
        assertEquals(original.title, rebuilt.title)
        assertEquals(20L, rebuilt.updatedAt)
        assertEquals("summary", rebuilt.generationMode)
        assertEquals(listOf(edited, deleted, pinned, manual), rebuilt.entries)
        assertEquals(original.background, rebuilt.background)
        assertEquals(original.continuity, rebuilt.continuity)
        assertEquals(original.sourceScopes, rebuilt.sourceScopes)
        assertEquals(original.conversationIds, rebuilt.conversationIds)
        assertEquals(snapshot, Json.encodeToString(original))
        assertEquals(rebuilt, Json.decodeFromString<Archive>(Json.encodeToString(rebuilt)))
        assertNull(Json.decodeFromString<Archive>("""{"id":"old","title":"旧","conversationIds":[],"entries":[],"updatedAt":1}""").rebuiltFromArchiveId)
        assertEquals("自定名称", ReorganizationLogic.rebuildArchive(original, "other", "自定名称").title)
        assertThrows(IllegalArgumentException::class.java) { ReorganizationLogic.rebuildArchive(original, original.id) }
    }

    @Test fun changedIdsAndCuesCannotBypassProtectedSourcesAndMixedSuggestionsStayWhole() {
        val deleted = entry("old-deleted", "不再沿用的事实", "s1").copy(deleted = true)
        val edited = entry("old-edited", "我已修订的事实", "s2").copy(edited = true)
        val mixed = entry("new-cue-id", "一个不可按来源随意拆分的完整新结果。", "s1", "s2", "new-source")
            .copy(continuityCue = ContinuityCue.OPEN_THREAD)
        val safe = entry("safe", "其他来源的新结果照常保存。", "s3")
        val result = ReorganizationLogic.mergeRebuiltEntries(listOf(deleted, edited), listOf(mixed, safe))
        assertEquals(listOf(deleted, edited, safe), result.entries)
        assertEquals(listOf(EntryConflict(deleted, mixed), EntryConflict(edited, mixed)), result.conflicts)
        assertTrue(result.conflicts.all { it.proposed == mixed })
        val unchanged = ReorganizationLogic.mergeRebuiltEntries(listOf(deleted), listOf(deleted.copy(deleted = false)))
        assertEquals(listOf(deleted), unchanged.entries)
        assertTrue(unchanged.conflicts.isEmpty())
    }

    @Test fun noSourceManualContentIsPreservedWithoutInventingTextSimilarityLinks() {
        val manual = entry("manual", "相同的句子")
        val separate = entry("new", manual.text, "s1")
        val result = ReorganizationLogic.mergeRebuiltEntries(listOf(manual), listOf(separate))
        assertEquals(listOf(manual, separate), result.entries)
        assertTrue(result.conflicts.isEmpty())
        val collision = ReorganizationLogic.mergeRebuiltEntries(listOf(manual), listOf(separate.copy(id = manual.id)))
        assertEquals(listOf(manual), collision.entries)
        assertEquals(1, collision.conflicts.size)
    }

    @Test fun groupsIncludeAllSuggestionsSharingProtectionAndCrossSideIdentity() {
        val first = entry("old1", "删除项", "s1").copy(deleted = true)
        val second = entry("old2", "修订项", "s2").copy(edited = true)
        val mixed = entry("new1", "混合完整提议", "s1", "s2")
        val other = entry("new2", "第二个完整提议", "s2")
        val isolated = EntryConflict(entry("isolated-old", "独立旧", "s3"), entry("isolated-new", "独立新", "s3"))
        val pairs = listOf(EntryConflict(first, mixed), isolated, EntryConflict(second, mixed), EntryConflict(second, other))
        assertEquals(listOf(listOf(pairs[0], pairs[2], pairs[3]), listOf(isolated)), ReorganizationLogic.groupConflicts(pairs + pairs[0]))
        val crossSide = EntryConflict(other, entry("new3", "沿用另一个建议编号", "s4"))
        assertEquals(4, ReorganizationLogic.groupConflicts(pairs + crossSide).first().size)
    }

    @Test fun acceptingACompleteGroupReplacesAllRelatedEntriesAndKeepsEveryProposalOnce() {
        val first = entry("old1", "删除且固定", "s1").copy(deleted = true, pinned = true)
        val second = entry("old2", "手改内容", "s2").copy(edited = true)
        val mixed = entry("new1", "完整混合新结果", "s1", "s2").copy(continuityCue = ContinuityCue.OPEN_THREAD)
        val other = entry("new2", "另一完整新结果", "s2")
        val safe = entry("safe", "不相关内容", "s3")
        val result = ReorganizationLogic.mergeRebuiltEntries(listOf(first, second, safe), listOf(mixed, other))
        val group = ReorganizationLogic.groupConflicts(result.conflicts).single()
        val unrelated = EntryConflict(safe, safe.copy(text = "不相关提议"))
        val accepted = ReorganizationLogic.resolveConflictGroup(result.entries, result.conflicts + unrelated, group, true)
        assertEquals(listOf(safe, mixed.copy(edited = true, pinned = true), other.copy(edited = true)), accepted.entries)
        assertEquals(listOf(unrelated), accepted.conflicts)
        assertTrue(accepted.conflicts.all { pending -> accepted.entries.any { it.id == pending.existing.id } })
        val kept = ReorganizationLogic.resolveConflictGroup(result.entries, result.conflicts + unrelated, group, false)
        assertEquals(result.entries, kept.entries)
        assertEquals(listOf(unrelated), kept.conflicts)
    }

    @Test fun partialOrChangedGroupsCannotApproveUnseenSuggestions() {
        val old = entry("old", "固定", "s1").copy(pinned = true)
        val one = EntryConflict(old, entry("new1", "建议一", "s1"))
        val two = EntryConflict(old, entry("new2", "建议二", "s1"))
        assertThrows(IllegalArgumentException::class.java) {
            ReorganizationLogic.resolveConflictGroup(listOf(old), listOf(one, two), listOf(one), true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReorganizationLogic.resolveConflictGroup(listOf(old), listOf(one.copy(proposed = one.proposed.copy(text = "更长的新建议"))), listOf(one), true)
        }
    }

    @Test fun laterEditsDeletionsAndPinsInvalidatePreviouslyShownApproval() {
        val old = entry("old", "原修订", "s1").copy(edited = true)
        val proposal = entry("new", "来源相交的完整建议", "s1", "s2")
        val pending = listOf(EntryConflict(old, proposal))
        assertThrows(IllegalArgumentException::class.java) {
            ReorganizationLogic.resolveConflictGroup(listOf(old.copy(text = "再次修订")), pending, pending, true)
        }
        for (later in listOf(entry("later", "刚删除", "s2").copy(deleted = true),
                entry("later", "刚编辑", "s2").copy(edited = true), entry("later", "刚固定", "s2").copy(pinned = true))) {
            assertThrows(IllegalArgumentException::class.java) {
                ReorganizationLogic.resolveConflictGroup(listOf(old, later), pending, pending, true)
            }
        }
        val current = listOf(old.copy(text = "再次修订"))
        val kept = ReorganizationLogic.resolveConflictGroup(current, pending, pending, false)
        assertEquals(current, kept.entries)
        assertTrue(kept.conflicts.isEmpty())
    }

    @Test fun inconsistentSnapshotsCannotDeleteUnreviewedContent() {
        val old = entry("old", "旧内容", "s1").copy(edited = true)
        val new = entry("new", "新内容", "s1")
        val inconsistent = listOf(EntryConflict(old, new), EntryConflict(old.copy(text = "另一个旧版本"), new))
        assertThrows(IllegalArgumentException::class.java) {
            ReorganizationLogic.resolveConflictGroup(listOf(old), inconsistent, inconsistent, true)
        }
        val proposedVariants = listOf(EntryConflict(old, new), EntryConflict(old, new.copy(text = "另一个新版本")))
        assertThrows(IllegalArgumentException::class.java) {
            ReorganizationLogic.resolveConflictGroup(listOf(old), proposedVariants, proposedVariants, true)
        }
    }

    private fun entry(id: String, text: String, vararg sourceIds: String) =
        ArchiveEntry(id, EntryCategory.RECENT, text, sourceIds.toList(), EntryKind.USER_FACT)
}
