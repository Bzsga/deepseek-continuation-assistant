package app.banzhan.deepseekcontinuation.core

import org.junit.Assert.*
import org.junit.Test

class ContinuityFocusTest {
    @Test fun chineseAndEnglishTopicWordsMatchWithoutCommonChatFiller() {
        val focus = ContinuityFocus.fromText("我想继续讨论 PostgreSQL 数据库连接超时")
        assertTrue(focus.score("PostgreSQL 连接超时需要检查连接池。") > 0)
        assertEquals(0, focus.score("今晚去公园散步，顺便买晚饭。"))
        assertTrue(ContinuityFocus.fromText("请继续讨论一下这个问题").isEmpty)
        assertTrue(ContinuityFocus.fromText("Can you please continue this chat").isEmpty)
        assertEquals(focus.score("PostgreSQL 数据库连接超时"),
            focus.score("PostgreSQL 数据库连接超时".repeat(100)))
    }

    @Test fun currentWrittenMessageHasMoreWeightThanRecentAssistantWords() {
        val message = ChatMessage("m", "assistant", "明天讨论 Android 界面")
        val archive = archive(listOf(message)).copy(continuity = ContinuitySettings(
            enabled = true, nextMessage = "今天继续 PostgreSQL 数据库"))
        val focus = ContinuityFocus.forArchive(archive, listOf(conversation(listOf(message))))
        assertTrue(focus.score("PostgreSQL") > focus.score("Android"))
        assertTrue(focus.score("Android") > 0)
    }

    @Test fun onlyExactSelectedMessagesCanSupplyTheTopic() {
        val selected = ChatMessage("selected", "user", "数据库连接超时")
        val outside = ChatMessage("outside", "user", "周末去海边露营")
        val otherBranch = ConversationBranch("alternative", "other", listOf(
            ChatMessage("foreign", "user", "魔兽地图装备")))
        val source = conversation(listOf(selected, outside)).copy(branches = listOf(
            conversation(listOf(selected, outside)).branches.single(), otherBranch))
        val focus = ContinuityFocus.forArchive(archive(listOf(selected)), listOf(source))
        assertTrue(focus.score("数据库") > 0)
        assertEquals(0, focus.score("露营"))
        assertEquals(0, focus.score("魔兽地图"))
    }

    @Test fun invalidOrMultipleBranchesUseOnlyCurrentWrittenMessage() {
        val message = ChatMessage("m", "user", "数据库连接超时")
        val another = ConversationBranch("alternative", "other", listOf(ChatMessage("n", "user", "去海边露营")))
        val source = conversation(listOf(message)).copy(branches = listOf(
            conversation(listOf(message)).branches.single(), another))
        val base = archive(listOf(message)).copy(continuity = ContinuitySettings(enabled = true, nextMessage = "Android"))
        val ambiguous = base.copy(sourceScopes = base.sourceScopes + ArchiveSourceScope("c", "alternative", listOf("n")))
        val invalid = base.copy(sourceScopes = listOf(ArchiveSourceScope("c", "main", listOf("missing", "m"))))
        for (candidate in listOf(ambiguous, invalid, base.copy(sourceScopes = emptyList()))) {
            val focus = ContinuityFocus.forArchive(candidate, listOf(source))
            assertTrue(focus.score("Android") > 0)
            assertEquals(0, focus.score("数据库"))
            assertEquals(0, focus.score("露营"))
        }
    }

    @Test fun protectedEndpointCannotReviveOldTopicThroughRawSources() {
        val messages = listOf(ChatMessage("old", "user", "数据库连接超时"),
            ChatMessage("last", "assistant", "数据库部署建议"))
        val protectedEntry = ArchiveEntry("e", EntryCategory.RECENT, "用户手改后的内容", listOf("last"), edited = true)
        val archive = archive(messages).copy(entries = listOf(protectedEntry))
        assertTrue(ContinuityFocus.forArchive(archive, listOf(conversation(messages))).isEmpty)
        val deleted = archive.copy(entries = listOf(protectedEntry.copy(edited = false, deleted = true)))
        assertTrue(ContinuityFocus.forArchive(deleted, listOf(conversation(messages))).isEmpty)
    }

    @Test fun disabledRecentAndDisabledContinuityDoNotUseOriginalWords() {
        val messages = listOf(ChatMessage("m", "user", "数据库连接超时"))
        val base = archive(messages)
        val noRecent = base.copy(continuity = ContinuitySettings(enabled = true, includeRecent = false, nextMessage = "Android"))
        val focus = ContinuityFocus.forArchive(noRecent, listOf(conversation(messages)))
        assertTrue(focus.score("Android") > 0)
        assertEquals(0, focus.score("数据库"))
        assertTrue(ContinuityFocus.forArchive(base.copy(continuity = noRecent.continuity.copy(enabled = false)),
            listOf(conversation(messages))).isEmpty)
    }

    private fun archive(messages: List<ChatMessage>) = Archive("a", "chat", listOf("c"), emptyList(),
        continuity = ContinuitySettings(enabled = true),
        sourceScopes = listOf(ArchiveSourceScope("c", "main", messages.map { it.id })))

    private fun conversation(messages: List<ChatMessage>) = ImportedConversation("c", "chat",
        branches = listOf(ConversationBranch("main", "main", messages)))
}
