package com.jodae.docs199

import com.jodae.docs199.domain.document.DocumentRepository
import com.jodae.docs199.domain.document.DocumentService
import com.jodae.docs199.domain.document.EvidenceService
import com.jodae.docs199.domain.document.SourceKind
import com.jodae.docs199.domain.user.Clearance
import com.jodae.docs199.domain.user.UserRepository
import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.security.UserPrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import java.util.UUID

class EvidenceServiceTests : IntegrationTestBase() {
    @Autowired lateinit var service: EvidenceService

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var documentService: DocumentService

    @Autowired lateinit var repository: DocumentRepository

    @Autowired lateinit var users: UserRepository

    private fun principal(email: String): UserPrincipal {
        val user = users.findByEmail(email)!!
        return UserPrincipal(user.id, user.role, user.department.id, user.position, user.clearance)
    }

    private fun file(text: String) = MockMultipartFile("file", "memo.md", "text/markdown", text.toByteArray())

    private fun insertChunk(
        versionId: UUID,
        index: Int,
        content: String,
        charStart: Int? = null,
        charEnd: Int? = null,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO chunks(id, document_version_id, chunk_index, content, char_start, char_end) VALUES (?, ?, ?, ?, ?, ?)",
            id,
            versionId,
            index,
            content,
            charStart,
            charEnd,
        )
        return id
    }

    @Test
    fun returnsEvidenceWithAdjacentChunksAndDownloadUrlWhenAccessible() {
        val admin = principal("doc.admin@local.docs199")
        val hr = principal("hr.staff@local.docs199")
        val source = documentService.createSource(admin, SourceKind.LOCAL, "근거-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val docId = documentService.uploadOne(admin, source.id, "a.md", file("본문"), Clearance.PUBLIC, emptySet(), null).documentId!!
        val versionId = repository.currentVersion(docId)!!.id
        insertChunk(versionId, 0, "첫 번째 청크")
        val middleId = insertChunk(versionId, 1, "두 번째 청크", 10, 20)
        insertChunk(versionId, 2, "세 번째 청크")

        val evidence = service.evidence(hr, middleId)

        assertEquals(middleId, evidence.chunkId)
        assertEquals(docId, evidence.documentId)
        assertEquals(1, evidence.versionNumber)
        assertEquals("두 번째 청크", evidence.content)
        assertEquals(10, evidence.charStart)
        assertEquals(20, evidence.charEnd)
        assertEquals("첫 번째 청크", evidence.previousChunk)
        assertEquals("세 번째 청크", evidence.nextChunk)
        assertEquals(true, evidence.downloadUrl.contains("documents/"))
    }

    @Test
    fun returnsNullAdjacentChunksAtBoundaries() {
        val admin = principal("doc.admin@local.docs199")
        val source = documentService.createSource(admin, SourceKind.LOCAL, "경계-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val docId = documentService.uploadOne(admin, source.id, "b.md", file("본문"), Clearance.PUBLIC, emptySet(), null).documentId!!
        val versionId = repository.currentVersion(docId)!!.id
        val onlyChunkId = insertChunk(versionId, 0, "유일한 청크")

        val evidence = service.evidence(admin, onlyChunkId)

        assertNull(evidence.previousChunk)
        assertNull(evidence.nextChunk)
    }

    @Test
    fun deniesAccessToChunkFromInaccessibleDocument() {
        val admin = principal("doc.admin@local.docs199")
        val hr = principal("hr.staff@local.docs199")
        val dev = principal("dev.manager@local.docs199")
        val source = documentService.createSource(admin, SourceKind.LOCAL, "기밀-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val docId =
            documentService
                .uploadOne(admin, source.id, "c.md", file("기밀"), Clearance.CONFIDENTIAL, setOf(dev.departmentId), null)
                .documentId!!
        val versionId = repository.currentVersion(docId)!!.id
        val chunkId = insertChunk(versionId, 0, "기밀 내용")

        assertThrows(ApiException::class.java) { service.evidence(hr, chunkId) }
    }

    @Test
    fun deniesAccessToChunkFromSupersededVersionEvenForAdmin() {
        val admin = principal("doc.admin@local.docs199")
        val source = documentService.createSource(admin, SourceKind.LOCAL, "개정-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val docId = documentService.uploadOne(admin, source.id, "d.md", file("v1"), Clearance.PUBLIC, emptySet(), null).documentId!!
        val oldVersionId = repository.currentVersion(docId)!!.id
        val oldChunkId = insertChunk(oldVersionId, 0, "이전 버전 청크")
        documentService.uploadOne(admin, source.id, "d.md", file("v2"), null, null, null)

        assertThrows(ApiException::class.java) { service.evidence(admin, oldChunkId) }
    }

    @Test
    fun deniesAccessToChunkFromDeletedDocumentEvenForAdmin() {
        val admin = principal("doc.admin@local.docs199")
        val source = documentService.createSource(admin, SourceKind.LOCAL, "삭제-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val docId = documentService.uploadOne(admin, source.id, "e.md", file("삭제될 문서"), Clearance.PUBLIC, emptySet(), null).documentId!!
        val versionId = repository.currentVersion(docId)!!.id
        val chunkId = insertChunk(versionId, 0, "삭제될 청크")
        documentService.delete(docId)

        assertThrows(ApiException::class.java) { service.evidence(admin, chunkId) }
    }

    @Test
    fun returnsNotFoundForUnknownChunkId() {
        val admin = principal("doc.admin@local.docs199")

        assertThrows(ApiException::class.java) { service.evidence(admin, UUID.randomUUID()) }
    }
}
