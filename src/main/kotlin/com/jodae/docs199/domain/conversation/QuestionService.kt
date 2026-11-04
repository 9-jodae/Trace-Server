package com.jodae.docs199.domain.conversation

import com.jodae.docs199.domain.document.DocumentRecord
import com.jodae.docs199.domain.document.DocumentRepository
import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.error.ErrorCode
import com.jodae.docs199.global.security.AccessPolicy
import com.jodae.docs199.global.security.DocumentAccessRule
import com.jodae.docs199.global.security.UserPrincipal
import org.springframework.stereotype.Service
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
        // AI 호출은 DB 트랜잭션 밖에서 수행한다(최대 70초, 위 두 저장은 이미 각각 커밋됨).
        val response = client.chat(AgentChatRequest(message, conversation.id.toString()))
        val visible = visibleSources(principal, response.sources)
        conversations.addMessage(
            principal,
            conversation.id,
            MessageRole.ASSISTANT,
            response.answer,
            visible.map { UUID.fromString(it.id) }.toSet(),
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

    private fun visibleSources(
        principal: UserPrincipal,
        sources: List<AgentSource>,
    ): List<EvidenceSourceView> {
        val byChunkAndDocument =
            sources.mapNotNull { source ->
                val chunkId = runCatching { UUID.fromString(source.id) }.getOrNull() ?: return@mapNotNull null
                val documentId = source.documentId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return@mapNotNull null
                Triple(source, chunkId, documentId)
            }
        val documentIds = byChunkAndDocument.map { it.third }.toSet()
        val documentsById = documents.documents(documentIds)
        val departmentsById = documents.departmentsBulk(documentIds)
        return byChunkAndDocument.mapNotNull { (source, chunkId, documentId) ->
            val document = documentsById[documentId] ?: return@mapNotNull null
            val departments = departmentsById[documentId].orEmpty()
            if (!policy.canAccess(principal, DocumentAccessRule(document.clearance, departments, document.minimumPosition))) {
                return@mapNotNull null
            }
            toEvidenceView(source, chunkId, documentId, document)
        }
    }

    private fun toEvidenceView(
        source: AgentSource,
        chunkId: UUID,
        documentId: UUID,
        document: DocumentRecord,
    ) = EvidenceSourceView(
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
