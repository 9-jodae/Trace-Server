package com.jodae.docs199

import com.jodae.docs199.domain.document.DocumentFilter
import com.jodae.docs199.domain.document.DocumentRepository
import com.jodae.docs199.domain.document.DocumentService
import com.jodae.docs199.domain.document.JobStatus
import com.jodae.docs199.domain.document.SourceKind
import com.jodae.docs199.domain.user.Clearance
import com.jodae.docs199.domain.user.Position
import com.jodae.docs199.domain.user.UserRepository
import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.security.UserPrincipal
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
import org.springframework.util.LinkedMultiValueMap
import java.net.URI
import java.util.UUID

class DocumentIntegrationTests : IntegrationTestBase() {
    @Autowired lateinit var service: DocumentService

    @Autowired lateinit var repository: DocumentRepository

    @Autowired lateinit var users: UserRepository

    @Autowired lateinit var rest: TestRestTemplate

    private fun principal(email: String): UserPrincipal {
        val user = users.findByEmail(email)!!
        return UserPrincipal(user.id, user.role, user.department.id, user.position, user.clearance)
    }

    private fun file(text: String) = MockMultipartFile("file", "memo.md", "text/markdown", text.toByteArray())

    @Test
    fun uploadVersionJobAndExclusions() {
        val admin = principal("doc.admin@local.docs199")
        val source = service.createSource(admin, SourceKind.UPLOAD, "테스트-${UUID.randomUUID()}", null, Clearance.PUBLIC)
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
        val source = service.createSource(admin, SourceKind.UPLOAD, "권한-${UUID.randomUUID()}", null, Clearance.PUBLIC)
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
        val filter = DocumentFilter(source.id, null, null, null, null, null)
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
    fun callbackRequiresSecret() {
        val body = """{"status":"DONE"}"""
        val request = HttpEntity(body, HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON })
        val noHeader = rest.postForEntity("/api/internal/ingestion-jobs/${UUID.randomUUID()}/result", request, String::class.java)
        assertEquals(HttpStatus.FORBIDDEN, noHeader.statusCode)
    }

    @Test
    fun multipartApiAndAdminRole() {
        val admin = principal("doc.admin@local.docs199")
        val source = service.createSource(admin, SourceKind.UPLOAD, "API-${UUID.randomUUID()}", null, Clearance.PUBLIC)
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
}
