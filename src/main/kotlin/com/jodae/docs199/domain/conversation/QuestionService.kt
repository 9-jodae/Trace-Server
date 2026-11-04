package com.jodae.docs199.domain.conversation

import com.jodae.docs199.domain.document.DocumentRepository
import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.error.ErrorCode
import com.jodae.docs199.global.security.AccessPolicy
import com.jodae.docs199.global.security.DocumentAccessRule
import com.jodae.docs199.global.security.UserPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class EvidenceSourceView(
    val id: String,
    val text: String,
    val source: String,
    val documentId: UUID,
    val documentTitle: String,
    val versionId: String?,
    val charStart: Int?,
    val charEnd: Int?,
)

data class QuestionResponse(
    val conversationId: UUID,
    val answer: String,
    val route: String?,
    val contextProbability: Double?,
    val sources: List<EvidenceSourceView>,
    val failedSources: List<String>,
    val fallbackReason: String?,
)

@Service
class QuestionService(
    private val conversations: ConversationService,
    private val conversationRepository: ConversationRepository,
    private val documents: DocumentRepository,
    private val policy: AccessPolicy,
    private val client: AgentChatClient,
) {
    @Transactional
    fun ask(
        principal: UserPrincipal,
        conversationId: UUID?,
        message: String,
    ): QuestionResponse {
        val conversation =
            if (conversationId == null) {
                conversations.create(principal, message.take(80))
            } else {
                val existing = conversationRepository.conversation(conversationId) ?: throw ApiException(ErrorCode.NOT_FOUND)
                if (existing.userId != principal.userId) throw ApiException(ErrorCode.NOT_FOUND)
                existing
            }
        conversations.addMessage(principal, conversation.id, MessageRole.USER, message)
        val response = client.chat(AgentChatRequest(message, conversation.id.toString()))
        val visible = response.sources.mapNotNull { visibleSource(principal, it) }
        conversations.addMessage(
            principal,
            conversation.id,
            MessageRole.ASSISTANT,
            response.answer,
            visible
                .map {
                    it.id.let(UUID::fromString)
                }.toSet(),
        )
        return QuestionResponse(
            conversation.id,
            response.answer,
            response.route,
            response.contextProbability,
            visible,
            response.failedSources,
            response.fallbackReason,
        )
    }

    private fun visibleSource(
        principal: UserPrincipal,
        source: AgentSource,
    ): EvidenceSourceView? {
        val chunkId = runCatching { UUID.fromString(source.id) }.getOrNull() ?: return null
        val documentId = source.documentId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        val document = documents.document(documentId) ?: return null
        val departments = documents.departments(documentId)
        if (!policy.canAccess(principal, DocumentAccessRule(document.clearance, departments, document.minimumPosition))) return null
        return EvidenceSourceView(
            chunkId.toString(),
            source.text,
            source.source,
            documentId,
            document.title,
            source.versionId,
            source.charStart,
            source.charEnd,
        )
    }
}
