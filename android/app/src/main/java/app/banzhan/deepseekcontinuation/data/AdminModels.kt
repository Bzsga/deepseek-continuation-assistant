package app.banzhan.deepseekcontinuation.data

import kotlinx.serialization.Serializable

@Serializable
data class DeveloperPrefs(
    val serviceUrl: String = "https://digimonlegend.com/continuation-api",
    val sessionToken: String = "",
    val expiresAt: String = "",
) {
    override fun toString(): String = "DeveloperPrefs(serviceUrl=$serviceUrl, sessionToken=[hidden], expiresAt=$expiresAt)"
}

data class AdminConfig(
    val publicEnabled: Boolean,
    val dailyLimit: Int,
    val userDailyLimit: Int,
    val model: String,
    val baseUrl: String,
    val apiKeyConfigured: Boolean,
    val contextTokens: Int,
    val maxOutputTokens: Int,
    val providerTimeoutSeconds: Double,
    val apiEnabled: Boolean,
    val mockMode: Boolean,
    val costKnown: Boolean,
    val configVersion: Int,
    val publicRegistrationLimit: Int = 20,
    val publicRegisteredCount: Int = 0,
)

data class AdminTotals(
    val users: Long, val newUsers: Long, val activeUsers: Long, val requests: Long,
    val inputTokens: Long, val outputTokens: Long,
    val completed: Long, val failed: Long, val uncertain: Long, val cancelled: Long,
    val queued: Long, val running: Long,
    val usageKnownRequests: Long? = null,
    val usageUnknownRequests: Long? = null,
)

data class AdminTodayQuota(
    val dailyLimit: Int, val dailyUsed: Int, val dailyReserved: Int, val dailyRemaining: Int,
    val resetAt: String,
)

data class AdminDailyUsage(
    val date: String, val newUsers: Long, val activeUsers: Long, val requests: Long,
    val inputTokens: Long, val outputTokens: Long,
    val completed: Long, val failed: Long, val uncertain: Long, val cancelled: Long,
    val usageKnownRequests: Long? = null,
    val usageUnknownRequests: Long? = null,
)

data class AdminStats(
    val timezone: String, val days: Int, val startDate: String, val endDate: String,
    val generatedAt: String, val totals: AdminTotals, val today: AdminTodayQuota,
    val daily: List<AdminDailyUsage>,
)

data class UserUsage(
    val id: String, val accessMode: String, val createdAt: String, val lastSeenAt: String?,
    val dailyLimit: Int, val dailyUsed: Int, val dailyReserved: Int, val dailyRemaining: Int,
    val totalRequests: Long, val completed: Long, val failed: Long, val uncertain: Long,
    val cancelled: Long, val inputTokens: Long, val outputTokens: Long,
    val usageKnownRequests: Long? = null,
    val usageUnknownRequests: Long? = null,
    val username: String? = null,
    val registrationKind: String? = null,
    val dailyLimitOverride: Int? = null,
)

data class AdminUsersPage(val limit: Int, val offset: Int, val total: Long, val users: List<UserUsage>,
    val day: String? = null, val resetAt: String? = null)

data class AdminRegistrationInvite(
    val id: String,
    val code: String? = null,
    val expiresAt: String,
    val createdAt: String = "",
    val usedAt: String? = null,
    val usedBy: String? = null,
)
