package com.jodae.docs199.domain.document

import com.jodae.docs199.domain.user.Clearance
import com.jodae.docs199.domain.user.Position
import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.error.ErrorCode
import com.jodae.docs199.global.response.ApiResponse
import com.jodae.docs199.global.response.PageResponse
import com.jodae.docs199.global.security.CurrentUser
import com.jodae.docs199.global.security.UserPrincipal
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.beans.factory.annotation.Value
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class SourceRequest(
    val kind: SourceKind = SourceKind.LOCAL,
    @field:NotBlank(message = "소스 이름을 입력하세요") val name: String,
    val defaultDepartmentId: UUID?,
    val defaultClearance: Clearance = Clearance.PUBLIC,
)

data class AccessRequest(
    val clearance: Clearance,
    val departmentIds: Set<UUID> = emptySet(),
    val minimumPosition: Position?,
    val ownerDepartmentId: UUID? = null,
)

data class VersionView(
    val id: UUID,
    val number: Int,
    val fileSize: Long,
    val contentHash: String,
    val uploadedBy: UUID,
    val createdAt: Instant,
)

data class DetailView(
    val document: DocumentRecord,
    val departmentIds: Set<UUID>,
    val versions: List<VersionView>,
    val job: JobRecord?,
)

fun DocumentDetail.view() =
    DetailView(
        document,
        departments,
        versions.map {
            VersionView(it.id, it.versionNumber, it.fileSize, it.contentHash, it.uploadedBy, it.createdAt)
        },
        job,
    )

@RestController
@RequestMapping("/api/admin")
class AdminDocumentController(
    private val service: DocumentService,
) {
    @PostMapping("/sources")
    fun createSource(
        @CurrentUser principal: UserPrincipal,
        @Valid @RequestBody request: SourceRequest,
    ) = ApiResponse.ok(service.createSource(principal, request.kind, request.name, request.defaultDepartmentId, request.defaultClearance))

    @GetMapping("/sources")
    fun sources() = ApiResponse.ok(service.sources())

    @PostMapping("/sources/{sourceId}/documents")
    fun upload(
        @CurrentUser principal: UserPrincipal,
        @PathVariable sourceId: UUID,
        @RequestPart files: List<MultipartFile>,
        @RequestParam paths: List<String>,
        @RequestParam(required = false) clearance: Clearance?,
        @RequestParam(required = false) departmentIds: Set<UUID>?,
        @RequestParam(required = false) minimumPosition: Position?,
        @RequestParam(required = false) ownerDepartmentId: UUID?,
        @RequestParam(required = false) author: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) authoredAt: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) sourceModifiedAt: Instant?,
    ): ApiResponse<List<UploadResult>> {
        if (files.size != paths.size) throw ApiException(ErrorCode.INVALID_INPUT, "파일과 상대 경로의 개수가 다릅니다")
        return ApiResponse.ok(
            files.indices.map {
                service.uploadOne(
                    principal,
                    sourceId,
                    paths[it],
                    files[it],
                    clearance,
                    departmentIds,
                    minimumPosition,
                    ownerDepartmentId,
                    author,
                    authoredAt,
                    sourceModifiedAt,
                )
            },
        )
    }

    @GetMapping("/documents")
    fun list(
        @CurrentUser principal: UserPrincipal,
        @RequestParam(required = false) sourceId: UUID?,
        @RequestParam(required = false) sourceKind: SourceKind?,
        @RequestParam(required = false) extension: String?,
        @RequestParam(required = false) documentType: DocumentType?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) authoredFrom: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) authoredTo: LocalDate?,
        @RequestParam(required = false) status: JobStatus?,
        @RequestParam(required = false) ownerDepartmentId: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResponse<DocumentRecord>> =
        ApiResponse.ok(
            service.list(
                principal,
                DocumentFilter(sourceId, sourceKind, extension, documentType, authoredFrom, authoredTo, status, ownerDepartmentId),
                page,
                size,
                true,
            ),
        )

    @GetMapping("/documents/{id}")
    fun detail(
        @CurrentUser principal: UserPrincipal,
        @PathVariable id: UUID,
    ) = ApiResponse.ok(service.detail(principal, id, true).view())

    @PatchMapping("/documents/{id}/access")
    fun access(
        @PathVariable id: UUID,
        @RequestBody request: AccessRequest,
    ) = ApiResponse.ok(
        service
            .updateAccess(id, request.clearance, request.departmentIds, request.minimumPosition, request.ownerDepartmentId)
            .view(),
    )

    @PostMapping("/documents/{id}/revisions")
    fun revise(
        @CurrentUser principal: UserPrincipal,
        @PathVariable id: UUID,
        @RequestPart file: MultipartFile,
        @RequestParam(required = false) author: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) authoredAt: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) sourceModifiedAt: Instant?,
    ): ApiResponse<UploadResult> {
        val document = service.detail(principal, id, true).document
        return ApiResponse.ok(
            service.uploadOne(
                principal,
                document.sourceId,
                document.relativePath,
                file,
                null,
                null,
                null,
                null,
                author,
                authoredAt,
                sourceModifiedAt,
            ),
        )
    }

    @DeleteMapping("/documents/{id}")
    fun delete(
        @PathVariable id: UUID,
    ): ApiResponse<Map<String, Boolean>> {
        service.delete(id)
        return ApiResponse.ok(mapOf("deleted" to true))
    }

    @GetMapping("/documents/{id}/download-url")
    fun url(
        @CurrentUser principal: UserPrincipal,
        @PathVariable id: UUID,
    ) = ApiResponse.ok(
        mapOf(
            "url" to service.downloadUrl(principal, id, true),
        ),
    )

    @GetMapping("/ingestion-jobs/{id}")
    fun job(
        @CurrentUser principal: UserPrincipal,
        @PathVariable id: UUID,
    ) = ApiResponse.ok(service.job(principal, id, true))
}

