package com.jodae.docs199

import com.jodae.docs199.domain.document.DocumentFilter
import com.jodae.docs199.domain.document.DocumentRecord
import com.jodae.docs199.domain.document.DocumentRepository
import com.jodae.docs199.domain.document.DocumentService
import com.jodae.docs199.domain.document.DocumentType
import com.jodae.docs199.domain.document.IngestionAfterCommit
import com.jodae.docs199.domain.document.JobStatus
import com.jodae.docs199.domain.document.JobUpdater
import com.jodae.docs199.domain.document.SourceKind
import com.jodae.docs199.domain.document.StorageCleanupQueue
import com.jodae.docs199.domain.document.StorageCleanupWorker
import com.jodae.docs199.domain.document.VersionRecord
import com.jodae.docs199.domain.user.Clearance
import com.jodae.docs199.domain.user.Position
import com.jodae.docs199.domain.user.UserRepository
import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.security.UserPrincipal
import com.jodae.docs199.global.storage.FileStorage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.LinkedMultiValueMap
import java.net.URI
import java.time.Instant
import java.util.UUID

class DocumentIntegrationTests : IntegrationTestBase() {
    @Autowired lateinit var service: DocumentService

    @Autowired lateinit var repository: DocumentRepository

    @Autowired lateinit var users: UserRepository

    @Autowired lateinit var rest: TestRestTemplate

    @Autowired lateinit var jobs: JobUpdater

    @Autowired lateinit var ingestion: IngestionAfterCommit

    @Autowired lateinit var storage: FileStorage

    @Autowired lateinit var cleanupQueue: StorageCleanupQueue

    @Autowired lateinit var cleanupWorker: StorageCleanupWorker

    @Autowired lateinit var transactions: PlatformTransactionManager

    private fun principal(email: String): UserPrincipal {
        val user = users.findByEmail(email)!!
        return UserPrincipal(user.id, user.role, user.department.id, user.position, user.clearance)
    }

    private fun file(text: String) = MockMultipartFile("file", "memo.md", "text/markdown", text.toByteArray())

