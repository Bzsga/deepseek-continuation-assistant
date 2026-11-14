package app.banzhan.deepseekcontinuation.core

/** Short selections fit comfortably as original dialogue; never rewrite or trim them here. */
object ConversationPreparation {
    fun prefersOriginal(messages: List<ChatMessage>): Boolean =
        messages.isNotEmpty() && messages.size <= 30 && messages.any { it.content.isNotBlank() } &&
            messages.sumOf { it.content.length.toLong() } <= 6_000L
}
