package app.banzhan.deepseekcontinuation.ui

import app.banzhan.deepseekcontinuation.core.DeepSeekImporter
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ManualPasteDocumentTest {
    private fun parse(bytes: ByteArray) = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject

    @Test fun rolesOrderAndPartialOriginSurviveNormalImport() {
        val bytes = manualPasteDocument("复制记录", listOf(
            ManualPastedText(0, content = "说话方尚不确定"),
            ManualPastedText(1, role = "user", content = "我的补充"),
            ManualPastedText(2, role = "assistant", content = "模型的建议")))
        val document = parse(bytes)
        assertFalse(document.getValue("complete_export").jsonPrimitive.boolean)
        val conversation = DeepSeekImporter.parse(bytes, "manual-paste.json").conversations.single()
        assertTrue(conversation.title.startsWith(MANUAL_PASTE_TITLE_PREFIX))
        val messages = conversation.branches.single().messages
        assertEquals(listOf("unknown", "user", "assistant"), messages.map { it.role })
        assertEquals(listOf("说话方尚不确定", "我的补充", "模型的建议"), messages.map { it.content })
    }

    @Test fun repeatIdentityIgnoresUiSelectionButRespectsRoleAndOrder() {
        val items = listOf(ManualPastedText(2, "user", "甲"), ManualPastedText(3, "assistant", "乙"))
        val first = parse(manualPasteDocument("原标题", items)).getValue("id")
        val same = parse(manualPasteDocument("改标题", items.map { it.copy(rowId = it.rowId + 50, selected = true) })).getValue("id")
        assertEquals(first, same)
        assertNotEquals(first, parse(manualPasteDocument("原标题", items.reversed())).getValue("id"))
        assertNotEquals(first, parse(manualPasteDocument("原标题", items.map { it.copy(role = "unknown") })).getValue("id"))
    }

    @Test fun rawWhitespaceQuotesAndUnicodeArePreservedInDocument() {
        val text = "  第一行\r\n\t\"引号\" \\ 路径 🐳\n  "
        val document = parse(manualPasteDocument("原文", listOf(ManualPastedText(0, content = text))))
        assertEquals(text, document.getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonPrimitive.content)
    }

    @Test fun blankRowsAreExcludedAndEmptyInputRejected() {
        val document = parse(manualPasteDocument("标题", listOf(ManualPastedText(0, content = " \n"), ManualPastedText(1, content = "保留"))))
        assertEquals(1, document.getValue("messages").jsonArray.size)
        assertThrows(IllegalArgumentException::class.java) { manualPasteDocument("标题", listOf(ManualPastedText(0))) }
        assertThrows(IllegalArgumentException::class.java) { manualPasteDocument(" ", listOf(ManualPastedText(0, content = "正文"))) }
        assertThrows(IllegalArgumentException::class.java) { manualPasteDocument("标题", listOf(ManualPastedText(0, role = "system", content = "正文"))) }
    }
}
