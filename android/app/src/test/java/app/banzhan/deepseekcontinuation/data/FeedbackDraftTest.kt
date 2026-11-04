package app.banzhan.deepseekcontinuation.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FeedbackDraftTest {
    @Test fun draftRequiresContentAndContactAndValidContactType() {
        val valid = FeedbackDraft(content = "合成意见", contactType = "wechat", contact = "synthetic-contact")
        validateFeedbackDraft(valid)
        assertThrows(IllegalArgumentException::class.java) { validateFeedbackDraft(valid.copy(content = " ")) }
        assertThrows(IllegalArgumentException::class.java) { validateFeedbackDraft(valid.copy(contact = "")) }
        assertThrows(IllegalArgumentException::class.java) { validateFeedbackDraft(valid.copy(contactType = "email")) }
        assertThrows(IllegalArgumentException::class.java) { validateFeedbackDraft(valid.copy(content = "x".repeat(5001))) }
    }

    @Test fun newDraftHasNewIdentityAndPrivateFieldsAreNotInDescriptions() {
        val first = FeedbackDraft(content = "private-feedback-text", contact = "private-contact")
        val second = FeedbackDraft()
        assertNotEquals(first.idempotencyKey, second.idempotencyKey)
        assertFalse(first.toString().contains(first.content))
        assertFalse(first.toString().contains(first.contact))
        val submission = FeedbackSubmission(first.idempotencyKey, first.content, "qq", first.contact, "test", emptyList())
        assertFalse(submission.toString().contains(first.content))
        assertFalse(submission.toString().contains(first.contact))
    }
}
