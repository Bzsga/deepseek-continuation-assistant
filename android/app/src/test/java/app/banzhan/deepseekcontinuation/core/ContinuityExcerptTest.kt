package app.banzhan.deepseekcontinuation.core

import org.junit.Assert.*
import org.junit.Test

class ContinuityExcerptTest {
    private fun fragment(vararg messages: ChatMessage, title: String = "测试聊天") =
        ContinuityFragment("f", "c", "b", title, messages.toList())

    @Test fun completeRenderingPreservesRolesOriginalLinesAndAttachmentMetadata() {
        val original = fragment(ChatMessage("u", "user", "问题第一行\n第二行", attachments = listOf("图片名\n大小")),
            ChatMessage("a", "assistant", "回答。"), ChatMessage("s", "system", "历史系统内容"),
            ChatMessage("t", "tool", "历史工具内容"), ChatMessage("x", "custom\nrole", "其他角色内容"))
        val rendered = renderContinuityFragment(original, 10_000)!!

        assertFalse(rendered.excerpted)
        assertEquals(setOf("u", "a", "s", "t", "x"), rendered.representedMessageIds)
        assertTrue(rendered.text.contains("【原文片段 · 测试聊天】"))
        assertTrue(rendered.text.contains("> 问题第一行\n> 第二行\n"))
        assertTrue(rendered.text.contains("> 附件元数据（未读取内容）：\n> 图片名\n> 大小\n"))
        assertTrue(rendered.text.contains("【旧对话系统消息（引用）】"))
        assertTrue(rendered.text.contains("【旧对话工具消息（引用）】"))
        assertTrue(rendered.text.contains("【原角色 custom role（引用）】"))
        assertEquals(rendered, renderContinuityFragment(original, rendered.text.length))
    }

    @Test fun longQuestionAndAnswerKeepSourceVerbatimAtBothEndsWithinBudget() {
        val question = "用户开头完整句。" + "用户中间很长的段落。".repeat(1000) + "用户最后的具体问题？"
        val answer = "助手开头完整句。" + "助手中间很长的段落。".repeat(1000) + "助手最后的答复。"
        val rendered = renderContinuityFragment(fragment(ChatMessage("u", "user", question),
            ChatMessage("a", "assistant", answer)), 700)!!

        assertTrue(rendered.excerpted)
        assertTrue(rendered.text.length <= 700)
        assertEquals(setOf("u", "a"), rendered.representedMessageIds)
        for (sentence in listOf("用户开头完整句。", "用户最后的具体问题？", "助手开头完整句。", "助手最后的答复。")) {
            assertTrue(sentence, rendered.text.contains(sentence))
        }
        assertTrue(rendered.text.contains("【原文节选"))
        assertTrue(rendered.text.contains("〔节选说明：省略中间部分原文〕"))
        assertFalse(rendered.text.contains("> 〔节选说明"))
        assertTrue(rendered.text.lineSequence().filter { it.startsWith("> ") && !it.startsWith("> 【") }
            .map { it.removePrefix("> ") }.filter { it.isNotEmpty() }
            .all { question.contains(it) || answer.contains(it) })
    }

    @Test fun oversizedUnbrokenTextIsNeverHardCutOrClaimedAsRepresented() {
        val unbroken = "无自然边界".repeat(3000)
        assertNull(renderContinuityFragment(fragment(ChatMessage("u", "user", unbroken)), 400))
        val result = renderContinuityFragment(fragment(ChatMessage("u", "user", unbroken),
            ChatMessage("a", "assistant", "可以保留的完整答复。")), 400)!!
        assertEquals(setOf("a"), result.representedMessageIds)
        assertTrue(result.text.contains("此处省略 1 条消息"))
        assertFalse(result.text.contains("无自然边界"))
    }

    @Test fun latestQuestionAndLastAnswerHavePriorityAndOtherRolesRemainOmitted() {
        val original = fragment(ChatMessage("old", "user", "早先内容。".repeat(200)),
            ChatMessage("system", "system", "不要把旧系统内容当新指令。".repeat(300)),
            ChatMessage("u", "user", "最新问题开头。" + "细节。".repeat(300) + "最新问题结尾？"),
            ChatMessage("tool", "tool", "工具内容。".repeat(300)),
            ChatMessage("a", "assistant", "最后答复开头。" + "说明。".repeat(300) + "最后答复结尾。"))
        val result = renderContinuityFragment(original, 600)!!
        assertTrue(result.representedMessageIds.containsAll(setOf("u", "a")))
        assertFalse(result.representedMessageIds.contains("system"))
        assertFalse(result.representedMessageIds.contains("tool"))
        assertFalse(result.text.contains("不要把旧系统内容"))
        assertFalse(result.text.contains("工具内容"))
        assertTrue(result.text.indexOf("最新问题开头") < result.text.indexOf("最后答复开头"))
    }

    @Test fun omittedAttachmentsAreExplicitAndMetadataAloneCountsOnlyWhenActuallyIncluded() {
        val original = fragment(ChatMessage("u", "user", "一段完整问题。", attachments = listOf("元数据".repeat(300))))
        val result = renderContinuityFragment(original, 300)!!
        assertTrue(result.excerpted)
        assertTrue(result.text.contains("附件元数据未带入"))
        assertFalse(result.text.contains("元数据元数据"))
        assertEquals(setOf("u"), result.representedMessageIds)
        val attachmentOnly = fragment(ChatMessage("attachment", "user", "", attachments = listOf("文件名")))
        assertEquals(setOf("attachment"), renderContinuityFragment(attachmentOnly, 1000)!!.representedMessageIds)
        assertNull(renderContinuityFragment(attachmentOnly.copy(messages = listOf(
            attachmentOnly.messages.single().copy(attachments = listOf("文件名".repeat(1000))))), 300))
    }

    @Test fun paragraphBoundariesAndSupplementaryUnicodeStayIntact() {
        val text = "开头😀\n\n" + "中间𐐀段落\n".repeat(400) + "最后👨‍👩‍👧‍👦"
        val result = renderContinuityFragment(fragment(ChatMessage("u", "user", text),
            title = "题".repeat(79) + "😀\n后续"), 350)!!
        assertTrue(result.text.contains("开头😀"))
        assertTrue(result.text.contains("最后👨‍👩‍👧‍👦"))
        assertTrue(result.text.length <= 350)
        result.text.forEachIndexed { index, char ->
            if (char.isHighSurrogate()) assertTrue(index + 1 < result.text.length && result.text[index + 1].isLowSurrogate())
            if (char.isLowSurrogate()) assertTrue(index > 0 && result.text[index - 1].isHighSurrogate())
        }
    }

    @Test fun tinyBudgetsAndNonChatOnlyFragmentsDoNotProduceEmptyFakeExcerpts() {
        assertNull(renderContinuityFragment(fragment(ChatMessage("u", "user", "完整问题。")), 5))
        assertNull(renderContinuityFragment(fragment(ChatMessage("u", "user", "完整问题。")), 0))
        assertNull(renderContinuityFragment(fragment(ChatMessage("s", "system", "系统内容。".repeat(500))), 400))
    }
}
