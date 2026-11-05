package com.jodae.docs199.domain.conversation

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

enum class MessageRole { USER, ASSISTANT }

data class ConversationRecord(
    val id: UUID,
    val userId: UUID,
    val title: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ConversationMessageRecord(
    val id: UUID,
    val conversationId: UUID,
    val role: MessageRole,
    val content: String,
    val evidenceChunkIds: Set<UUID>,
    val createdAt: Instant,
)

@Repository
class ConversationRepository(
    private val jdbc: JdbcTemplate,
) {
    fun create(conversation: ConversationRecord) {
        jdbc.update(
            "INSERT INTO conversations(id, user_id, title) VALUES (?, ?, ?)",
            conversation.id,
            conversation.userId,
            conversation.title,
        )
    }

    fun conversation(id: UUID): ConversationRecord? =
        jdbc.query("SELECT * FROM conversations WHERE id = ?", ::mapConversation, id).firstOrNull()

    fun forUser(userId: UUID): List<ConversationRecord> =
        jdbc.query("SELECT * FROM conversations WHERE user_id = ? ORDER BY updated_at DESC", ::mapConversation, userId)

    fun touch(id: UUID) {
        jdbc.update("UPDATE conversations SET updated_at = now() WHERE id = ?", id)
    }

    fun delete(id: UUID) {
        jdbc.update("DELETE FROM conversations WHERE id = ?", id)
    }

    fun addMessage(message: ConversationMessageRecord) {
        jdbc.execute { connection: Connection ->
            connection
                .prepareStatement(
                    "INSERT INTO conversation_messages(id, conversation_id, role, content, evidence_chunk_ids) VALUES (?, ?, ?, ?, ?)",
                ).use { ps ->
                    ps.setObject(1, message.id)
                    ps.setObject(2, message.conversationId)
                    ps.setString(3, message.role.name)
                    ps.setString(4, message.content)
                    ps.setArray(5, connection.createArrayOf("uuid", message.evidenceChunkIds.toTypedArray()))
                    ps.executeUpdate()
                }
        }
    }

    fun messages(conversationId: UUID): List<ConversationMessageRecord> =
        jdbc.query(
            "SELECT * FROM conversation_messages WHERE conversation_id = ? ORDER BY sequence_number",
            ::mapMessage,
            conversationId,
        )

    private fun mapConversation(
        rs: ResultSet,
        row: Int,
    ) = ConversationRecord(
        rs.getObject("id", UUID::class.java),
        rs.getObject("user_id", UUID::class.java),
        rs.getString("title"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
    )

    private fun mapMessage(
        rs: ResultSet,
        row: Int,
    ) = ConversationMessageRecord(
        rs.getObject("id", UUID::class.java),
        rs.getObject("conversation_id", UUID::class.java),
        MessageRole.valueOf(rs.getString("role")),
        rs.getString("content"),
        evidenceChunkIds(rs),
        rs.getTimestamp("created_at").toInstant(),
    )

    private fun evidenceChunkIds(rs: ResultSet): Set<UUID> {
        val raw = rs.getArray("evidence_chunk_ids")?.array as? Array<*> ?: return emptySet()
        return raw.filterNotNull().map { if (it is UUID) it else UUID.fromString(it.toString()) }.toSet()
    }
}
