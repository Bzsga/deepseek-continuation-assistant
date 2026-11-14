package app.banzhan.deepseekcontinuation.data

import java.time.LocalDate
import java.time.OffsetDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Developer credentials are independent of BYOK and free-session credentials. */
object AdminNetwork {
    private val contentType = "application/json; charset=utf-8".toMediaType()

    suspend fun loginDeveloper(serviceUrl: String, password: String): DeveloperPrefs {
        require(password.isNotEmpty()) { "请输入开发者密码" }
        val endpoint = SummaryNetwork.companionEndpoint(serviceUrl, "admin", "login")
        val body = buildJsonObject { put("password", password) }.toString()
        val request = Request.Builder().url(endpoint).post(body.toRequestBody(contentType)).build()
        val payload = SummaryNetwork.exchange(request, mutation = true, secrets = listOf(password))
        return parseResponse { parseDeveloperLogin(serviceUrl, payload) }
    }

    suspend fun getAdminConfig(prefs: DeveloperPrefs): AdminConfig {
        val request = authenticated(prefs, "config").get().build()
        val payload = SummaryNetwork.exchange(request, mutation = false, secrets = listOf(prefs.sessionToken))
        return parseResponse { parseAdminConfig(payload) }
    }

    suspend fun updateAdminConfig(prefs: DeveloperPrefs, config: AdminConfig, upstreamApiKey: String? = null): AdminConfig {
        val body = adminPatchPayload(config, upstreamApiKey)
        val request = authenticated(prefs, "config").patch(body.toRequestBody(contentType)).build()
        val payload = SummaryNetwork.exchange(request, mutation = true,
            secrets = listOfNotNull(prefs.sessionToken, upstreamApiKey))
        return parseResponse { parseAdminConfig(payload) }
    }

    suspend fun getStats(prefs: DeveloperPrefs, days: Int = 7): AdminStats {
        require(days in 1..90) { "统计天数须在 1 到 90 之间" }
        val url = SummaryNetwork.companionEndpoint(prefs.serviceUrl, "admin", "stats").newBuilder()
            .addQueryParameter("days", days.toString()).build()
        val request = authenticated(prefs, "stats").url(url).get().build()
        val payload = SummaryNetwork.exchange(request, mutation = false, secrets = listOf(prefs.sessionToken))
        return parseResponse { parseAdminStats(payload) }
    }

    suspend fun getUsers(prefs: DeveloperPrefs, limit: Int = 50, offset: Int = 0): AdminUsersPage {
        require(limit in 1..200 && offset >= 0) { "用户列表分页参数无效" }
        val url = SummaryNetwork.companionEndpoint(prefs.serviceUrl, "admin", "users").newBuilder()
            .addQueryParameter("limit", limit.toString()).addQueryParameter("offset", offset.toString()).build()
        val request = authenticated(prefs, "users").url(url).get().build()
        val payload = SummaryNetwork.exchange(request, mutation = false, secrets = listOf(prefs.sessionToken))
        return parseResponse { parseAdminUsers(payload) }
    }

    internal fun parseDeveloperLogin(serviceUrl: String, payload: String): DeveloperPrefs {
        val root = objectResponse(payload)
        val token = root.text("token")
        require(token.isNotEmpty() && token.length <= 8192 && token.all { it.code in 33..126 }) { "开发者登录返回无效令牌" }
        val expiresAt = root.text("expires_at")
        OffsetDateTime.parse(expiresAt)
        return DeveloperPrefs(SummaryNetwork.checkedBase(serviceUrl).toString().trimEnd('/'), token, expiresAt)
    }

    internal fun parseAdminConfig(payload: String): AdminConfig {
        val root = objectResponse(payload)
        val config = AdminConfig(
            publicEnabled = root.boolean("public_enabled"),
            dailyLimit = root.integer("daily_limit"),
            userDailyLimit = root.integer("user_daily_limit"),
            model = root.text("model"),
            baseUrl = root.text("base_url"),
            apiKeyConfigured = root.boolean("api_key_configured"),
            contextTokens = root.integer("context_tokens"),
            maxOutputTokens = root.integer("max_output_tokens"),
            providerTimeoutSeconds = root.number("provider_timeout_seconds"),
            apiEnabled = root.boolean("api_enabled"),
            mockMode = root.boolean("mock_mode"),
            costKnown = root.boolean("cost_known"),
            configVersion = root.integer("config_version"),
            publicRegistrationLimit = if ("public_registration_limit" in root) root.nonnegativeInteger("public_registration_limit") else 20,
            publicRegisteredCount = if ("public_registered_count" in root) root.nonnegativeInteger("public_registered_count") else 0,
        )
        validateConfig(config)
        require(config.configVersion >= 0) { "开发者配置版本无效" }
        return config
    }

