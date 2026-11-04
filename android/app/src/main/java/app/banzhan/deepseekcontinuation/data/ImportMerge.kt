package app.banzhan.deepseekcontinuation.data

import app.banzhan.deepseekcontinuation.core.ImportedConversation
import app.banzhan.deepseekcontinuation.core.ChatMessage
import kotlinx.serialization.builtins.ListSerializer

/** Keep archive source IDs valid when the same conversation is exported again. */
internal fun mergeImportedConversation(previous: ImportedConversation, incoming: ImportedConversation): ImportedConversation {
    require(previous.id == incoming.id)
    val oldMessages = previous.branches.asSequence().flatMap { it.messages }.associateBy { it.id }
    val changedSource = incoming.branches.asSequence().flatMap { it.messages }.any { message ->
        oldMessages[message.id]?.let { it != message } == true
    }
    if (changedSource) {
        val versionId = incoming.id + ":version:" + serializedHash(ImportedConversation.serializer(), incoming).take(16)
        val branchIds = incoming.branches.associate { it.id to "$versionId:branch:${hash(it.id).take(16)}" }
        val renamed = mutableMapOf<String, ChatMessage>()
        return incoming.copy(id = versionId, title = incoming.title + "（更新版本）",
            currentBranchId = incoming.currentBranchId?.let { branchIds.getValue(it) },
            branches = incoming.branches.map { branch ->
                branch.copy(id = branchIds.getValue(branch.id), messages = branch.messages.map { message ->
                    renamed.getOrPut(message.id) { message.copy(id = "$versionId:message:${hash(message.id).take(32)}") }
                })
            })
    }
    val incomingBranches = incoming.branches.associateBy { it.id }
    val retained = previous.branches.mapNotNull { old ->
        val latest = incomingBranches[old.id]
        when {
            latest == null -> old.copy(isCurrent = false)
            latest.messages == old.messages -> null
            else -> old.copy(id = old.id + ":history:" + serializedHash(ListSerializer(ChatMessage.serializer()), old.messages).take(16),
                label = old.label + "（较早导出）", isCurrent = false)
        }
    }
    return incoming.copy(branches = (incoming.branches + retained).distinctBy { it.id })
}
