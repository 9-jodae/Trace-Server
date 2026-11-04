package com.jodae.docs199.domain.document

import com.jodae.docs199.domain.user.Clearance
import com.jodae.docs199.domain.user.Position
import com.jodae.docs199.global.security.AccessScope
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class SourceKind { LOCAL, OBSIDIAN, NOTION }

enum class DocumentType { HANDOVER, APPROVAL, REPORT, PRESENTATION, ETC }

enum class JobStatus { PENDING, PROCESSING, DONE, FAILED }

data class SourceRecord(
    val id: UUID,
    val kind: SourceKind,
    val name: String,
    val defaultDepartmentId: UUID?,
    val defaultClearance: Clearance,
    val createdBy: UUID,
)

data class DocumentRecord(
    val id: UUID,
    val sourceId: UUID,
    val externalId: String,
    val title: String,
    val relativePath: String,
    val extension: String,
    val documentType: DocumentType?,
    val author: String?,
    val authoredAt: LocalDate?,
    val clearance: Clearance,
    val minimumPosition: Position?,
    val currentVersion: Int,
    val deletedAt: Instant?,
    val ownerDepartmentId: UUID? = null,
    val sourceModifiedAt: Instant? = null,
)

data class VersionRecord(
    val id: UUID,
    val documentId: UUID,
    val versionNumber: Int,
    val storageKey: String,
    val fileSize: Long,
    val contentHash: String,
    val uploadedBy: UUID,
    val createdAt: Instant,
)

data class JobRecord(
    val id: UUID,
    val versionId: UUID,
    val status: JobStatus,
    val errorMessage: String?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
)

data class DocumentFilter(
    val sourceId: UUID?,
    val sourceKind: SourceKind?,
    val extension: String?,
    val documentType: DocumentType?,
    val authoredFrom: LocalDate?,
    val authoredTo: LocalDate?,
    val status: JobStatus?,
    val ownerDepartmentId: UUID? = null,
)

