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
import com.jodae.docs199.domain.document.SourceRecord
import com.jodae.docs199.domain.document.StorageCleanupQueue
import com.jodae.docs199.domain.document.StorageCleanupWorker
import com.jodae.docs199.domain.document.VersionRecord
import com.jodae.docs199.domain.user.Clearance
import com.jodae.docs199.domain.user.Position
import com.jodae.docs199.domain.user.UserRepository
import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.error.ErrorCode
import com.jodae.docs199.global.security.UserPrincipal
import com.jodae.docs199.global.storage.FileStorage
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Assertions.assertArrayEquals
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.LinkedMultiValueMap
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

class DocumentIntegrationTests : IntegrationTestBase() {
    @Autowired lateinit var service: DocumentService

    @Autowired lateinit var jdbc: JdbcTemplate

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
    fun pdfTextRevisionPreservesOriginalVersionAndRejectsStaleBase() {
        val admin = principal("doc.admin@local.docs199")
        val source = service.createSource(admin, SourceKind.LOCAL, "pdf-edit-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val pdfBytes =
            PDDocument().use { pdf ->
                val page = PDPage()
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    stream.newLineAtOffset(50f, 700f)
                    stream.showText("Original text")
                    stream.endText()
                }
                ByteArrayOutputStream().also(pdf::save).toByteArray()
            }
        val uploaded =
            service.uploadOne(
                admin,
                source.id,
                "guide.pdf",
                MockMultipartFile("file", "guide.pdf", "application/pdf", pdfBytes),
                null,
                null,
                null,
            )
        val id = uploaded.documentId!!
        val original = service.editableText(admin, id)
        assertEquals("Original text", original.text)
        assertEquals("md", original.saveExtension)

        val revised = service.reviseText(admin, id, original.versionId, "Updated text")
        assertEquals(id, revised.documentId)
        assertEquals(2, revised.version)
        assertEquals("md", service.detail(admin, id, true).document.extension)
        assertEquals("Updated text", service.editableText(admin, id).text)
        assertEquals("pdf", repository.versions(id).last().extension)
        val historical = service.download(admin, id, original.versionId, true)
        assertEquals("application/pdf", historical.contentType)
        historical.input.use { assertArrayEquals(pdfBytes, it.readBytes()) }

        val error = assertThrows(ApiException::class.java) { service.reviseText(admin, id, original.versionId, "Stale text") }
        assertEquals(ErrorCode.STALE_DOCUMENT_VERSION, error.code)
        assertEquals(2, repository.versions(id).size)

        val fileRevision =
            service.reviseFile(
                admin,
                id,
                MockMultipartFile("file", "guide.md", "text/markdown", "File revision".toByteArray()),
                null,
                null,
                null,
            )
        assertEquals(id, fileRevision.documentId)
        assertEquals(3, fileRevision.version)
    }

    @Test
    fun streamsCurrentAndHistoricalVersionsOnlyWhenAccessible() {
        val admin = principal("doc.admin@local.docs199")
        val source = service.createSource(admin, SourceKind.LOCAL, "download-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val first = service.uploadOne(admin, source.id, "memo.md", file("first-version"), null, null, null)
        val documentId = first.documentId!!
        val firstVersionId = repository.versions(documentId).first().id
        service.uploadOne(admin, source.id, "memo.md", file("current-version"), null, null, null)

        val adminLogin = rest.postForEntity("/api/dev/token", null, String::class.java)
        val adminToken = Regex("\"accessToken\":\"([^\"]+)\"").find(adminLogin.body!!)!!.groupValues[1]
        val adminHeaders = HttpHeaders().apply { setBearerAuth(adminToken) }
        val adminDownload =
            rest.exchange(
                "/api/admin/documents/$documentId/download",
                HttpMethod.GET,
                HttpEntity<Void>(adminHeaders),
                ByteArray::class.java,
            )
        assertEquals(HttpStatus.OK, adminDownload.statusCode)
        assertArrayEquals("current-version".toByteArray(), adminDownload.body)

        val login =
            rest.postForEntity(
                "/api/auth/login",
                HttpEntity(
                    """{"email":"hr.staff@local.docs199","password":"local-demo-1234"}""",
                    HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON },
                ),
                String::class.java,
            )
        val token = Regex("\"accessToken\":\"([^\"]+)\"").find(login.body!!)!!.groupValues[1]
        val headers = HttpHeaders().apply { setBearerAuth(token) }

        val current =
            rest.exchange(
                "/api/documents/$documentId/download",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                ByteArray::class.java,
            )
        assertEquals(HttpStatus.OK, current.statusCode)
        assertArrayEquals("current-version".toByteArray(), current.body)
        assertTrue(current.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION)!!.startsWith("inline"))

