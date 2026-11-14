package app.banzhan.deepseekcontinuation.core

import org.junit.Assert.*
import org.junit.Test

class ConversationPreparationTest {
    @Test fun shortDialogueWithUncertaintyAndFormattingPrefersOriginal() {
        val messages = listOf(ChatMessage("a", "user", "嗯…我还没决定。\n先留着？"),
            ChatMessage("b", "assistant", "好，等你想聊的时候再说。"))
        assertTrue(ConversationPreparation.prefersOriginal(messages))
    }

    @Test fun aSingleLongMessageAndManyShortTurnsStillOfferOrganizationFirst() {
        assertFalse(ConversationPreparation.prefersOriginal(listOf(ChatMessage("a", "user", "字".repeat(6001)))))
        assertFalse(ConversationPreparation.prefersOriginal((1..31).map { ChatMessage("$it", "user", "好") }))
        assertTrue(ConversationPreparation.prefersOriginal((1..30).map { ChatMessage("$it", "user", "字".repeat(200)) }))
    }

    @Test fun emptyInputIsNotAnOriginalRecommendation() {
        assertFalse(ConversationPreparation.prefersOriginal(emptyList()))
        assertFalse(ConversationPreparation.prefersOriginal(listOf(ChatMessage("a", "user", " \n"))))
    }
}