@Repository
class DocumentRepository(
    private val jdbc: JdbcTemplate,
    private val named: NamedParameterJdbcTemplate,
) {
    fun createSource(source: SourceRecord) {
        jdbc.update(
            "INSERT INTO sources(id, kind, name, default_department_id, default_clearance, created_by) VALUES (?, ?, ?, ?, ?, ?)",
            source.id,
            source.kind.name,
            source.name,
            source.defaultDepartmentId,
            source.defaultClearance.name,
            source.createdBy,
        )
    }

    fun source(id: UUID): SourceRecord? = jdbc.query("SELECT * FROM sources WHERE id = ?", ::mapSource, id).firstOrNull()

    fun lockSource(id: UUID) {
        jdbc.query("SELECT id FROM sources WHERE id = ? FOR UPDATE", { rs, _ -> rs.getObject(1, UUID::class.java) }, id)
    }

    fun sources(): List<SourceRecord> = jdbc.query("SELECT * FROM sources ORDER BY created_at DESC", ::mapSource)

    fun document(id: UUID): DocumentRecord? =
        jdbc.query("SELECT * FROM documents WHERE id = ? AND deleted_at IS NULL", ::mapDocument, id).firstOrNull()

    fun documentByPath(
        sourceId: UUID,
        path: String,
    ): DocumentRecord? =
        jdbc.query("SELECT * FROM documents WHERE source_id = ? AND external_id = ?", ::mapDocument, sourceId, path).firstOrNull()

    fun insertDocument(document: DocumentRecord) {
        jdbc.update(
            """INSERT INTO documents(id, source_id, external_id, title, relative_path, extension, document_type, author,
               authored_at, clearance, minimum_position, current_version, owner_department_id, source_modified_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            document.id,
            document.sourceId,
            document.externalId,
            document.title,
            document.relativePath,
            document.extension,
            document.documentType?.name,
            document.author,
            document.authoredAt,
            document.clearance.name,
            document.minimumPosition?.name,
            document.currentVersion,
            document.ownerDepartmentId,
            document.sourceModifiedAt?.let(Timestamp::from),
        )
    }

    fun updateOwnerDepartment(
        id: UUID,
        ownerDepartmentId: UUID?,
    ) {
        jdbc.update("UPDATE documents SET owner_department_id = ?, updated_at = now() WHERE id = ?", ownerDepartmentId, id)
    }

    fun updateMetadata(
        id: UUID,
        author: String?,
        authoredAt: LocalDate?,
        sourceModifiedAt: Instant?,
    ) {
        jdbc.update(
            """UPDATE documents SET author = COALESCE(?, author), authored_at = COALESCE(?, authored_at),
               source_modified_at = COALESCE(?, source_modified_at), updated_at = now() WHERE id = ?""",
            author,
            authoredAt,
            sourceModifiedAt?.let(Timestamp::from),
            id,
        )
    }

    fun fillAuthorMetadataIfAbsent(
        documentId: UUID,
        versionNumber: Int,
        author: String?,
        authoredAt: LocalDate?,
    ) {
        if (author == null && authoredAt == null) return
        jdbc.update(
            """UPDATE documents SET author = COALESCE(author, ?), authored_at = COALESCE(authored_at, ?), updated_at = now()
               WHERE id = ? AND current_version = ? AND deleted_at IS NULL""",
            author,
            authoredAt,
            documentId,
            versionNumber,
        )
    }

    fun updateVersion(
        documentId: UUID,
        number: Int,
    ) {
        jdbc.update(
            "UPDATE documents SET current_version = ?, document_type = NULL, updated_at = now(), deleted_at = NULL WHERE id = ?",
            number,
            documentId,
        )
    }

    fun updateAccess(
        id: UUID,
        clearance: Clearance,
        position: Position?,
    ) {
        jdbc.update(
            "UPDATE documents SET clearance = ?, minimum_position = ?, updated_at = now() WHERE id = ?",
            clearance.name,
            position?.name,
            id,
        )
    }

    fun updateTypeIfCurrent(
        documentId: UUID,
        versionNumber: Int,
        type: DocumentType,
    ): Boolean =
        jdbc.update(
            "UPDATE documents SET document_type = ?, updated_at = now() WHERE id = ? AND current_version = ? AND deleted_at IS NULL",
            type.name,
            documentId,
            versionNumber,
        ) > 0

    fun softDelete(id: UUID) {
        jdbc.update("UPDATE documents SET deleted_at = now(), updated_at = now() WHERE id = ?", id)
    }

    fun departments(documentId: UUID): Set<UUID> =
        jdbc
            .query(
                "SELECT department_id FROM document_departments WHERE document_id = ?",
                { rs, _ -> rs.getObject(1, UUID::class.java) },
                documentId,
            ).toSet()

    fun setDepartments(
        documentId: UUID,
        ids: Set<UUID>,
    ) {
        jdbc.update("DELETE FROM document_departments WHERE document_id = ?", documentId)
        ids.forEach { jdbc.update("INSERT INTO document_departments(document_id, department_id) VALUES (?, ?)", documentId, it) }
    }

    fun addVersion(version: VersionRecord) {
        jdbc.update(
            "INSERT INTO document_versions(id, document_id, version_number, storage_key, file_size, content_hash, uploaded_by) VALUES (?, ?, ?, ?, ?, ?, ?)",
            version.id,
            version.documentId,
            version.versionNumber,
            version.storageKey,
            version.fileSize,
            version.contentHash,
            version.uploadedBy,
        )
    }

    fun versions(documentId: UUID): List<VersionRecord> =
        jdbc.query("SELECT * FROM document_versions WHERE document_id = ? ORDER BY version_number DESC", ::mapVersion, documentId)

    fun version(id: UUID): VersionRecord? = jdbc.query("SELECT * FROM document_versions WHERE id = ?", ::mapVersion, id).firstOrNull()

    fun currentVersion(documentId: UUID): VersionRecord? =
        jdbc
            .query(
                "SELECT * FROM document_versions WHERE document_id = ? ORDER BY version_number DESC LIMIT 1",
                ::mapVersion,
                documentId,
            ).firstOrNull()

    fun createJob(
        id: UUID,
        versionId: UUID,
    ): UUID {
        jdbc.update(
            "INSERT INTO ingestion_jobs(id, version_id, status) VALUES (?, ?, 'PENDING') ON CONFLICT (version_id) DO NOTHING",
            id,
            versionId,
        )
        return requireNotNull(jobForVersion(versionId)).id
    }

    fun versionsNeedingIngestion(): List<VersionRecord> =
        jdbc.query(
            """SELECT v.* FROM document_versions v
               JOIN documents d ON d.id = v.document_id
               LEFT JOIN ingestion_jobs j ON j.version_id = v.id
               WHERE d.deleted_at IS NULL AND (j.id IS NULL OR j.status = 'PENDING')
               ORDER BY v.created_at LIMIT 100""",
            ::mapVersion,
        )

    fun enqueueCleanup(key: String) {
        jdbc.update(
            "INSERT INTO storage_cleanup_jobs(id, storage_key) VALUES (?, ?) ON CONFLICT (storage_key) DO NOTHING",
            UUID.randomUUID(),
            key,
        )
    }

    fun pendingCleanup(): List<Pair<UUID, String>> =
        jdbc.query(
            "SELECT id, storage_key FROM storage_cleanup_jobs ORDER BY created_at LIMIT 100",
            { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getString("storage_key") },
        )

    fun finishCleanup(id: UUID) {
        jdbc.update("DELETE FROM storage_cleanup_jobs WHERE id = ?", id)
    }

    fun failedCleanup(id: UUID) {
        jdbc.update("UPDATE storage_cleanup_jobs SET attempts = attempts + 1, last_attempt_at = now() WHERE id = ?", id)
    }

    fun job(id: UUID): JobRecord? = jdbc.query("SELECT * FROM ingestion_jobs WHERE id = ?", ::mapJob, id).firstOrNull()

    fun jobForVersion(versionId: UUID): JobRecord? =
        jdbc.query("SELECT * FROM ingestion_jobs WHERE version_id = ?", ::mapJob, versionId).firstOrNull()

    fun transitionJob(
        id: UUID,
        status: JobStatus,
        error: String?,
    ): Boolean =
        jdbc.update(
            """UPDATE ingestion_jobs SET status = ?, error_message = ?,
               started_at = CASE WHEN ? = 'PROCESSING' THEN now() ELSE started_at END,
               finished_at = CASE WHEN ? IN ('DONE', 'FAILED') THEN now() ELSE finished_at END
               WHERE id = ? AND (status = 'PENDING' OR (status = 'PROCESSING' AND ? IN ('DONE', 'FAILED')))""",
            status.name,
            error,
            status.name,
            status.name,
            id,
            status.name,
        ) > 0

    fun list(
        filter: DocumentFilter,
        scope: AccessScope?,
        page: Int,
        size: Int,
    ): Pair<List<DocumentRecord>, Long> {
        val where = StringBuilder(" WHERE d.deleted_at IS NULL")
        val p = MapSqlParameterSource().addValue("limit", size).addValue("offset", page * size)
        filter.sourceId?.let {
            where.append(" AND d.source_id = :sourceId")
            p.addValue("sourceId", it)
        }
        filter.sourceKind?.let {
            where.append(" AND EXISTS (SELECT 1 FROM sources s WHERE s.id = d.source_id AND s.kind = :sourceKind)")
            p.addValue("sourceKind", it.name)
        }
        filter.ownerDepartmentId?.let {
            where.append(" AND d.owner_department_id = :ownerDepartmentId")
            p.addValue("ownerDepartmentId", it)
        }
        filter.extension?.let {
            where.append(" AND d.extension = :extension")
            p.addValue("extension", it.lowercase())
        }
        filter.documentType?.let {
            where.append(" AND d.document_type = :documentType")
            p.addValue("documentType", it.name)
        }
        filter.authoredFrom?.let {
            where.append(" AND d.authored_at >= :authoredFrom")
            p.addValue("authoredFrom", it)
        }
        filter.authoredTo?.let {
            where.append(" AND d.authored_at <= :authoredTo")
            p.addValue("authoredTo", it)
        }
        filter.status?.let {
            where.append(
                " AND EXISTS (SELECT 1 FROM document_versions v JOIN ingestion_jobs j ON j.version_id = v.id WHERE v.document_id = d.id AND v.version_number = d.current_version AND j.status = :status)",
            )
            p.addValue("status", it.name)
        }
        if (scope != null) {
            where.append(
                """ AND (:isAdmin = true OR (
                    CASE d.clearance WHEN 'PUBLIC' THEN 0 WHEN 'INTERNAL' THEN 1 ELSE 2 END <= :clearance
                    AND (NOT EXISTS (SELECT 1 FROM document_departments dd WHERE dd.document_id = d.id)
                         OR EXISTS (SELECT 1 FROM document_departments dd WHERE dd.document_id = d.id AND dd.department_id = :departmentId))
                    AND (d.minimum_position IS NULL OR CASE d.minimum_position
                         WHEN 'STAFF' THEN 0 WHEN 'SENIOR' THEN 1 WHEN 'MANAGER' THEN 2 ELSE 3 END <= :position)))""",
            )
            p
                .addValue("isAdmin", scope.isAdmin)
                .addValue("departmentId", scope.departmentId)
                .addValue("clearance", scope.clearance.ordinal)
                .addValue("position", scope.position.ordinal)
        }
        val from = " FROM documents d" + where
        val total = named.queryForObject("SELECT count(*)" + from, p, Long::class.java) ?: 0L
        val rows = named.query("SELECT d.*" + from + " ORDER BY d.created_at DESC, d.id LIMIT :limit OFFSET :offset", p, ::mapDocument)
        return rows to total
    }

    private fun mapSource(
        rs: ResultSet,
        row: Int,
    ) = SourceRecord(
        rs.getObject("id", UUID::class.java),
        SourceKind.valueOf(rs.getString("kind")),
        rs.getString("name"),
        rs.getObject("default_department_id", UUID::class.java),
        Clearance.valueOf(rs.getString("default_clearance")),
        rs.getObject("created_by", UUID::class.java),
    )

    private fun mapDocument(
        rs: ResultSet,
        row: Int,
    ) = DocumentRecord(
        rs.getObject("id", UUID::class.java),
        rs.getObject("source_id", UUID::class.java),
        rs.getString("external_id"),
        rs.getString("title"),
        rs.getString("relative_path"),
        rs.getString("extension"),
        rs.getString("document_type")?.let(DocumentType::valueOf),
        rs.getString("author"),
        rs.getDate("authored_at")?.toLocalDate(),
        Clearance.valueOf(rs.getString("clearance")),
        rs.getString("minimum_position")?.let(Position::valueOf),
        rs.getInt("current_version"),
        rs.getTimestamp("deleted_at")?.toInstant(),
        rs.getObject("owner_department_id", UUID::class.java),
        rs.getTimestamp("source_modified_at")?.toInstant(),
    )

    private fun mapVersion(
        rs: ResultSet,
        row: Int,
    ) = VersionRecord(
        rs.getObject("id", UUID::class.java),
        rs.getObject("document_id", UUID::class.java),
        rs.getInt("version_number"),
        rs.getString("storage_key"),
        rs.getLong("file_size"),
        rs.getString("content_hash"),
        rs.getObject("uploaded_by", UUID::class.java),
        rs.getTimestamp("created_at").toInstant(),
    )

    private fun mapJob(
        rs: ResultSet,
        row: Int,
    ) = JobRecord(
        rs.getObject("id", UUID::class.java),
        rs.getObject("version_id", UUID::class.java),
        JobStatus.valueOf(rs.getString("status")),
        rs.getString("error_message"),
        rs.getTimestamp("started_at")?.toInstant(),
        rs.getTimestamp("finished_at")?.toInstant(),
    )
}