    internal fun adminPatchPayload(config: AdminConfig, upstreamApiKey: String?): String {
        validateConfig(config)
        require(upstreamApiKey == null || upstreamApiKey.length <= 4096) { "上游密钥长度超过限制" }
        return buildJsonObject {
            put("public_enabled", config.publicEnabled)
            put("daily_limit", config.dailyLimit)
            put("user_daily_limit", config.userDailyLimit)
            put("public_registration_limit", config.publicRegistrationLimit)
            put("model", config.model)
            put("base_url", config.baseUrl)
            put("context_tokens", config.contextTokens)
            put("max_output_tokens", config.maxOutputTokens)
            put("provider_timeout_seconds", config.providerTimeoutSeconds)
            if (upstreamApiKey != null) put("api_key", upstreamApiKey)
        }.toString()
    }

    internal fun parseAdminStats(payload: String): AdminStats {
        val root = objectResponse(payload)
        val totals = root["totals"] as? JsonObject ?: error("统计响应缺少 totals")
        val today = root["today"] as? JsonObject ?: error("统计响应缺少 today")
        val values = root["daily"] as? JsonArray ?: error("统计响应缺少 daily")
        val days = root.integer("days")
        require(days in 1..90 && values.size <= days) { "统计响应天数无效" }
        val daily = values.map { value ->
            val day = value as? JsonObject ?: error("每日统计格式无效")
            AdminDailyUsage(day.date("date"), day.count("new_users"), day.count("active_users"),
                day.count("requests"), day.count("input_tokens"), day.count("output_tokens"),
                day.count("completed"), day.count("failed"), day.count("uncertain"), day.count("cancelled"),
                usageKnownRequests = day.optionalCount("usage_known_requests"),
                usageUnknownRequests = day.optionalCount("usage_unknown_requests"))
        }
        return AdminStats(
            timezone = root.text("timezone"), days = days, startDate = root.date("start_date"),
            endDate = root.date("end_date"), generatedAt = root.timestamp("generated_at"),
            totals = AdminTotals(totals.count("users"), totals.count("new_users"), totals.count("active_users"),
                totals.count("requests"), totals.count("input_tokens"), totals.count("output_tokens"),
                totals.count("completed"), totals.count("failed"), totals.count("uncertain"),
                totals.count("cancelled"), totals.count("queued"), totals.count("running"),
                usageKnownRequests = totals.optionalCount("usage_known_requests"),
                usageUnknownRequests = totals.optionalCount("usage_unknown_requests")),
            today = AdminTodayQuota(today.nonnegativeInteger("daily_limit"), today.nonnegativeInteger("daily_used"),
                today.nonnegativeInteger("daily_reserved"), today.nonnegativeInteger("daily_remaining"), today.timestamp("reset_at")),
            daily = daily
        )
    }

    internal fun parseAdminUsers(payload: String): AdminUsersPage {
        val root = objectResponse(payload)
        val limit = root.integer("limit")
        val offset = root.nonnegativeInteger("offset")
        val values = root["users"] as? JsonArray ?: error("用户响应缺少 users")
        require(limit in 1..200 && values.size <= limit) { "用户响应分页无效" }
        val users = values.map { value ->
            val user = value as? JsonObject ?: error("用户统计格式无效")
            val accessMode = user.text("access_mode")
            require(accessMode in setOf("public", "invitation")) { "用户访问方式无效" }
            val lastSeenAt = if (user["last_seen_at"] == JsonNull) null else user.timestamp("last_seen_at")
            UserUsage(user.text("id"), accessMode, user.timestamp("created_at"), lastSeenAt,
                user.nonnegativeInteger("daily_limit"), user.nonnegativeInteger("daily_used"),
                user.nonnegativeInteger("daily_reserved"), user.nonnegativeInteger("daily_remaining"),
                user.count("total_requests"), user.count("completed"), user.count("failed"),
                user.count("uncertain"), user.count("cancelled"), user.count("input_tokens"), user.count("output_tokens"),
                usageKnownRequests = user.optionalCount("usage_known_requests"),
                usageUnknownRequests = user.optionalCount("usage_unknown_requests"),
                username = user.optionalText("username"),
                registrationKind = user.optionalText("registration_kind")?.also {
                    require(it in setOf("public", "invitation")) { "注册来源无效" }
                },
                dailyLimitOverride = if (user["daily_limit_override"] == null || user["daily_limit_override"] == JsonNull) null
                    else user.nonnegativeInteger("daily_limit_override"))
        }
        return AdminUsersPage(limit, offset, root.count("total"), users,
            day = root.optionalText("day")?.also { LocalDate.parse(it) },
            resetAt = root.optionalText("reset_at")?.also { OffsetDateTime.parse(it) })
    }

