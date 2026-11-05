package com.jodae.docs199.domain.conversation

import com.jodae.docs199.domain.document.DocumentType
import com.jodae.docs199.domain.document.SourceKind
import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.error.ErrorCode
import com.jodae.docs199.global.security.AccessPolicy
import com.jodae.docs199.global.security.AccessScope
import com.jodae.docs199.global.security.UserPrincipal
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import java.sql.ResultSet
import java.util.UUID

private fun ResultSet.getNullableInt(column: String): Int? {
    val value = getInt(column)
    return if (wasNull()) null else value
}

data class EvidenceSourceView(
    val id: String,
    val text: String,
    val source: String,
    val documentId: UUID,
    val documentTitle: String,
    val versionId: UUID,
    val charStart: Int?,
    val charEnd: Int?,
)

data class QuestionResponse(
    val conversationId: UUID,
    val answer: String,
    val route: String?,
    val contextProbability: Double?,
    val sources: List<EvidenceSourceView>,
    val evidence: List<QuestionEvidenceView>,
    val failedSources: List<String>,
    val fallbackReason: String?,
)

data class QuestionEvidenceView(
    val chunkId: String,
    val source: String,
    val documentId: UUID,
    val versionId: UUID,
    val quote: String,
    val charStart: Int?,
    val charEnd: Int?,
)

private data class VerifiedChunk(
    val chunkId: UUID,
    val content: String,
    val charStart: Int?,
    val charEnd: Int?,
    val versionId: UUID,
    val documentId: UUID,
    val documentTitle: String,
)

