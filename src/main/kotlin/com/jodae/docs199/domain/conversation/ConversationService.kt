package com.jodae.docs199.domain.conversation

import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.error.ErrorCode
import com.jodae.docs199.global.security.AccessPolicy
import com.jodae.docs199.global.security.AccessScope
import com.jodae.docs199.global.security.UserPrincipal
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class ConversationDetail(
    val conversation: ConversationRecord,
    val messages: List<ConversationMessageRecord>,
)

@Service
class ConversationService(
    private val repository: ConversationRepository,
    private val jdbc: JdbcTemplate,
    private val policy: AccessPolicy,
) {
    @Transactional
    fun create(
        principal: UserPrincipal,
        title: String,
    ): ConversationRecord {
        val now = Instant.now()
        val conversation = ConversationRecord(UUID.randomUUID(), principal.userId, title, now, now)
        repository.create(conversation)
        return conversation
    }

    fun listMine(principal: UserPrincipal): List<ConversationRecord> = repository.forUser(principal.userId)

    fun detail(
        principal: UserPrincipal,
        id: UUID,
    ): ConversationDetail {
        val conversation = own(principal, id)
        return ConversationDetail(conversation, redactInaccessibleEvidence(principal, repository.messages(id)))
    }

    @Transactional
    fun delete(
        principal: UserPrincipal,
        id: UUID,
    ) {
        own(principal, id)
        repository.delete(id)
    }

    @Transactional
    fun addMessage(
        principal: UserPrincipal,
        conversationId: UUID,
        role: MessageRole,
        content: String,
        evidenceChunkIds: Set<UUID> = emptySet(),
    ): ConversationMessageRecord {
        own(principal, conversationId)
        val message = ConversationMessageRecord(UUID.randomUUID(), conversationId, role, content, evidenceChunkIds, Instant.now())
        repository.addMessage(message)
        repository.touch(conversationId)
        return message
    }

    private fun own(
        principal: UserPrincipal,
        id: UUID,
    ): ConversationRecord {
        val conversation = repository.conversation(id) ?: throw ApiException(ErrorCode.NOT_FOUND)
        if (conversation.userId != principal.userId) throw ApiException(ErrorCode.NOT_FOUND)
        return conversation
    }

    // A message's evidence_chunk_ids was access-checked at ask() time, but a document referenced by an
    // old answer may since have been deleted, revised to a new version, or had its access rules changed.
    // Re-verify against current access on every restore so history never reveals evidence the caller can
    // no longer see; chunks that fail the check are dropped from the set rather than failing the request.
    private fun redactInaccessibleEvidence(
        principal: UserPrincipal,
        messages: List<ConversationMessageRecord>,
    ): List<ConversationMessageRecord> {
        val candidateIds = messages.flatMap { it.evidenceChunkIds }.distinct()
        if (candidateIds.isEmpty()) return messages
        val visibleIds = accessibleChunkIds(policy.scope(principal), candidateIds)
        return messages.map { message ->
            if (message.evidenceChunkIds.isEmpty()) {
                message
            } else {
                message.copy(evidenceChunkIds = message.evidenceChunkIds.filterTo(mutableSetOf()) { it in visibleIds })
            }
        }
    }

    private fun accessibleChunkIds(
        scope: AccessScope,
        chunkIds: List<UUID>,
    ): Set<UUID> =
        jdbc
            .query(
                "SELECT * FROM filter_accessible_chunk_ids(?, ?, ?, ?, ?)",
                PreparedStatementSetter { ps ->
                    ps.setObject(1, scope.departmentId)
                    ps.setString(2, scope.position.name)
                    ps.setString(3, scope.clearance.name)
                    ps.setBoolean(4, scope.isAdmin)
                    ps.setArray(5, ps.connection.createArrayOf("uuid", chunkIds.toTypedArray()))
                },
                RowMapper { rs, _ -> rs.getObject(1, UUID::class.java) },
            ).toSet()
}
