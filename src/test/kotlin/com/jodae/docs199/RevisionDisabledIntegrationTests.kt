package com.jodae.docs199

import com.jodae.docs199.domain.document.DocumentRepository
import com.jodae.docs199.domain.document.DocumentService
import com.jodae.docs199.domain.document.SourceKind
import com.jodae.docs199.domain.user.Clearance
import com.jodae.docs199.domain.user.UserRepository
import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.error.ErrorCode
import com.jodae.docs199.global.security.UserPrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockMultipartFile
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["app.documents.revision.enabled=false"])
class RevisionDisabledIntegrationTests : IntegrationTestBase() {
    @Autowired lateinit var service: DocumentService

    @Autowired lateinit var repository: DocumentRepository

    @Autowired lateinit var users: UserRepository

    @Test
    fun blocksOnlyCreationOfAdditionalVersions() {
        val user = users.findByEmail("doc.admin@local.docs199")!!
        val principal = UserPrincipal(user.id, user.role, user.department.id, user.position, user.clearance)
        val source = service.createSource(principal, SourceKind.LOCAL, "revision-disabled-${UUID.randomUUID()}", null, Clearance.PUBLIC)

        fun file(text: String) = MockMultipartFile("file", "memo.md", "text/markdown", text.toByteArray())
        val created = service.uploadOne(principal, source.id, "memo.md", file("first"), null, null, null)
        assertEquals("CREATED", created.outcome)
        val documentId = created.documentId!!
        val error =
            assertThrows(ApiException::class.java) {
                service.uploadOne(principal, source.id, "memo.md", file("second"), null, null, null)
            }
        assertEquals(ErrorCode.REVISION_DISABLED, error.code)
        assertEquals(1, repository.versions(documentId).size)
        assertEquals("CREATED", service.uploadOne(principal, source.id, "new.md", file("new"), null, null, null).outcome)
    }
}