    suspend fun getRegistrationInvites(prefs: DeveloperPrefs): List<AdminRegistrationInvite> {
        return collectRegistrationInvites { limit, offset ->
            val url = SummaryNetwork.companionEndpoint(prefs.serviceUrl, "admin", "registration-invites").newBuilder()
                .addQueryParameter("limit", limit.toString()).addQueryParameter("offset", offset.toString()).build()
            SummaryNetwork.exchange(SummaryNetwork.authenticated(url, prefs.sessionToken).get().build(),
                mutation = false, secrets = listOf(prefs.sessionToken))
        }
    }

    internal suspend fun collectRegistrationInvites(
        fetchPage: suspend (limit: Int, offset: Int) -> String,
    ): List<AdminRegistrationInvite> {
        val limit = 100
        var offset = 0
        var expectedTotal: Int? = null
        val invitations = mutableListOf<AdminRegistrationInvite>()
        val ids = mutableSetOf<String>()
        while (true) {
            val payload = fetchPage(limit, offset)
            val page = parseResponse {
                val root = objectResponse(payload)
                val total = root.count("total")
                require(total <= Int.MAX_VALUE) { "邀请码总数超出本机列表范围" }
                RegistrationInvitesPage(root.integer("limit"), root.nonnegativeInteger("offset"), total.toInt(),
                    parseRegistrationInvites(payload))
            }
            if (page.limit != limit || page.offset != offset) {
                throw SummaryFailure("邀请码服务的分页参数不一致，请更新服务或刷新后重试", ambiguous = false)
            }
            if (expectedTotal != null && page.total != expectedTotal) {
                throw SummaryFailure("邀请码列表已变化，请刷新后重试", ambiguous = false)
            }
            expectedTotal = page.total
            if (page.total < offset || page.invitations.size != minOf(limit, page.total - offset)) {
                throw SummaryFailure("邀请码列表未完整返回，请刷新后重试", ambiguous = false)
            }
            if (page.invitations.any { it.code.isNullOrBlank() || it.usedAt != null || !ids.add(it.id) }) {
                throw SummaryFailure("邀请码列表含重复或不可分发记录，请刷新后重试", ambiguous = false)
            }
            invitations.addAll(page.invitations)
            offset += page.invitations.size
            if (offset == expectedTotal) return invitations
        }
    }

    private data class RegistrationInvitesPage(val limit: Int, val offset: Int, val total: Int,
        val invitations: List<AdminRegistrationInvite>)

    suspend fun createRegistrationInvites(prefs: DeveloperPrefs, count: Int, validDays: Int): List<AdminRegistrationInvite> {
        require(count in 1..100 && validDays in 1..365) { "邀请码数量须为1–100，有效期须为1–365天。" }
        val body = buildJsonObject { put("count", count); put("valid_days", validDays) }.toString()
        val payload = SummaryNetwork.exchange(authenticated(prefs, "registration-invites")
            .post(body.toRequestBody(contentType)).build(), mutation = true, secrets = listOf(prefs.sessionToken))
        return parseResponse { parseRegistrationInvites(payload) }
    }

    suspend fun updateUserQuota(prefs: DeveloperPrefs, userId: String, dailyLimit: Int?,
        todayRemaining: Int? = null, day: String? = null) {
        require(userId.isNotBlank() && userId.length <= 256) { "用户编号无效" }
        val body = userQuotaPayload(dailyLimit, todayRemaining, day)
        SummaryNetwork.exchange(authenticated(prefs, "users", userId, "quota")
            .patch(body.toRequestBody(contentType)).build(), mutation = true, secrets = listOf(prefs.sessionToken))
    }

