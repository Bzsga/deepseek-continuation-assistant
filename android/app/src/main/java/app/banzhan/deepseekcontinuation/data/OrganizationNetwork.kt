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
}

internal object LiveOrganizationNetwork : OrganizationNetwork {
    override suspend fun createSession(settings: ServiceSettings) = SummaryNetwork.createSession(settings)
    override suspend fun quota(settings: ServiceSettings) = SummaryNetwork.getQuota(settings)
    override suspend fun submit(settings: ServiceSettings, messages: List<ChatMessage>, key: String) = SummaryNetwork.submit(settings, messages, key)
    override suspend fun poll(settings: ServiceSettings, remoteId: String) = SummaryNetwork.poll(settings, remoteId)
    override suspend fun cancel(settings: ServiceSettings, remoteId: String) = SummaryNetwork.cancel(settings, remoteId)
    override suspend fun summarize(settings: ServiceSettings, messages: List<ChatMessage>, promptVersion: String) =
        SummaryNetwork.summarize(settings, messages, promptVersion)
}
