package app.banzhan.deepseekcontinuation.core

import java.text.Normalizer
import java.util.Locale

/** Bounded lexical hints for selection only. A match is never evidence for a saved statement. */
internal object ContinuityFocus {
    private const val MAX_QUERY_CHARACTERS = 6000
    private const val MAX_TEXT_CHARACTERS = 12000
    private const val MAX_TERMS = 256

    class Query internal constructor(private val terms: Map<String, Int>) {
        val isEmpty: Boolean get() = terms.isEmpty()

        fun score(text: String): Int {
            if (terms.isEmpty()) return 0
            return lexicalTerms(text).sumOf { terms[it] ?: 0 }
        }
    }

    fun fromText(text: String): Query = query(listOf(text.takeLast(MAX_QUERY_CHARACTERS) to 1))

    /**
     * Only one exact, valid selected branch can supply the current topic. Multiple branches,
     * missing ranges and a protected endpoint fall back to the user's current written message.
     */
    fun forArchive(archive: Archive, conversations: List<ImportedConversation>): Query {
        if (!archive.continuity.enabled) return fromText("")
        val current = archive.continuity.nextMessage.takeLast(MAX_QUERY_CHARACTERS)
        val parts = mutableListOf(current to 4)
        if (!archive.continuity.includeRecent || archive.sourceScopes.isEmpty()) return query(parts)
        val scopes = archive.sourceScopes
        val key = scopes.map { it.conversationId to it.branchId }.distinct().singleOrNull() ?: return query(parts)
        if (runCatching {
                ContinuityLogic.validateSourceScopes(scopes, conversations, archive.conversationIds.toSet())
            }.isFailure) return query(parts)
        val conversation = conversations.singleOrNull { it.id == key.first } ?: return query(parts)
        val branch = conversation.branches.singleOrNull { it.id == key.second } ?: return query(parts)
        val selectedIds = scopes.flatMap { it.selectedMessageIds }.toSet()
        val selected = branch.messages.filter { it.id in selectedIds }
        val blocked = archive.entries.filter { it.edited || it.deleted }.flatMap { it.sourceIds }.toSet()
        if (selected.lastOrNull()?.id in blocked) return query(parts)
        val recent = selected.takeLast(6).filter { it.id !in blocked && it.role.lowercase(Locale.ROOT) in setOf("user", "assistant") }
        // Newer user wording has priority; assistant observations can add topic words but not facts.
        recent.asReversed().forEach { message ->
            parts += message.content.takeLast(2000) to if (message.role.equals("user", true)) 2 else 1
        }
        return query(parts)
    }

    private fun query(parts: List<Pair<String, Int>>): Query {
        val terms = linkedMapOf<String, Int>()
        parts.forEach { (text, weight) ->
            lexicalTerms(text).forEach { term ->
                if (term in terms || terms.size < MAX_TERMS) {
                    terms[term] = maxOf(terms[term] ?: 0, weight * if (term.length >= 3) 3 else 2)
                }
            }
        }
        return Query(terms)
    }

    private val tokens = Regex("[\\u4e00-\\u9fff]+|[a-z][a-z0-9_+#.-]{1,63}")
    private val shortTechnicalTerms = setOf("ui", "ai", "ip", "js", "go", "ap", "hp", "mp")
    private val stopWords = setOf(
        "the", "and", "that", "this", "with", "from", "have", "has", "had", "will", "would", "could",
        "should", "what", "when", "where", "which", "there", "their", "them", "then", "than", "some",
        "into", "about", "please", "continue", "talk", "chat", "said", "want", "need", "just", "very",
        "your", "you", "our", "are", "was", "were", "for", "not", "but", "can", "how", "let", "its",
        "我们", "你们", "他们", "她们", "自己", "这个", "那个", "这些", "那些", "一个", "一种",
        "一下", "一些", "一点", "这样", "那样", "什么", "怎么", "为什么", "如何", "因为", "所以",
        "但是", "不过", "而且", "或者", "如果", "虽然", "还是", "就是", "只是", "已经", "现在",
        "之前", "之后", "时候", "可以", "可能", "应该", "需要", "不要", "不是", "没有", "不能",
        "希望", "觉得", "感觉", "知道", "继续", "接着", "讨论", "问题", "事情", "建议", "回答",
        "说说", "聊天", "内容", "关于", "进行", "目前", "然后", "还有", "认为", "想要", "想想",
        "我想", "我说", "你说", "你看", "能否", "能不能", "是不是", "有没有", "能不", "不是很",
    )
    private val chineseFillers = stopWords.filter { it.first() in '\u4e00'..'\u9fff' }.sortedByDescending { it.length }
    private val chineseFillerPattern = Regex(chineseFillers.joinToString("|") { Regex.escape(it) })

    private fun lexicalTerms(text: String): Set<String> {
        val normalized = Normalizer.normalize(text.take(MAX_TEXT_CHARACTERS), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val result = linkedSetOf<String>()
        tokens.findAll(normalized).forEach { match ->
            val token = match.value.trimEnd('.', '-')
            if (token.first() in '\u4e00'..'\u9fff') {
                chineseFillerPattern.split(token).forEach { part ->
                    for (size in 2..3) {
                        if (part.length < size) continue
                        for (index in 0..part.length - size) {
                            val term = part.substring(index, index + size)
                            if (term !in stopWords) result += term
                        }
                    }
                }
            } else if (token !in stopWords && (token.length >= 3 || token in shortTechnicalTerms)) {
                result += token
            }
        }
        return result
    }
}
