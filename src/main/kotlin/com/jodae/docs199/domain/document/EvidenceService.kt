package com.jodae.docs199.domain.document

import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.error.ErrorCode
import com.jodae.docs199.global.security.AccessPolicy
import com.jodae.docs199.global.security.UserPrincipal
import com.jodae.docs199.global.storage.FileStorage
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.sql.ResultSet
import java.time.Duration
import java.util.UUID

private fun ResultSet.getNullableInt(column: String): Int? {
    val value = getInt(column)
    return if (wasNull()) null else value
}

data class EvidenceResponse(
    val chunkId: UUID,
    val documentId: UUID,
    val documentTitle: String,
    val extension: String,
    val versionId: UUID,
    val versionNumber: Int,
    val content: String,
    val charStart: Int?,
    val charEnd: Int?,
    val page: Int?,
    val previousChunk: String?,
    val nextChunk: String?,
    val downloadUrl: String,
)

private data class ChunkRow(
    val chunkId: UUID,
    val documentVersionId: UUID,
    val chunkIndex: Int,
    val content: String,
    val charStart: Int?,
    val charEnd: Int?,
    val page: Int?,
    val versionNumber: Int,
    val storageKey: String,
    val documentId: UUID,
    val documentTitle: String,
    val extension: String,
)

@Service
class EvidenceService(
    private val jdbc: JdbcTemplate,
    private val policy: AccessPolicy,
    private val storage: FileStorage,
) {
    fun evidence(
        principal: UserPrincipal,
        chunkId: UUID,
    ): EvidenceResponse {
        val scope = policy.scope(principal)
        val accessible =
            jdbc.query(
                "SELECT * FROM filter_accessible_chunk_ids(?, ?, ?, ?, ?)",
                { rs, _ -> rs.getObject(1, UUID::class.java) },
                scope.departmentId,
                scope.position.name,
                scope.clearance.name,
                scope.isAdmin,
                arrayOf(chunkId),
            )
        if (accessible.isEmpty()) throw ApiException(ErrorCode.NOT_FOUND)

        val row =
            jdbc
                .query(
                    """
                    SELECT c.id AS chunk_id, c.document_version_id, c.chunk_index, c.content, c.char_start, c.char_end, c.page,
                           dv.version_number, dv.storage_key, d.id AS document_id, d.title, d.extension
                    FROM chunks c
                    JOIN document_versions dv ON dv.id = c.document_version_id
                    JOIN documents d ON d.id = dv.document_id
                    WHERE c.id = ?
                    """,
                    { rs, _ ->
                        ChunkRow(
                            rs.getObject("chunk_id", UUID::class.java),
                            rs.getObject("document_version_id", UUID::class.java),
                            rs.getInt("chunk_index"),
                            rs.getString("content"),
                            rs.getNullableInt("char_start"),
                            rs.getNullableInt("char_end"),
                            rs.getNullableInt("page"),
                            rs.getInt("version_number"),
                            rs.getString("storage_key"),
                            rs.getObject("document_id", UUID::class.java),
                            rs.getString("title"),
                            rs.getString("extension"),
                        )
                    },
                    chunkId,
                ).firstOrNull() ?: throw ApiException(ErrorCode.NOT_FOUND)

        return EvidenceResponse(
            row.chunkId,
            row.documentId,
            row.documentTitle,
            row.extension,
            row.documentVersionId,
            row.versionNumber,
            row.content,
            row.charStart,
            row.charEnd,
            row.page,
            adjacentChunk(row.documentVersionId, row.chunkIndex - 1),
            adjacentChunk(row.documentVersionId, row.chunkIndex + 1),
            storage.presignedDownloadUrl(row.storageKey, Duration.ofMinutes(15)),
        )
    }

    private fun adjacentChunk(
        documentVersionId: UUID,
        chunkIndex: Int,
    ): String? =
        jdbc
            .query(
                "SELECT content FROM chunks WHERE document_version_id = ? AND chunk_index = ?",
                { rs, _ -> rs.getString("content") },
                documentVersionId,
                chunkIndex,
            ).firstOrNull()
}
