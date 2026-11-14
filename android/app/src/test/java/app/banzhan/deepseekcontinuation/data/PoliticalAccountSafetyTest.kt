package app.banzhan.deepseekcontinuation.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class PoliticalAccountSafetyTest {
    private val text = "讨论内容检查。"
    private val session = AccountSession("https://accounts.example.test/service", "account-token", "2099-01-01T00:00:00+08:00",
        AccountUser("owner-a", "user_a", "public", "2026-11-14T00:00:00+08:00"))
    private val settings = ServiceSettings(mode = "companion", companionUrl = session.serviceUrl,
        trialToken = session.token, contextTokens = 64000, maxOutputTokens = 4096)
    private val batch = PoliticalExportBatch("batch-original", PoliticalFilterProtocol.parts(text))
    private val task = PoliticalExportTask("task-original", "archive", text, hash(text), settings.withoutSecrets(),
        listOf(batch), status = "paused", ownerUserId = "owner-a")

    @Test fun newFreeCheckRequiresExplicitMatchingLoginWhileByokDoesNot() {
        assertEquals("owner-a", politicalOwnerForNewTask(settings, session))
        assertEquals("owner-a", politicalOwnerForNewTask(settings.copy(companionUrl = session.serviceUrl + "/"), session))
        assertThrows(IllegalArgumentException::class.java) { politicalOwnerForNewTask(settings, null) }
        assertThrows(IllegalArgumentException::class.java) { politicalOwnerForNewTask(settings.copy(trialToken = "legacy-token"), session) }
        assertThrows(IllegalArgumentException::class.java) { politicalOwnerForNewTask(settings.copy(companionUrl = "https://other.example.test"), session) }
        assertThrows(IllegalArgumentException::class.java) { politicalOwnerForNewTask(settings, session.copy(expiresAt = "2000-01-01T00:00:00Z")) }
        assertNull(politicalOwnerForNewTask(settings.copy(mode = "byok", apiKey = "byok-key"), null))
    }

    @Test fun explicitResumeRebindsOnlyKnownOwnerWithoutChangingHistoricalRequest() {
        val inFlight = task.copy(batches = listOf(batch.copy(started = true, remoteJobId = "original-job", remoteStatus = "running", attempt = 2)))
        val newLogin = session.copy(token = "new-owner-a-token")
        assertEquals("new-owner-a-token", politicalResumeCredential(inFlight, newLogin, "old-owner-a-token"))
        val resumed = inFlight.forExplicitResume(false)
        assertEquals(inFlight.id, resumed.id)
        assertEquals(inFlight.settings, resumed.settings)
        assertEquals(inFlight.promptVersion, resumed.promptVersion)
        assertEquals(inFlight.batches, resumed.batches)
        assertEquals("owner-a", resumed.ownerUserId)
    }

    @Test fun anotherAccountCannotBorrowExistingTaskCredentialOrRestoreItsMissingSecret() {
        val other = session.copy(user = session.user.copy(id = "owner-b", username = "user_b"))
        for (secret in listOf("old-owner-a-token", "")) {
            assertThrows(IllegalArgumentException::class.java) { politicalResumeCredential(task, other, secret) }
            assertThrows(IllegalArgumentException::class.java) { politicalResumeCredential(task, null, secret) }
            assertThrows(IllegalArgumentException::class.java) {
                politicalResumeCredential(task, session.copy(serviceUrl = "https://other.example.test"), secret)
            }
        }
    }

    @Test fun legacyUnknownRecoveryUsesOriginalIdentityAndCannotAuthorizeFreshAttempts() {
        val legacy = task.copy(ownerUserId = null, batches = listOf(batch.copy(started = true, attempt = 3)))
        assertEquals("original-legacy-token", politicalResumeCredential(legacy, session, "original-legacy-token"))
        requirePoliticalSubmission(legacy, legacy.batches.single(), settings.copy(trialToken = "original-legacy-token"), null)
        val resumed = legacy.forExplicitResume(false)
        assertEquals(legacy.batches, resumed.batches)
        assertEquals(legacy.id, resumed.id)
        assertThrows(IllegalArgumentException::class.java) { politicalResumeCredential(legacy, session, "") }
        for (pending in listOf(batch, batch.copy(started = true, remoteStatus = "cancelled"),
            batch.copy(started = true, remoteStatus = "uncertain"))) {
            val value = legacy.copy(batches = listOf(pending))
            assertThrows(IllegalArgumentException::class.java) { politicalResumeCredential(value, session, "original-legacy-token") }
            assertThrows(IllegalArgumentException::class.java) {
                requirePoliticalSubmission(value, pending, settings.copy(trialToken = "original-legacy-token"), session)
            }
        }
    }

    @Test fun eachAccountSubmissionChecksCurrentOwnerAndTokenAgain() {
        requirePoliticalSubmission(task, batch, settings, session)
        for (current in listOf(null, session.copy(token = "rotated-token"),
            session.copy(user = session.user.copy(id = "owner-b")), session.copy(expiresAt = "2000-01-01T00:00:00Z"))) {
            assertThrows(IllegalArgumentException::class.java) { requirePoliticalSubmission(task, batch, settings, current) }
        }
        // Even an unknown POST of a registered account cannot be sent after logout.
        assertThrows(IllegalArgumentException::class.java) {
            requirePoliticalSubmission(task, batch.copy(started = true), settings, null)
        }
    }

    @Test fun readingAndCancellationCanUseSameOwnerLoginWithoutBorrowingAnotherOwner() {
        val current = session.copy(token = "same-owner-new-token")
        assertEquals(current.token, politicalReadCredential(task, current, "original-token"))
        assertEquals(current.token, politicalReadCredential(task, current, ""))
        val other = current.copy(user = current.user.copy(id = "owner-b"))
        assertEquals("original-token", politicalReadCredential(task, other, "original-token"))
        assertThrows(IllegalArgumentException::class.java) { politicalReadCredential(task, other, "") }
        assertEquals("original-token", politicalReadCredential(task.copy(ownerUserId = null), current, "original-token"))
    }

    @Test fun logoutPausesOnlyAccountChecksAndPreservesAllRecoveryEvidence() {
        val running = task.copy(status = "running", batches = listOf(batch.copy(started = true, attempt = 2)))
        val paused = running.afterAccountLogout()
        assertEquals("paused", paused.status)
        assertEquals(running.batches, paused.batches)
        assertEquals(running.settings, paused.settings)
        assertEquals(running.ownerUserId, paused.ownerUserId)
        assertEquals(running.id, paused.id)
        assertTrue(paused.hasUnknownRequest)
        val byok = running.copy(settings = running.settings.copy(mode = "byok"), ownerUserId = null)
        assertEquals(byok, byok.afterAccountLogout())
        val completed = running.copy(status = "completed")
        assertEquals(completed, completed.afterAccountLogout())
    }

    @Test fun ownerIsDurableAndLegacyMissingFieldNeverInheritsCurrentLogin() {
        val encoded = appJson.encodeToString(task)
        val restored = appJson.decodeFromString<PoliticalExportTask>(encoded)
        assertEquals("owner-a", restored.ownerUserId)
        validatePoliticalTask(restored)
        val legacy = appJson.decodeFromString<PoliticalExportTask>(encoded.replace(",\"ownerUserId\":\"owner-a\"", ""))
        assertNull(legacy.ownerUserId)
        validatePoliticalTask(legacy)
        assertThrows(IllegalArgumentException::class.java) { validatePoliticalTask(task.copy(ownerUserId = "")) }
        assertThrows(IllegalArgumentException::class.java) {
            validatePoliticalTask(task.copy(settings = task.settings.copy(mode = "byok")))
        }
    }
}