    internal fun userQuotaPayload(dailyLimit: Int?, todayRemaining: Int?, day: String?): String {
        require(dailyLimit == null || dailyLimit in 0..1_000_000) { "个人每日次数须在0到1000000之间。" }
        require(todayRemaining == null || todayRemaining in 0..1_000_000) { "今日剩余次数须在0到1000000之间。" }
        if (day != null) LocalDate.parse(day)
        return buildJsonObject {
            if (dailyLimit == null) put("daily_limit", JsonNull) else put("daily_limit", dailyLimit)
            if (todayRemaining != null) {
                put("today_remaining", todayRemaining)
                if (day != null) put("day", day)
            }
        }.toString()
    }

    internal fun parseRegistrationInvites(payload: String): List<AdminRegistrationInvite> {
        val root = objectResponse(payload)
        val values = root["invitations"] as? JsonArray ?: error("邀请码响应缺少列表")
        require(values.size <= 200) { "邀请码列表超过限制" }
        return values.map { element ->
            val value = element as? JsonObject ?: error("邀请码格式无效")
            AdminRegistrationInvite(value.text("id").also { require(it.isNotBlank() && it.length <= 256) },
                code = value.optionalText("code")?.also { require(it.isNotBlank() && it.length <= 256) },
                expiresAt = value.timestamp("expires_at"),
                createdAt = value.optionalText("created_at")?.also { OffsetDateTime.parse(it) }.orEmpty(),
                usedAt = value.optionalText("redeemed_at")?.also { OffsetDateTime.parse(it) },
                usedBy = value.optionalText("redeemed_by"))
        }
    }

    private fun validateConfig(config: AdminConfig) {
        require(config.dailyLimit in 0..1_000_000 && config.userDailyLimit in 0..1_000_000) { "每日次数须在 0 到 1000000 之间" }
        require(config.publicRegistrationLimit in 0..1_000_000) { "公开注册人数上限须在0到1000000之间。" }
        require(config.model.isNotBlank() && config.model.length <= 128) { "请输入有效模型名称" }
        require(config.baseUrl.length <= 2048) { "上游地址过长" }
        SummaryNetwork.checkedBase(config.baseUrl)
        require(config.contextTokens in 512..262_144 && config.maxOutputTokens in 256..8192 &&
            config.contextTokens > config.maxOutputTokens) { "上下文与输出 token 预算无效" }
        require(config.providerTimeoutSeconds.isFinite() && config.providerTimeoutSeconds in 1.0..300.0) {
            "上游超时须在 1 到 300 秒之间"
        }
    }

    private fun authenticated(prefs: DeveloperPrefs, vararg path: String): Request.Builder {
        require(prefs.sessionToken.isNotBlank()) { "请先登录开发者设置" }
        return SummaryNetwork.authenticated(SummaryNetwork.companionEndpoint(prefs.serviceUrl, "admin", *path), prefs.sessionToken)
    }

    private fun <T> parseResponse(block: () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // Do not surface parser excerpts, which could contain an incorrectly echoed secret.
        throw SummaryFailure("开发者服务响应格式无效，请刷新后再试", ambiguous = false)
    }

    private fun objectResponse(payload: String): JsonObject =
        SummaryProtocol.json.parseToJsonElement(payload) as? JsonObject ?: error("开发者响应格式无效")

    private fun JsonObject.text(key: String): String {
        val value = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        require(value != null) { "开发者响应缺少 $key" }
        return value
    }

    private fun JsonObject.optionalText(key: String): String? =
        if (this[key] == null || this[key] == JsonNull) null else text(key)

    private fun JsonObject.boolean(key: String): Boolean {
        val value = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        require(value != null) { "开发者响应中的 $key 无效" }
        return value
    }

    private fun JsonObject.integer(key: String): Int {
        val value = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        require(value != null) { "开发者响应中的 $key 无效" }
        return value
    }

    private fun JsonObject.number(key: String): Double {
        val value = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
        require(value != null && value.isFinite()) { "开发者响应中的 $key 无效" }
        return value
    }

    private fun JsonObject.nonnegativeInteger(key: String): Int = integer(key).also {
        require(it >= 0) { "开发者响应中的 $key 不得为负数" }
    }

    private fun JsonObject.count(key: String): Long {
        val value = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        require(value != null && value >= 0) { "开发者响应中的 $key 无效" }
        return value
    }

    private fun JsonObject.optionalCount(key: String): Long? = if (key in this) count(key) else null

    private fun JsonObject.date(key: String): String = text(key).also { LocalDate.parse(it) }
    private fun JsonObject.timestamp(key: String): String = text(key).also { OffsetDateTime.parse(it) }
}
