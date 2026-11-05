package com.jodae.docs199

import com.jodae.docs199.domain.document.DocumentRepository
import com.jodae.docs199.domain.document.DocumentService
import com.jodae.docs199.domain.document.JobStatus
import com.jodae.docs199.domain.document.SourceKind
import com.jodae.docs199.domain.user.Clearance
import com.jodae.docs199.domain.user.UserRepository
import com.jodae.docs199.global.security.UserPrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.TestPropertySource
import java.util.UUID

@TestPropertySource(properties = ["app.ai.mode=http", "app.ai.ingestion-mode=mock"])
class IngestionModeUploadIntegrationTests : IntegrationTestBase() {
    @Autowired lateinit var service: DocumentService

    @Autowired lateinit var users: UserRepository

    @Autowired lateinit var repository: DocumentRepository

    @Test
    fun uploadCompletesMockIngestionWhenChatUsesHttpMode() {
        val user = users.findByEmail("doc.admin@local.docs199")!!
        val principal = UserPrincipal(user.id, user.role, user.department.id, user.position, user.clearance)
        val source = service.createSource(principal, SourceKind.LOCAL, "mode-test-${UUID.randomUUID()}", null, Clearance.PUBLIC)

        val uploaded =
            service.uploadOne(
                principal,
                source.id,
                "mode-test.md",
                MockMultipartFile("file", "mode-test.md", "text/markdown", "content".toByteArray()),
                null,
                null,
                null,
            )

        assertEquals("CREATED", uploaded.outcome)
        val version = repository.versions(uploaded.documentId!!).first()
        val job = repository.jobForVersion(version.id)
        assertNotNull(job)
        val deadline = System.nanoTime() + 5_000_000_000L
        var status = job!!.status
        while (status != JobStatus.DONE && System.nanoTime() < deadline) {
            Thread.sleep(25)
            status = repository.jobForVersion(version.id)!!.status
        }
        assertEquals(JobStatus.DONE, status)
    }
}
