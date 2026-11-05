package app.banzhan.deepseekcontinuation.data

import app.banzhan.deepseekcontinuation.core.ArchiveEntry
import app.banzhan.deepseekcontinuation.core.ChatMessage

/** A narrow boundary for deterministic task/recovery tests without any paid calls. */
internal interface OrganizationNetwork {
    suspend fun createSession(settings: ServiceSettings): CompanionSession
    suspend fun quota(settings: ServiceSettings): CompanionQuota
    suspend fun submit(settings: ServiceSettings, messages: List<ChatMessage>, key: String): RemoteJob
    suspend fun poll(settings: ServiceSettings, remoteId: String): RemoteJob
    suspend fun cancel(settings: ServiceSettings, remoteId: String): RemoteJob
    suspend fun summarize(settings: ServiceSettings, messages: List<ChatMessage>, promptVersion: String): List<ArchiveEntry>

    suspend fun submit(settings: ServiceSettings, messages: List<ChatMessage>, key: String,
        contextMessages: List<ChatMessage>): RemoteJob {
        require(contextMessages.isEmpty()) { "此整理连接不支持参考上下文" }
        return submit(settings, messages, key)
    }

    suspend fun summarize(settings: ServiceSettings, messages: List<ChatMessage>, promptVersion: String,
        contextMessages: List<ChatMessage>): List<ArchiveEntry> {
        require(contextMessages.isEmpty()) { "此整理连接不支持参考上下文" }
        return summarize(settings, messages, promptVersion)
    }
}

internal object LiveOrganizationNetwork : OrganizationNetwork {
    override suspend fun createSession(settings: ServiceSettings) = SummaryNetwork.createSession(settings)
    override suspend fun quota(settings: ServiceSettings) = SummaryNetwork.getQuota(settings)
    override suspend fun submit(settings: ServiceSettings, messages: List<ChatMessage>, key: String) = SummaryNetwork.submit(settings, messages, key)
    override suspend fun poll(settings: ServiceSettings, remoteId: String) = SummaryNetwork.poll(settings, remoteId)
    override suspend fun cancel(settings: ServiceSettings, remoteId: String) = SummaryNetwork.cancel(settings, remoteId)
    override suspend fun summarize(settings: ServiceSettings, messages: List<ChatMessage>, promptVersion: String) =
        SummaryNetwork.summarize(settings, messages, promptVersion)
    override suspend fun submit(settings: ServiceSettings, messages: List<ChatMessage>, key: String,
        contextMessages: List<ChatMessage>) = SummaryNetwork.submit(settings, messages, key, contextMessages)
    override suspend fun summarize(settings: ServiceSettings, messages: List<ChatMessage>, promptVersion: String,
        contextMessages: List<ChatMessage>) = SummaryNetwork.summarize(settings, messages, promptVersion, contextMessages)
}
