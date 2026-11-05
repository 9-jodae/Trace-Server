package com.jodae.docs199.domain.graph

import com.jodae.docs199.domain.user.Clearance
import com.jodae.docs199.global.security.AccessScope
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

data class GraphNodeRow(
    val id: UUID,
    val title: String,
    val documentType: String?,
    val ownerDepartmentId: UUID?,
    val ownerDepartmentName: String?,
    val clearance: Clearance,
    val currentVersion: Int,
    val versionCount: Int,
)

data class GraphRelationRow(
    val id: UUID,
    val source: UUID,
    val target: UUID,
    val type: RelationType,
    val detail: ConflictDetail?,
)

data class GraphDocumentTopicRow(
    val topic: String,
    val documentId: UUID,
)

@Repository
class GraphRepository(
    private val jdbc: JdbcTemplate,
) {
    fun topicsForAccessibleCurrentDocuments(scope: AccessScope): List<GraphDocumentTopicRow> =
        jdbc.query(
            """
            SELECT lower(trim(t.topic)) AS topic, d.id AS document_id
            FROM document_topics t
            JOIN document_versions dv ON dv.id = t.document_version_id
            JOIN documents d ON d.id = dv.document_id AND dv.version_number = d.current_version
            JOIN accessible_document_versions(?, ?, ?, ?) av ON av.document_version_id = dv.id
            WHERE d.deleted_at IS NULL
            ORDER BY lower(trim(t.topic)), d.id
            """.trimIndent(),
            { rs, _ ->
                GraphDocumentTopicRow(rs.getString("topic"), rs.getObject("document_id", UUID::class.java))
            },
            scope.departmentId,
            scope.position.name,
            scope.clearance.name,
            scope.isAdmin,
        )

    fun accessibleNodes(scope: AccessScope): List<GraphNodeRow> =
        jdbc.query(
            """
            SELECT d.id, d.title, d.document_type, d.owner_department_id, owner.name AS owner_department_name,
                   d.clearance, d.current_version,
                   (SELECT count(*) FROM document_versions all_versions WHERE all_versions.document_id = d.id) AS version_count
            FROM documents d
            JOIN document_versions current_dv ON current_dv.document_id = d.id AND current_dv.version_number = d.current_version
            JOIN accessible_document_versions(?, ?, ?, ?) av ON av.document_version_id = current_dv.id
            LEFT JOIN departments owner ON owner.id = d.owner_department_id
            WHERE d.deleted_at IS NULL
            ORDER BY d.title, d.id
            """.trimIndent(),
            { rs, _ ->
                GraphNodeRow(
                    rs.getObject("id", UUID::class.java),
                    rs.getString("title"),
                    rs.getString("document_type"),
                    rs.getObject("owner_department_id", UUID::class.java),
                    rs.getString("owner_department_name"),
                    Clearance.valueOf(rs.getString("clearance")),
                    rs.getInt("current_version"),
                    rs.getInt("version_count"),
                )
            },
            scope.departmentId,
            scope.position.name,
            scope.clearance.name,
            scope.isAdmin,
        )

    fun relationsBetweenAccessibleDocuments(scope: AccessScope): List<GraphRelationRow> =
        jdbc.query(
            """
            SELECT r.id, r.source_document_id, r.target_document_id, r.relation_type, r.detail::text AS detail
            FROM document_relations r
            WHERE r.source_document_id IN (
                SELECT d.id FROM documents d
                JOIN document_versions dv ON dv.document_id = d.id AND dv.version_number = d.current_version
                JOIN accessible_document_versions(?, ?, ?, ?) av ON av.document_version_id = dv.id
                WHERE d.deleted_at IS NULL
            )
            AND r.target_document_id IN (
                SELECT d.id FROM documents d
                JOIN document_versions dv ON dv.document_id = d.id AND dv.version_number = d.current_version
                JOIN accessible_document_versions(?, ?, ?, ?) av ON av.document_version_id = dv.id
                WHERE d.deleted_at IS NULL
            )
            ORDER BY r.id
            """.trimIndent(),
            { rs, _ ->
                val type = RelationType.valueOf(rs.getString("relation_type"))
                GraphRelationRow(
                    rs.getObject("id", UUID::class.java),
                    rs.getObject("source_document_id", UUID::class.java),
                    rs.getObject("target_document_id", UUID::class.java),
                    type,
                    rs.getString("detail")?.let(ConflictDetail::fromJson),
                )
            },
            *scopeParams(scope, repeat = 2),
        )

    fun hasAccessibleDocument(
        scope: AccessScope,
        id: UUID,
    ): Boolean =
        jdbc
            .query(
                """
                SELECT EXISTS (
                    SELECT 1 FROM documents d
                    JOIN document_versions dv ON dv.document_id = d.id AND dv.version_number = d.current_version
                    JOIN accessible_document_versions(?, ?, ?, ?) av ON av.document_version_id = dv.id
                    WHERE d.id = ? AND d.deleted_at IS NULL
                )
                """.trimIndent(),
                { rs, _ -> rs.getBoolean(1) },
                scope.departmentId,
                scope.position.name,
                scope.clearance.name,
                scope.isAdmin,
                id,
            ).first()

    fun upsert(relation: GraphRelationRow): UUID {
        val detailJson = relation.detail?.toJson()
        val conflictTarget =
            if (relation.type == RelationType.CONFLICTS) {
                "(source_document_id, target_document_id, (detail->>'item')) WHERE relation_type = 'CONFLICTS'"
            } else {
                "(source_document_id, target_document_id) WHERE relation_type = 'REFERENCES'"
            }
        return jdbc.queryForObject(
            """
            INSERT INTO document_relations (id, source_document_id, target_document_id, relation_type, detail)
            VALUES (?, ?, ?, ?, ?::jsonb)
            ON CONFLICT $conflictTarget
            DO UPDATE SET detail = EXCLUDED.detail
            RETURNING id
            """.trimIndent(),
            UUID::class.java,
            relation.id,
            relation.source,
            relation.target,
            relation.type.name,
            detailJson,
        ) ?: throw IllegalStateException("문서 관계를 저장하지 못했습니다")
    }

    fun accessibleRelation(
        scope: AccessScope,
        id: UUID,
    ): GraphRelationRow? =
        jdbc
            .query(
                """
                SELECT r.id, r.source_document_id, r.target_document_id, r.relation_type, r.detail::text AS detail
                FROM document_relations r
                WHERE r.id = ?
                  AND EXISTS (
                    SELECT 1 FROM documents d
                    JOIN document_versions dv ON dv.document_id = d.id AND dv.version_number = d.current_version
                    JOIN accessible_document_versions(?, ?, ?, ?) av ON av.document_version_id = dv.id
                    WHERE d.id = r.source_document_id AND d.deleted_at IS NULL
                  )
                  AND EXISTS (
                    SELECT 1 FROM documents d
                    JOIN document_versions dv ON dv.document_id = d.id AND dv.version_number = d.current_version
                    JOIN accessible_document_versions(?, ?, ?, ?) av ON av.document_version_id = dv.id
                    WHERE d.id = r.target_document_id AND d.deleted_at IS NULL
                  )
                """.trimIndent(),
                { rs, _ ->
                    val type = RelationType.valueOf(rs.getString("relation_type"))
                    GraphRelationRow(
                        rs.getObject("id", UUID::class.java),
                        rs.getObject("source_document_id", UUID::class.java),
                        rs.getObject("target_document_id", UUID::class.java),
                        type,
                        rs.getString("detail")?.let(ConflictDetail::fromJson),
                    )
                },
                id,
                *scopeParams(scope, repeat = 2),
            ).firstOrNull()

    fun delete(id: UUID) {
        jdbc.update("DELETE FROM document_relations WHERE id = ?", id)
    }

    private fun scopeParams(
        scope: AccessScope,
        repeat: Int,
    ): Array<Any> =
        Array(repeat * 4) {
            when (it % 4) {
                0 -> scope.departmentId
                1 -> scope.position.name
                2 -> scope.clearance.name
                else -> scope.isAdmin
            }
        }
}