@RestController
@RequestMapping("/api/documents")
class EmployeeDocumentController(
    private val service: DocumentService,
) {
    @GetMapping
    fun list(
        @CurrentUser principal: UserPrincipal,
        @RequestParam(required = false) sourceId: UUID?,
        @RequestParam(required = false) sourceKind: SourceKind?,
        @RequestParam(required = false) extension: String?,
        @RequestParam(required = false) documentType: DocumentType?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) authoredFrom: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) authoredTo: LocalDate?,
        @RequestParam(required = false) status: JobStatus?,
        @RequestParam(required = false) ownerDepartmentId: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ) = ApiResponse.ok(
        service.list(
            principal,
            DocumentFilter(sourceId, sourceKind, extension, documentType, authoredFrom, authoredTo, status, ownerDepartmentId),
            page,
            size,
            false,
        ),
    )

    @GetMapping("/{id}")
    fun detail(
        @CurrentUser principal: UserPrincipal,
        @PathVariable id: UUID,
    ) = ApiResponse.ok(service.detail(principal, id, false).view())

    @GetMapping("/{id}/download-url")
    fun url(
        @CurrentUser principal: UserPrincipal,
        @PathVariable id: UUID,
    ) = ApiResponse.ok(
        mapOf(
            "url" to service.downloadUrl(principal, id, false),
        ),
    )

    @GetMapping("/{id}/status")
    fun status(
        @CurrentUser principal: UserPrincipal,
        @PathVariable id: UUID,
    ) = ApiResponse.ok(service.detail(principal, id, false).job)
}

data class IngestionResultRequest(
    val status: JobStatus,
    val errorMessage: String?,
    val documentType: DocumentType?,
    val author: String? = null,
    val authoredAt: LocalDate? = null,
)

@RestController
@RequestMapping("/api/internal/ingestion-jobs")
class IngestionCallbackController(
    private val jobs: JobUpdater,
    @Value("\${app.internal-secret}") private val secret: String,
) {
    @PostMapping("/{id}/result")
    fun result(
        @PathVariable id: UUID,
        @RequestHeader("X-Internal-Secret", required = false) supplied: String?,
        @RequestBody request: IngestionResultRequest,
    ): ApiResponse<Map<String, Boolean>> {
        val actual = supplied?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        if (!MessageDigest.isEqual(secret.toByteArray(Charsets.UTF_8), actual)) throw ApiException(ErrorCode.FORBIDDEN)
        jobs.result(id, request.status, request.errorMessage, request.documentType, request.author, request.authoredAt)
        return ApiResponse.ok(mapOf("accepted" to true))
    }
}
