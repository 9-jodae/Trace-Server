package com.jodae.docs199.domain.conversation

import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.error.ErrorCode
import com.jodae.docs199.global.security.UserPrincipal
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
        return ConversationDetail(conversation, repository.messages(id))
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
}