        val historical =
            rest.exchange(
                "/api/documents/$documentId/versions/$firstVersionId/download",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                ByteArray::class.java,
            )
        assertEquals(HttpStatus.OK, historical.statusCode)
        assertArrayEquals("first-version".toByteArray(), historical.body)

        val restrictedUpload =
            service.uploadOne(
                admin,
                source.id,
                "restricted.md",
                file("restricted"),
                Clearance.CONFIDENTIAL,
                null,
                null,
            )
        val restricted = restrictedUpload.documentId!!
        val denied =
            rest.exchange(
                "/api/documents/$restricted/download",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                ByteArray::class.java,
            )
        assertEquals(HttpStatus.NOT_FOUND, denied.statusCode)

        val anonymous = rest.getForEntity("/api/documents/$documentId/download", ByteArray::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, anonymous.statusCode)
    }

    @Test
    fun uploadVersionJobAndExclusions() {
        val admin = principal("doc.admin@local.docs199")
        val source = service.createSource(admin, SourceKind.LOCAL, "테스트-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val first =
            service.uploadOne(
                admin,
                source.id,
                "notes/memo.md",
                file("\uCCAB \uB0B4\uC6A9"),
                null,
                null,
                null,
                displayTitle = "\uCD9C\uC7A5\uBE44 \uADDC\uC815",
            )
        assertEquals("CREATED", first.outcome)
        val id = first.documentId!!
        assertEquals("\uCD9C\uC7A5\uBE44 \uADDC\uC815", repository.document(id)?.title)
        val fallback = service.uploadOne(admin, source.id, "notes/plain.md", file("\uAE30\uBCF8 \uC81C\uBAA9"), null, null, null)
        assertEquals("plain", repository.document(fallback.documentId!!)?.title)
        assertEquals(1, first.version)
        val url = service.downloadUrl(admin, id, true)
        assertEquals("/api/admin/documents/$id/download", url)
        repeat(30) {
            if (service.detail(admin, id, true).job?.status == JobStatus.DONE) return@repeat
            Thread.sleep(100)
        }
        assertEquals(JobStatus.DONE, service.detail(admin, id, true).job?.status)
        assertEquals(
            "UNCHANGED",
            service.uploadOne(admin, source.id, "notes/memo.md", file("\uCCAB \uB0B4\uC6A9"), null, null, null).outcome,
        )
        assertEquals(2, service.uploadOne(admin, source.id, "notes/memo.md", file("둘째 내용"), null, null, null).version)
        assertEquals(2, repository.versions(id).size)
        assertEquals("EXCLUDED", service.uploadOne(admin, source.id, ".obsidian/cache.md", file("x"), null, null, null).outcome)
        assertEquals("EXCLUDED", service.uploadOne(admin, source.id, "notes/a.exe", file("x"), null, null, null).outcome)
        service.delete(id)
        assertThrows(ApiException::class.java) { service.detail(admin, id, true) }
    }

    @Test
    fun currentDocumentReportsSearchableOnlyWhenCurrentVersionHasChunks() {
        val admin = principal("doc.admin@local.docs199")
        val source = service.createSource(admin, SourceKind.LOCAL, "searchable-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val documentId = UUID.randomUUID()
        val versionId = UUID.randomUUID()
        repository.insertDocument(
            DocumentRecord(
                documentId,
                source.id,
                "searchable-${UUID.randomUUID()}",
                "Searchable test",
                "searchable.md",
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
        repository.addVersion(
            VersionRecord(versionId, documentId, 1, "test/$versionId", 1, "a".repeat(64), admin.userId, Instant.now()),
        )
        repository.updateVersion(documentId, 1)
        assertFalse(service.detail(admin, documentId, true).document.searchable)

        jdbc.update(
            "INSERT INTO chunks(id, document_version_id, chunk_index, content) VALUES (?, ?, 0, ?)",
            UUID.randomUUID(),
            versionId,
            "searchable test content",
        )
        assertTrue(service.detail(admin, documentId, true).document.searchable)
    }

    @Test
    fun sourceIndependentIngestSeparatesExternalIdFromDisplayMetadata() {
        val admin = principal("doc.admin@local.docs199")
        val source = service.createSource(admin, SourceKind.OBSIDIAN, "external-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val first =
            service.upsertDocument(
                principal = admin,
                sourceId = source.id,
                externalId = "page-42",
                title = "Handover guide",
                displayPath = "Operations/Handover.md",
                extension = "md",
                bytes = "initial text".toByteArray(),
                contentType = "text/markdown",
            )
        assertEquals("CREATED", first.outcome)
        val documentId = first.documentId!!
        assertEquals("page-42", repository.document(documentId)?.externalId)
        assertEquals("Handover guide", repository.document(documentId)?.title)
        assertEquals("Operations/Handover.md", repository.document(documentId)?.relativePath)

        val revision =
            service.upsertDocument(
                principal = admin,
                sourceId = source.id,
                externalId = "page-42",
                title = "Revised handover guide",
                displayPath = "Operations/Handover guide.md",
                extension = "md",
                bytes = "revised text".toByteArray(),
                contentType = "text/markdown",
            )
        assertEquals(documentId, revision.documentId)
        assertEquals(2, revision.version)
        assertEquals("Revised handover guide", repository.document(documentId)?.title)
        assertEquals("Operations/Handover guide.md", repository.document(documentId)?.relativePath)

        val differentExternalId =
            service.upsertDocument(
                principal = admin,
                sourceId = source.id,
                externalId = "page-43",
                title = "Different document with same filename",
                displayPath = "Operations/Handover guide.md",
                extension = "md",
                bytes = "initial text".toByteArray(),
                contentType = "text/markdown",
            )
        assertEquals("CREATED", differentExternalId.outcome)
        assertTrue(differentExternalId.documentId != documentId)
    }

    @Test
    fun multipartAdapterRejectsNotionSources() {
        val admin = principal("doc.admin@local.docs199")
        val source = SourceRecord(UUID.randomUUID(), SourceKind.NOTION, "notion-${UUID.randomUUID()}", null, Clearance.PUBLIC, admin.userId)
        repository.createSource(source)

        assertThrows(ApiException::class.java) {
            service.uploadOne(admin, source.id, "page.md", file("content"), null, null, null)
        }
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
    fun uploadAndReviseAcceptAuthorMetadata() {
        val admin = principal("doc.admin@local.docs199")
        val source = service.createSource(admin, SourceKind.LOCAL, "작성자-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val modified = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.MICROS)
        val id =
            service
                .uploadOne(
                    admin,
                    source.id,
                    "author.md",
                    file("내용"),
                    null,
                    null,
                    null,
                    null,
                    "홍길동",
                    LocalDate.of(2026, 1, 1),
                    modified,
                ).documentId!!
        assertEquals("홍길동", repository.document(id)?.author)
        assertEquals(LocalDate.of(2026, 1, 1), repository.document(id)?.authoredAt)
        assertEquals(modified, repository.document(id)?.sourceModifiedAt)

        service.uploadOne(admin, source.id, "author.md", file("개정 내용"), null, null, null, null, "김철수", null, null)
        assertEquals("김철수", repository.document(id)?.author)
        assertEquals(LocalDate.of(2026, 1, 1), repository.document(id)?.authoredAt)
    }

    @Test
    fun unchangedUploadUpdatesOnlyProvidedMetadataWithoutCreatingVersionOrJob() {
        val admin = principal("doc.admin@local.docs199")
        val source = service.createSource(admin, SourceKind.LOCAL, "메타데이터-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val originalDate = LocalDate.of(2026, 1, 1)
        val originalModified = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.MICROS)
        val content = "같은 파일 내용"
        val documentId =
            service
                .uploadOne(
                    admin,
                    source.id,
                    "metadata.md",
                    file(content),
                    null,
                    null,
                    null,
                    author = "작성자 A",
                    authoredAt = originalDate,
                    sourceModifiedAt = originalModified,
                    displayTitle = "원래 제목",
                ).documentId!!

        repeat(30) {
            if (service.detail(admin, documentId, true).job?.status != JobStatus.DONE) Thread.sleep(100)
        }
        val initialDetail = service.detail(admin, documentId, true)
        val initialJobId = initialDetail.job!!.id

        val result =
            service.uploadOne(
                admin,
                source.id,
                "metadata.md",
                file(content),
                null,
                null,
                null,
                author = "작성자 B",
                authoredAt = LocalDate.of(2026, 2, 2),
                displayTitle = "수정된 제목",
            )

        assertEquals("UNCHANGED", result.outcome)
        assertEquals(1, repository.document(documentId)?.currentVersion)
        assertEquals(1, repository.versions(documentId).size)
        assertEquals("작성자 B", repository.document(documentId)?.author)
        assertEquals(LocalDate.of(2026, 2, 2), repository.document(documentId)?.authoredAt)
        assertEquals(originalModified, repository.document(documentId)?.sourceModifiedAt)
        assertEquals("수정된 제목", repository.document(documentId)?.title)
        assertEquals(initialJobId, service.detail(admin, documentId, true).job?.id)

        val noMetadataUpdate = service.uploadOne(admin, source.id, "metadata.md", file(content), null, null, null)
        assertEquals("UNCHANGED", noMetadataUpdate.outcome)
        assertEquals("작성자 B", repository.document(documentId)?.author)
        assertEquals(LocalDate.of(2026, 2, 2), repository.document(documentId)?.authoredAt)
        assertEquals(originalModified, repository.document(documentId)?.sourceModifiedAt)
        assertEquals("수정된 제목", repository.document(documentId)?.title)
        assertEquals(initialJobId, service.detail(admin, documentId, true).job?.id)
    }

    @Test
    fun aiCallbackFillsMissingAuthorButNeverOverwritesUserInput() {
        val admin = principal("doc.admin@local.docs199")
        val source = service.createSource(admin, SourceKind.LOCAL, "콜백메타-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val documentId = UUID.randomUUID()
        repository.insertDocument(
            DocumentRecord(documentId, source.id, "meta.md", "meta", "meta.md", "md", null, null, null, Clearance.PUBLIC, null, 0, null),
        )
        val versionId = UUID.randomUUID()
        repository.addVersion(VersionRecord(versionId, documentId, 1, "test/$versionId", 1, "f".repeat(64), admin.userId, Instant.now()))
        repository.updateVersion(documentId, 1)
        val job = jobs.create(versionId)
        jobs.result(job, JobStatus.DONE, null, null, "AI 추출 이름", LocalDate.of(2025, 5, 5))
        assertEquals("AI 추출 이름", repository.document(documentId)?.author)
        assertEquals(LocalDate.of(2025, 5, 5), repository.document(documentId)?.authoredAt)

        repository.updateMetadata(documentId, "사용자 입력", null, null)
        val secondVersionId = UUID.randomUUID()
        repository.addVersion(
            VersionRecord(secondVersionId, documentId, 2, "test/$secondVersionId", 1, "g".repeat(64), admin.userId, Instant.now()),
        )
        repository.updateVersion(documentId, 2)
        val secondJob = jobs.create(secondVersionId)
        jobs.result(secondJob, JobStatus.DONE, null, null, "AI가 다시 추출한 이름", LocalDate.of(2030, 1, 1))
        assertEquals("사용자 입력", repository.document(documentId)?.author)
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
        form.add("titles", "API 표시 제목")
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
        val documentId = Regex("\"documentId\":\"([^\"]+)\"").find(upload.body!!)!!.groupValues[1]
        listOf(listOf("one", "two"), listOf(" ")).forEach { invalidTitles ->
            val invalidForm = LinkedMultiValueMap<String, Any>()
            invalidForm.add("paths", "folder/invalid.md")
            invalidTitles.forEach { invalidForm.add("titles", it) }
            invalidForm.add(
                "files",
                object : ByteArrayResource("content".toByteArray()) {
                    override fun getFilename() = "invalid.md"
                },
            )
            val invalidUpload =
                rest.postForEntity("/api/admin/sources/${source.id}/documents", HttpEntity(invalidForm, headers), String::class.java)
            assertEquals(HttpStatus.BAD_REQUEST, invalidUpload.statusCode)
        }
        val readHeaders = HttpHeaders().apply { setBearerAuth(token) }
        val detail =
            rest.exchange(
                "/api/admin/documents/$documentId",
                HttpMethod.GET,
                HttpEntity<Void>(readHeaders),
                String::class.java,
            )
        assertEquals(HttpStatus.OK, detail.statusCode)
        assertTrue(detail.body!!.contains("API 표시 제목"))
        val adminList =
            rest.exchange(
                "/api/admin/documents?sourceId=${source.id}",
                HttpMethod.GET,
                HttpEntity<Void>(readHeaders),
                String::class.java,
            )
        assertTrue(adminList.body!!.contains("API 표시 제목"))
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
    fun multipartRequestCanContainMultipleFilesAboveTheFormerAggregateLimit() {
        val admin = principal("doc.admin@local.docs199")
        val source = service.createSource(admin, SourceKind.LOCAL, "batch-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val tokenBody = rest.postForEntity("/api/dev/token", null, String::class.java).body!!
        val token = Regex("\"accessToken\":\"([^\"]+)\"").find(tokenBody)!!.groupValues[1]
        val form = LinkedMultiValueMap<String, Any>()
        form.add("paths", "first.md")
        form.add("paths", "second.md")
        val content = ByteArray(11 * 1024 * 1024) { 'x'.code.toByte() }
        repeat(2) { index ->
            form.add(
                "files",
                object : ByteArrayResource(content) {
                    override fun getFilename() = "file-$index.md"
                },
            )
        }
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.MULTIPART_FORM_DATA
                setBearerAuth(token)
            }

        val upload = rest.postForEntity("/api/admin/sources/${source.id}/documents", HttpEntity(form, headers), String::class.java)

        assertEquals(HttpStatus.OK, upload.statusCode)
        assertEquals(2, Regex("\"outcome\":\"CREATED\"").findAll(upload.body!!).count())
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
