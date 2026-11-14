package app.banzhan.deepseekcontinuation.data

import java.time.OffsetDateTime
import kotlinx.serialization.Serializable

@Serializable
data class AccountUser(
    val id: String,
    val username: String,
    val registrationKind: String,
    val createdAt: String,
)

/** Stored only in the device's encrypted credentials, never in service settings or backups. */
@Serializable
data class AccountSession(
    val serviceUrl: String,
    val token: String,
    val expiresAt: String,
    val user: AccountUser,
) {
    override fun toString(): String =
        "AccountSession(serviceUrl=$serviceUrl, token=[hidden], expiresAt=$expiresAt, user=$user)"
}

data class AccountState(
    val currentUser: AccountUser? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

data class AccountAuthentication(val session: AccountSession, val quota: CompanionQuota)

data class AccountProfile(val user: AccountUser, val quota: CompanionQuota)

internal fun accountSessionExpired(session: AccountSession, nowMillis: Long = System.currentTimeMillis()): Boolean =
    session.token.isBlank() || runCatching {
        OffsetDateTime.parse(session.expiresAt).toInstant().toEpochMilli() <= nowMillis
    }.getOrDefault(true)