@Service
class QuestionService(
    private val conversations: ConversationService,
    private val conversationRepository: ConversationRepository,
    private val jdbc: JdbcTemplate,
    private val policy: AccessPolicy,
    private val client: AgentChatClient,
    @Value("\${app.documents.allowed-extensions:pdf,md}") allowedExtensions: String = "pdf,md",
) {
    private val allowedExtensions =
        allowedExtensions
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun ask(
        principal: UserPrincipal,
        conversationId: UUID?,
        message: String,
        filters: SearchFilters? = null,
    ): QuestionResponse {
        validateFilters(filters)
        val conversation =
            if (conversationId == null) {
                conversations.create(principal, message.take(80))
            } else {
                val existing = conversationRepository.conversation(conversationId) ?: throw ApiException(ErrorCode.NOT_FOUND)
                if (existing.userId != principal.userId) throw ApiException(ErrorCode.NOT_FOUND)
                existing
            }
        conversations.addMessage(principal, conversation.id, MessageRole.USER, message)
        val scope = policy.scope(principal)
        val accessScope = AgentAccessScope(scope.departmentId, scope.position.name, scope.clearance.name, scope.isAdmin)
        // AI 호출은 DB 트랜잭션 밖에서 수행한다(최대 70초, 위 두 저장은 이미 각각 커밋됨).
        val response = client.chat(AgentChatRequest(message, conversation.id.toString(), filters, accessScope))
        val visible = visibleSources(principal, response.sources)
        val evidence = visibleEvidence(principal, response.evidence)
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
            evidence,
            response.failedSources,
            response.fallbackReason,
        )
    }

    private fun validateFilters(filters: SearchFilters?) {
        if (filters == null) return

        fun valid(
            values: List<String>?,
            allowed: Set<String>,
        ) = values == null || (values.isNotEmpty() && values.all { it in allowed })
        if (!valid(filters.extensions, allowedExtensions) ||
            !valid(filters.documentTypes, DocumentType.entries.map { it.name }.toSet()) ||
            !valid(filters.sourceKinds, SourceKind.entries.map { it.name }.toSet()) ||
            (filters.authoredFrom != null && filters.authoredTo != null && filters.authoredFrom > filters.authoredTo)
        ) {
            throw ApiException(ErrorCode.INVALID_INPUT)
        }
    }

    // AI 응답의 documentId/text는 네트워크 경계를 넘어온 값이라 신뢰하지 않는다. source.id(청크 ID)만
    // 모아 DB의 실제 chunks·document_versions·documents 조합과 accessible_document_versions 권한
    // 검사를 한 번의 배치 쿼리로 함께 확인하고, 본문·문서 정보는 전부 DB에서 다시 읽은 값으로 채운다.
    private fun visibleSources(
        principal: UserPrincipal,
        sources: List<AgentSource>,
    ): List<EvidenceSourceView> {
        val scope = policy.scope(principal)
        val parsedIds = sources.map { runCatching { UUID.fromString(it.id) }.getOrNull() }
        val verified = fetchVerifiedChunks(scope, parsedIds.filterNotNull().distinct())
        return parsedIds.mapNotNull { chunkId ->
            val chunk = chunkId?.let { verified[it] } ?: return@mapNotNull null
            EvidenceSourceView(
                chunk.chunkId.toString(),
                chunk.content,
                chunk.documentTitle,
                chunk.documentId,
                chunk.documentTitle,
                chunk.versionId,
                chunk.charStart,
                chunk.charEnd,
            )
        }
    }

    private fun fetchVerifiedChunks(
        scope: AccessScope,
        chunkIds: List<UUID>,
    ): Map<UUID, VerifiedChunk> {
        if (chunkIds.isEmpty()) return emptyMap()
        val rows =
            jdbc.query(
                """
                SELECT c.id AS chunk_id, c.content, c.char_start, c.char_end,
                       dv.id AS version_id, d.id AS document_id, d.title
                FROM chunks c
                JOIN document_versions dv ON dv.id = c.document_version_id
                JOIN documents d ON d.id = dv.document_id
                JOIN accessible_document_versions(?, ?, ?, ?) av ON av.document_version_id = c.document_version_id
                WHERE c.id = ANY(?)
                """,
                PreparedStatementSetter { ps ->
                    ps.setObject(1, scope.departmentId)
                    ps.setString(2, scope.position.name)
                    ps.setString(3, scope.clearance.name)
                    ps.setBoolean(4, scope.isAdmin)
                    ps.setArray(5, ps.connection.createArrayOf("uuid", chunkIds.toTypedArray()))
                },
                RowMapper { rs, _ ->
                    VerifiedChunk(
                        rs.getObject("chunk_id", UUID::class.java),
                        rs.getString("content"),
                        rs.getNullableInt("char_start"),
                        rs.getNullableInt("char_end"),
                        rs.getObject("version_id", UUID::class.java),
                        rs.getObject("document_id", UUID::class.java),
                        rs.getString("title"),
                    )
                },
            )
        return rows.associateBy { it.chunkId }
    }

    private fun visibleEvidence(
        principal: UserPrincipal,
        evidence: List<AgentEvidence>,
    ): List<QuestionEvidenceView> {
        val scope = policy.scope(principal)
        val parsedIds = evidence.map { runCatching { UUID.fromString(it.chunkId) }.getOrNull() }
        val verified = fetchVerifiedChunks(scope, parsedIds.filterNotNull().distinct())
        return evidence
            .mapNotNull { item ->
                val chunkId = runCatching { UUID.fromString(item.chunkId) }.getOrNull() ?: return@mapNotNull null
                val chunk = verified[chunkId] ?: return@mapNotNull null
                val claimedDocumentId = item.documentId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                val claimedVersionId = item.versionId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if ((item.documentId != null && claimedDocumentId != chunk.documentId) ||
                    (item.versionId != null && claimedVersionId != chunk.versionId)
                ) {
                    return@mapNotNull null
                }
                if (item.quote.isEmpty() || !chunk.content.contains(item.quote)) return@mapNotNull null

                val offsets = verifiedQuoteOffsets(chunk, item)
                QuestionEvidenceView(
                    chunkId = chunk.chunkId.toString(),
                    source = chunk.documentTitle,
                    documentId = chunk.documentId,
                    versionId = chunk.versionId,
                    quote = item.quote,
                    charStart = offsets?.first,
                    charEnd = offsets?.second,
                )
            }.take(3)
    }

    private fun verifiedQuoteOffsets(
        chunk: VerifiedChunk,
        evidence: AgentEvidence,
    ): Pair<Int, Int>? {
        val documentChunkStart = chunk.charStart ?: return null
        val quoteLength = evidence.quote.codePointCount(0, evidence.quote.length)
        val chunkLength = chunk.content.codePointCount(0, chunk.content.length)
        val requestedStart = evidence.charStart
        val requestedEnd = evidence.charEnd
        if (requestedStart != null && requestedEnd != null) {
            val localStart = requestedStart.toLong() - documentChunkStart
            val localEnd = requestedEnd.toLong() - documentChunkStart
            if (documentChunkStart >= 0 &&
                localStart >= 0 &&
                localEnd >= localStart &&
                localEnd <= chunkLength &&
                localEnd - localStart == quoteLength.toLong() &&
                chunk.substringByCodePoints(localStart, localEnd) == evidence.quote
            ) {
                return requestedStart to requestedEnd
            }
        }

        val firstUtf16Offset = chunk.content.indexOf(evidence.quote)
        if (firstUtf16Offset < 0 || chunk.content.indexOf(evidence.quote, firstUtf16Offset + 1) >= 0) return null
        val localStart = chunk.content.codePointCount(0, firstUtf16Offset)
        val start = documentChunkStart.toLong() + localStart
        val end = start + quoteLength
        if (start !in 0..Int.MAX_VALUE.toLong() || end > Int.MAX_VALUE) return null
        return start.toInt() to end.toInt()
    }

    private fun VerifiedChunk.substringByCodePoints(
        start: Long,
        end: Long,
    ): String? {
        val count = content.codePointCount(0, content.length)
        if (start < 0 || end < start || end > count) return null
        val startOffset = content.offsetByCodePoints(0, start.toInt())
        val endOffset = content.offsetByCodePoints(0, end.toInt())
        return content.substring(startOffset, endOffset)
    }
}