    @Test
    fun uploadVersionJobAndExclusions() {
        val admin = principal("doc.admin@local.docs199")
        val source = service.createSource(admin, SourceKind.LOCAL, "테스트-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val first = service.uploadOne(admin, source.id, "notes/memo.md", file("첫 내용"), null, null, null)
        assertEquals("CREATED", first.outcome)
        val id = first.documentId!!
        assertEquals(1, first.version)
        val url = service.downloadUrl(admin, id, true)
        assertTrue(url.contains("documents/"))
        assertEquals("첫 내용", URI(url).toURL().openStream().use { it.readAllBytes().decodeToString() })
        repeat(30) {
            if (service.detail(admin, id, true).job?.status == JobStatus.DONE) return@repeat
            Thread.sleep(100)
        }
        assertEquals(JobStatus.DONE, service.detail(admin, id, true).job?.status)
        assertEquals("UNCHANGED", service.uploadOne(admin, source.id, "notes/memo.md", file("첫 내용"), null, null, null).outcome)
        assertEquals(2, service.uploadOne(admin, source.id, "notes/memo.md", file("둘째 내용"), null, null, null).version)
        assertEquals(2, repository.versions(id).size)
        assertEquals("EXCLUDED", service.uploadOne(admin, source.id, ".obsidian/cache.md", file("x"), null, null, null).outcome)
        assertEquals("EXCLUDED", service.uploadOne(admin, source.id, "notes/a.exe", file("x"), null, null, null).outcome)
        service.delete(id)
        assertThrows(ApiException::class.java) { service.detail(admin, id, true) }
    }

    @Test
    fun employeeListRespectsAllConditions() {
        val admin = principal("doc.admin@local.docs199")
        val hr = principal("hr.staff@local.docs199")
        val dev = principal("dev.manager@local.docs199")
        val director = principal("ops.director@local.docs199")
        val source = service.createSource(admin, SourceKind.LOCAL, "권한-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val id =
            service
                .uploadOne(
                    admin,
                    source.id,
                    "private.md",
                    file("기밀"),
                    Clearance.CONFIDENTIAL,
                    setOf(dev.departmentId),
                    Position.MANAGER,
                ).documentId!!
        val filter = DocumentFilter(source.id, null, null, null, null, null, null)
        assertFalse(service.list(hr, filter, 0, 20, false).items.any { it.id == id })
        assertFalse(service.list(dev, filter, 0, 20, false).items.any { it.id == id })
        assertFalse(service.list(director, filter, 0, 20, false).items.any { it.id == id })
        assertTrue(
            service.list(admin.copy(role = com.jodae.docs199.domain.user.Role.SYSTEM_ADMIN), filter, 0, 20, false).items.any {
                it.id ==
                    id
            },
        )
        service.updateAccess(id, Clearance.PUBLIC, setOf(dev.departmentId), Position.MANAGER)
        assertTrue(service.list(dev, filter, 0, 20, false).items.any { it.id == id })
        assertFalse(service.list(hr, filter, 0, 20, false).items.any { it.id == id })
        service.updateAccess(id, Clearance.PUBLIC, emptySet(), Position.STAFF)
        assertTrue(service.list(hr, filter, 0, 20, false).items.any { it.id == id })
    }

    @Test
    fun ownerDepartmentDefaultsAndFiltersIndependentlyOfAccess() {
        val admin = principal("doc.admin@local.docs199")
        val hr = principal("hr.staff@local.docs199")
        val dev = principal("dev.manager@local.docs199")
        val defaultedSource = service.createSource(admin, SourceKind.LOCAL, "기본소유-${UUID.randomUUID()}", hr.departmentId, Clearance.PUBLIC)
        val defaultedId =
            service.uploadOne(admin, defaultedSource.id, "default-owner.md", file("기본"), null, null, null).documentId!!
        assertEquals(hr.departmentId, repository.document(defaultedId)?.ownerDepartmentId)

        val source = service.createSource(admin, SourceKind.LOCAL, "소유부서-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val id =
            service
                .uploadOne(admin, source.id, "owner.md", file("소유 테스트"), null, null, null, dev.departmentId)
                .documentId!!
        assertEquals(dev.departmentId, repository.document(id)?.ownerDepartmentId)
        assertTrue(service.list(hr, DocumentFilter(source.id, null, null, null, null, null, null), 0, 20, false).items.any { it.id == id })
        assertTrue(
            service
                .list(admin, DocumentFilter(null, null, null, null, null, null, null, dev.departmentId), 0, 20, true)
                .items
                .any { it.id == id },
        )
        assertFalse(
            service
                .list(admin, DocumentFilter(null, null, null, null, null, null, null, hr.departmentId), 0, 20, true)
                .items
                .any { it.id == id },
        )
        service.updateAccess(id, Clearance.PUBLIC, emptySet(), null)
        assertEquals(dev.departmentId, repository.document(id)?.ownerDepartmentId)
        service.updateAccess(id, Clearance.PUBLIC, emptySet(), null, hr.departmentId)
        assertEquals(hr.departmentId, repository.document(id)?.ownerDepartmentId)
    }

    @Test
    fun callbackRequiresSecret() {
        val body = """{"status":"DONE"}"""
        val request = HttpEntity(body, HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON })
        val noHeader = rest.postForEntity("/api/internal/ingestion-jobs/${UUID.randomUUID()}/result", request, String::class.java)
        assertEquals(HttpStatus.FORBIDDEN, noHeader.statusCode)
    }

    @Test
    fun multipartApiAndAdminRole() {
        val admin = principal("doc.admin@local.docs199")
        val source = service.createSource(admin, SourceKind.LOCAL, "API-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val tokenBody = rest.postForEntity("/api/dev/token", null, String::class.java).body!!
        val token = Regex("\"accessToken\":\"([^\"]+)\"").find(tokenBody)!!.groupValues[1]
        val form = LinkedMultiValueMap<String, Any>()
        form.add("paths", "folder/from-api.md")
        form.add(
            "files",
            object : ByteArrayResource("API 파일".toByteArray()) {
                override fun getFilename() = "from-api.md"
            },
        )
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.MULTIPART_FORM_DATA
                setBearerAuth(token)
            }
        val upload = rest.postForEntity("/api/admin/sources/${source.id}/documents", HttpEntity(form, headers), String::class.java)
        assertEquals(HttpStatus.OK, upload.statusCode)
        assertTrue(upload.body!!.contains("CREATED"))
        val hrToken =
            rest
                .postForEntity(
                    "/api/auth/login",
                    HttpEntity(
                        """{"email":"hr.staff@local.docs199","password":"local-demo-1234"}""",
                        HttpHeaders().apply {
                            contentType =
                                MediaType.APPLICATION_JSON
                        },
                    ),
                    String::class.java,
                ).body!!
        val employee = Regex("\"accessToken\":\"([^\"]+)\"").find(hrToken)!!.groupValues[1]
        val employeeHeaders = HttpHeaders().apply { setBearerAuth(employee) }
        val forbidden = rest.exchange("/api/admin/sources", HttpMethod.GET, HttpEntity<Void>(employeeHeaders), String::class.java)
        assertEquals(HttpStatus.FORBIDDEN, forbidden.statusCode)
        val visible =
            rest.exchange(
                "/api/documents?sourceId=${source.id}",
                HttpMethod.GET,
                HttpEntity<Void>(employeeHeaders),
                String::class.java,
            )
        assertEquals(HttpStatus.OK, visible.statusCode)
        assertTrue(visible.body!!.contains("from-api.md"))
    }

    @Test
    fun staleAndDuplicateCallbacksDoNotChangeLatestVersion() {
        val admin = principal("doc.admin@local.docs199")
        val source = service.createSource(admin, SourceKind.LOCAL, "콜백-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val documentId = UUID.randomUUID()
        repository.insertDocument(
            DocumentRecord(
                documentId,
                source.id,
                "callback.md",
                "callback",
                "callback.md",
                "md",
                null,
                null,
                null,
                Clearance.PUBLIC,
                null,
                0,
                null,
            ),
        )
        val firstId = UUID.randomUUID()
        val secondId = UUID.randomUUID()
        repository.addVersion(VersionRecord(firstId, documentId, 1, "test/$firstId", 1, "a".repeat(64), admin.userId, Instant.now()))
        repository.addVersion(VersionRecord(secondId, documentId, 2, "test/$secondId", 1, "b".repeat(64), admin.userId, Instant.now()))
        repository.updateVersion(documentId, 2)
        val firstJob = jobs.create(firstId)
        val secondJob = jobs.create(secondId)
        jobs.result(firstJob, JobStatus.DONE, null, DocumentType.REPORT)
        assertEquals(null, repository.document(documentId)?.documentType)
        jobs.result(secondJob, JobStatus.DONE, null, DocumentType.HANDOVER)
        val finished = repository.job(secondJob)?.finishedAt
        jobs.result(secondJob, JobStatus.PROCESSING, null, null)
        jobs.result(secondJob, JobStatus.FAILED, "늦은 실패", null)
        jobs.result(secondJob, JobStatus.DONE, null, DocumentType.APPROVAL)
        assertEquals(JobStatus.DONE, repository.job(secondJob)?.status)
        assertEquals(finished, repository.job(secondJob)?.finishedAt)
        assertEquals(DocumentType.HANDOVER, repository.document(documentId)?.documentType)
    }

    @Test
    fun newVersionClearsOldClassification() {
        val admin = principal("doc.admin@local.docs199")
        val source = service.createSource(admin, SourceKind.LOCAL, "유형-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val documentId = UUID.randomUUID()
        val firstId = UUID.randomUUID()
        val secondId = UUID.randomUUID()
        repository.insertDocument(
            DocumentRecord(documentId, source.id, "type.md", "type", "type.md", "md", null, null, null, Clearance.PUBLIC, null, 0, null),
        )
        repository.addVersion(VersionRecord(firstId, documentId, 1, "test/$firstId", 1, "d".repeat(64), admin.userId, Instant.now()))
        repository.updateVersion(documentId, 1)
        jobs.result(jobs.create(firstId), JobStatus.DONE, null, DocumentType.REPORT)
        assertEquals(DocumentType.REPORT, repository.document(documentId)?.documentType)
        repository.addVersion(VersionRecord(secondId, documentId, 2, "test/$secondId", 1, "e".repeat(64), admin.userId, Instant.now()))
        repository.updateVersion(documentId, 2)
        assertEquals(null, repository.document(documentId)?.documentType)
    }

    @Test
    fun rollbackDeletesUploadedObjectAndQueuedCleanupRetries() {
        val admin = principal("doc.admin@local.docs199")
        val source = service.createSource(admin, SourceKind.LOCAL, "롤백-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        var key = ""
        TransactionTemplate(transactions).execute { transaction ->
            val uploaded = service.uploadOne(admin, source.id, "rollback.md", file("되돌릴 내용"), null, null, null)
            key = repository.currentVersion(uploaded.documentId!!)!!.storageKey
            transaction.setRollbackOnly()
        }
        assertThrows(ApiException::class.java) { storage.download(key).use { it.readAllBytes() } }

        val queuedKey = "test/${UUID.randomUUID()}"
        storage.upload(queuedKey, "정리할 내용".byteInputStream(), "정리할 내용".toByteArray().size.toLong(), "text/plain")
        cleanupQueue.enqueue(queuedKey)
        cleanupWorker.retry()
        assertThrows(ApiException::class.java) { storage.download(queuedKey).use { it.readAllBytes() } }
    }

    @Test
    fun missingJobIsRecovered() {
        val admin = principal("doc.admin@local.docs199")
        val source = service.createSource(admin, SourceKind.LOCAL, "복구-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val documentId = UUID.randomUUID()
        val versionId = UUID.randomUUID()
        repository.insertDocument(
            DocumentRecord(
                documentId,
                source.id,
                "recovery.md",
                "recovery",
                "recovery.md",
                "md",
                null,
                null,
                null,
                Clearance.PUBLIC,
                null,
                0,
                null,
            ),
        )
        repository.addVersion(VersionRecord(versionId, documentId, 1, "test/$versionId", 1, "c".repeat(64), admin.userId, Instant.now()))
        repository.updateVersion(documentId, 1)
        assertEquals(null, repository.jobForVersion(versionId))
        ingestion.recover()
        assertTrue(repository.jobForVersion(versionId) != null)
    }
}
