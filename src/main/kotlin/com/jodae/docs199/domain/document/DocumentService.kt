package com.jodae.docs199.domain.document

import com.jodae.docs199.domain.user.Clearance
import com.jodae.docs199.domain.user.DepartmentRepository
import com.jodae.docs199.domain.user.Position
import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.error.ErrorCode
import com.jodae.docs199.global.response.PageResponse
import com.jodae.docs199.global.security.AccessPolicy
import com.jodae.docs199.global.security.DocumentAccessRule
import com.jodae.docs199.global.security.UserPrincipal
import com.jodae.docs199.global.storage.FileStorage
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.multipart.MultipartFile
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class UploadResult(
    val path: String,
    val documentId: UUID?,
    val version: Int?,
    val outcome: String,
    val reason: String? = null,
)

data class DocumentDetail(
    val document: DocumentRecord,
    val departments: Set<UUID>,
    val versions: List<VersionRecord>,
    val job: JobRecord?,
)

@Service
class DocumentService(
    private val repository: DocumentRepository,
    private val storage: FileStorage,
    private val policy: AccessPolicy,
    private val departments: DepartmentRepository,
    private val events: ApplicationEventPublisher,
    private val cleanupQueue: StorageCleanupQueue,
    @Value("\${app.documents.allowed-extensions:pdf,md}") allowedExtensions: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val allowed =
        allowedExtensions
            .split(',')
            .map { it.trim().lowercase() }
            .filter(String::isNotBlank)
            .toSet()

    @Transactional
    fun createSource(
        principal: UserPrincipal,
        kind: SourceKind,
        name: String,
        departmentId: UUID?,
        clearance: Clearance,
    ): SourceRecord {
        if (kind == SourceKind.NOTION) throw ApiException(ErrorCode.INVALID_INPUT, "NOTION 소스는 아직 생성할 수 없습니다")
        validateDepartments(departmentId?.let(::setOf) ?: emptySet())
        val source = SourceRecord(UUID.randomUUID(), kind, name, departmentId, clearance, principal.userId)
        repository.createSource(source)
        return source
    }

    fun sources(): List<SourceRecord> = repository.sources()

    @Transactional
    fun uploadOne(
        principal: UserPrincipal,
        sourceId: UUID,
        path: String,
        file: MultipartFile,
        clearance: Clearance?,
        departments: Set<UUID>?,
        minimumPosition: Position?,
        ownerDepartmentId: UUID? = null,
        author: String? = null,
        authoredAt: LocalDate? = null,
        sourceModifiedAt: Instant? = null,
    ): UploadResult {
        val normalized = path.replace('\\', '/').trim('/')
        val parts = normalized.split('/')
        if (normalized.isBlank() ||
            parts.any { it.isBlank() || it == ".." || it.startsWith('.') } ||
            parts.any { it.equals(".obsidian", true) || it.equals(".trash", true) }
        ) {
            return UploadResult(path, null, null, "EXCLUDED", "숨김 파일 또는 시스템 폴더")
        }
        val extension = normalized.substringAfterLast('.', "").lowercase()
        if (extension !in allowed) return UploadResult(path, null, null, "EXCLUDED", "허용되지 않는 확장자")
        val bytes = file.bytes
        return upsertDocument(
            principal = principal,
            sourceId = sourceId,
            externalId = normalized,
            title = normalized.substringAfterLast('/').substringBeforeLast('.'),
            displayPath = normalized,
            extension = extension,
            bytes = bytes,
            contentType = file.contentType ?: "application/octet-stream",
            clearance = clearance,
            departments = departments,
            minimumPosition = minimumPosition,
            ownerDepartmentId = ownerDepartmentId,
            author = author,
            authoredAt = authoredAt,
            sourceModifiedAt = sourceModifiedAt,
        )
    }

    /**
     * Creates or revises a document from source-independent content. HTTP multipart handling and
     * source-specific path validation belong in the caller; external IDs are not display paths.
     */
    @Transactional
    fun upsertDocument(
        principal: UserPrincipal,
        sourceId: UUID,
        externalId: String,
        title: String,
        displayPath: String,
        extension: String,
        bytes: ByteArray,
        contentType: String,
        clearance: Clearance? = null,
        departments: Set<UUID>? = null,
        minimumPosition: Position? = null,
        ownerDepartmentId: UUID? = null,
        author: String? = null,
        authoredAt: LocalDate? = null,
        sourceModifiedAt: Instant? = null,
    ): UploadResult {
        val source = repository.source(sourceId) ?: throw ApiException(ErrorCode.NOT_FOUND)
        repository.lockSource(sourceId)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val previous = repository.documentByExternalId(sourceId, externalId)
        val latest = previous?.let { repository.currentVersion(it.id) }
        if (latest?.contentHash == hash && previous.deletedAt == null) {
            return UploadResult(displayPath, previous.id, latest.versionNumber, "UNCHANGED")
        }
        val documentId = previous?.id ?: UUID.randomUUID()
        val number = (previous?.currentVersion ?: 0) + 1
        val effectiveDepartments = departments ?: source.defaultDepartmentId?.let(::setOf) ?: emptySet()
        validateDepartments(effectiveDepartments)
        if (previous == null) {
            val effectiveOwner = ownerDepartmentId ?: source.defaultDepartmentId
            effectiveOwner?.let { validateDepartments(setOf(it)) }
            repository.insertDocument(
                DocumentRecord(
                    documentId,
                    sourceId,
                    externalId,
                    title,
                    displayPath,
                    extension,
                    null,
                    author,
                    authoredAt,
                    clearance ?: source.defaultClearance,
                    minimumPosition,
                    0,
                    null,
                    effectiveOwner,
                    sourceModifiedAt,
                ),
            )
            repository.setDepartments(documentId, effectiveDepartments)
        } else {
            repository.updateDocumentDisplay(documentId, title, displayPath, extension)
            if (clearance != null || departments != null || minimumPosition != null) {
                repository.updateAccess(documentId, clearance ?: previous.clearance, minimumPosition ?: previous.minimumPosition)
                if (departments != null) repository.setDepartments(documentId, departments)
            }
            if (ownerDepartmentId != null) {
                validateDepartments(setOf(ownerDepartmentId))
                repository.updateOwnerDepartment(documentId, ownerDepartmentId)
            }
            if (author != null || authoredAt != null || sourceModifiedAt != null) {
                repository.updateMetadata(documentId, author, authoredAt, sourceModifiedAt)
            }
        }
        val versionId = UUID.randomUUID()
        val key = "documents/$documentId/$versionId"
        // Registered before the upload call so an ambiguous outcome (e.g. the object was written but the
        // client timed out waiting for the response) still gets cleaned up when the transaction rolls back.
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCompletion(status: Int) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        try {
                            storage.delete(key)
                        } catch (ex: Exception) {
                            try {
                                cleanupQueue.enqueue(key)
                            } catch (queueError: Exception) {
                                logger.error("저장소 보상 정리 예약 실패 exceptionType={}", queueError.javaClass.simpleName)
                            }
                        }
                    }
                }
            },
        )
        storage.upload(key, bytes.inputStream(), bytes.size.toLong(), contentType)
        repository.addVersion(
            VersionRecord(versionId, documentId, number, key, bytes.size.toLong(), hash, principal.userId, Instant.now()),
        )
        repository.updateVersion(documentId, number)
        events.publishEvent(VersionCreated(versionId))
        return UploadResult(displayPath, documentId, number, "CREATED")
    }

    fun list(
        principal: UserPrincipal,
        filter: DocumentFilter,
        page: Int,
        size: Int,
        admin: Boolean,
    ): PageResponse<DocumentRecord> {
        if (page < 0 || size !in 1..100) throw ApiException(ErrorCode.INVALID_INPUT)
        val (items, total) = repository.list(filter, if (admin) null else policy.scope(principal), page, size)
        return PageResponse(items, page, size, total, ((total + size - 1) / size).toInt())
    }

    fun detail(
        principal: UserPrincipal,
        id: UUID,
        admin: Boolean,
    ): DocumentDetail {
        val document = repository.document(id) ?: throw ApiException(ErrorCode.NOT_FOUND)
        val departments = repository.departments(id)
        if (!admin && !policy.canAccess(principal, DocumentAccessRule(document.clearance, departments, document.minimumPosition))) {
            throw ApiException(ErrorCode.NOT_FOUND)
        }
        val versions = repository.versions(id)
        return DocumentDetail(document, departments, versions, versions.firstOrNull()?.let { repository.jobForVersion(it.id) })
    }

    @Transactional
    fun updateAccess(
        id: UUID,
        clearance: Clearance,
        departments: Set<UUID>,
        position: Position?,
        ownerDepartmentId: UUID? = null,
    ): DocumentDetail {
        val doc = repository.document(id) ?: throw ApiException(ErrorCode.NOT_FOUND)
        validateDepartments(departments)
        repository.updateAccess(id, clearance, position)
        repository.setDepartments(id, departments)
        if (ownerDepartmentId != null) {
            validateDepartments(setOf(ownerDepartmentId))
            repository.updateOwnerDepartment(id, ownerDepartmentId)
        }
        return DocumentDetail(
            doc.copy(
                clearance = clearance,
                minimumPosition = position,
                ownerDepartmentId = ownerDepartmentId ?: doc.ownerDepartmentId,
            ),
            departments,
            repository.versions(id),
            repository.currentVersion(id)?.let { repository.jobForVersion(it.id) },
        )
    }

    @Transactional
    fun delete(id: UUID) {
        repository.document(id) ?: throw ApiException(ErrorCode.NOT_FOUND)
        repository.softDelete(id)
    }

    fun downloadUrl(
        principal: UserPrincipal,
        id: UUID,
        admin: Boolean,
    ): String {
        val detail = detail(principal, id, admin)
        val version = detail.versions.firstOrNull() ?: throw ApiException(ErrorCode.NOT_FOUND)
        return storage.presignedDownloadUrl(version.storageKey, Duration.ofMinutes(15))
    }

    fun job(
        principal: UserPrincipal,
        id: UUID,
        admin: Boolean,
    ): JobRecord {
        val job = repository.job(id) ?: throw ApiException(ErrorCode.NOT_FOUND)
        val version = repository.version(job.versionId) ?: throw ApiException(ErrorCode.NOT_FOUND)
        detail(principal, version.documentId, admin)
        return job
    }

    private fun validateDepartments(ids: Set<UUID>) {
        if (ids.any { !departments.existsById(it) }) throw ApiException(ErrorCode.INVALID_INPUT, "존재하지 않는 부서가 포함됐습니다")
    }
}
