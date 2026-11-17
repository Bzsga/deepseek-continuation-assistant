package app.banzhan.deepseekcontinuation.core

import org.junit.Assert.*
import org.junit.Test

class DeepSeekShareParserTest {
    @Test fun importsExactWordingRolesTimestampsAndAnUnansweredLastQuestion() {
        val result = parse("""{"code":0,"data":{"biz_code":0,"biz_data":{"title":"测试分享","inserted_at":"2026-11-17","messages":[
          {"id":1,"role":"USER","inserted_at":"t1","fragments":[{"type":"REQUEST","content":"  我想保留原话。\n还有下一行。  "}]},
          {"id":2,"role":"ASSISTANT","fragments":[{"type":"RESPONSE","content":"好的。"}]},
          {"id":3,"fragments":[{"type":"REQUEST","content":"最后这个问题还没回答？"}]}]}}}""")
        val conversation = result.conversations.single()
        assertEquals("测试分享", conversation.title)
        assertEquals("2026-11-17", conversation.createdAt)
        val messages = conversation.branches.single().messages
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.role })
        assertEquals("  我想保留原话。\n还有下一行。  ", messages[0].content)
        assertEquals("t1", messages[0].timestamp)
        assertEquals("最后这个问题还没回答？", messages.last().content)
        assertTrue(result.warnings.any { it.contains("分享范围") })
    }

    @Test fun stableShareIdentityDoesNotCollideWithAnExportAndRetainsBranches() {
        val source = """{"id":"abc123","title":"分支","current_message_id":3,"messages":[
          {"id":1,"parent_id":null,"role":"user","content":"共同问题"},
          {"id":2,"parent_id":1,"role":"assistant","content":"版本一"},
          {"id":3,"parent_id":1,"role":"assistant","content":"版本二"}]}"""
        val share = parse(source).conversations.single()
        assertEquals(share, parse(source).conversations.single())
        assertNotEquals(DeepSeekImporter.parse(source.toByteArray(), "export.json").conversations.single().id, share.id)
        assertEquals(2, share.branches.size)
        assertEquals("版本二", share.branches.single { it.isCurrent }.messages.last().content)
        assertEquals("共同问题", share.branches.single { it.isCurrent }.messages.first().content)
        assertEquals(share.branches[0].messages[0].id, share.branches[1].messages[0].id)
    }

    @Test fun keepsKnownMetadataOutOfConversationProseWithAnExplicitWarning() {
        val result = parse("""{"data":{"biz_data":{"messages":[
          {"id":1,"role":"USER","fragments":[{"type":"REQUEST","content":"提问"},{"type":"FILE","files":[{"id":"f1","file_name":"示例.pdf"}]}]},
          {"id":2,"role":"ASSISTANT","fragments":[{"type":"THINK","content":"不混入正文"},{"type":"TOOL_SEARCH","queries":["测试"],"results":[]},{"type":"RESPONSE","content":"原始回答"}]}]}}}""")
        val messages = result.conversations.single().branches.single().messages
        assertEquals("原始回答", messages.last().content)
        assertTrue(messages.first().attachments.any { it.contains("示例.pdf") })
        assertTrue(result.warnings.any { it.contains("原始") })
    }

    @Test fun rejectsExpiredMalformedPartialUnknownAndInvalidUtf8Documents() {
        listOf("""{"code":1,"data":{"messages":[]}}""",
            """{"data":{"biz_code":404,"biz_data":{"messages":[]}}}""",
            """{"messages":[]}""", """{"messages":[null]}""",
            """{"has_more":true,"messages":[{"role":"user","content":"部分"}]}""",
            """{"messages":[{"fragments":[{"type":"FUTURE_BODY","content":"private source"}]}]}""",
            """{"messages":[{"role":"user","content":{"type":"future","content":"private source"}}]}""",
            "[".repeat(65) + "]".repeat(65), "<html>private source</html>",
        ).forEach { text ->
            try { parse(text); throw AssertionError("Accepted invalid share") }
            catch (expected: IllegalArgumentException) { assertFalse(expected.message.orEmpty().contains("private source")) }
        }
        try { DeepSeekShareParser.parse(byteArrayOf(0xc3.toByte(), 0x28), "abc123"); fail("Invalid UTF8 accepted") }
        catch (_: IllegalArgumentException) { }
    }

    private fun parse(text: String) = DeepSeekShareParser.parse(text.toByteArray(Charsets.UTF_8), "abc123")
}
