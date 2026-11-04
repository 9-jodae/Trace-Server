package com.jodae.docs199

import com.jodae.docs199.domain.document.DocumentRepository
import com.jodae.docs199.domain.document.DocumentService
import com.jodae.docs199.domain.document.SourceKind
import com.jodae.docs199.domain.user.Clearance
import com.jodae.docs199.domain.user.Position
import com.jodae.docs199.domain.user.UserRepository
import com.jodae.docs199.global.security.AccessPolicy
import com.jodae.docs199.global.security.DocumentAccessRule
import com.jodae.docs199.global.security.UserPrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

class ChunksAccessTests : IntegrationTestBase() {
    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var dataSource: DataSource

    @Autowired lateinit var policy: AccessPolicy

    @Autowired lateinit var documentService: DocumentService

    @Autowired lateinit var repository: DocumentRepository

    @Autowired lateinit var users: UserRepository

    private fun principal(email: String): UserPrincipal {
        val user = users.findByEmail(email)!!
        return UserPrincipal(user.id, user.role, user.department.id, user.position, user.clearance)
    }

    private fun file(text: String) = MockMultipartFile("file", "memo.md", "text/markdown", text.toByteArray())

    private fun accessibleVersionIds(principal: UserPrincipal): Set<UUID> {
        val scope = policy.scope(principal)
        return jdbc
            .query(
                "SELECT * FROM accessible_document_versions(?, ?, ?, ?)",
                { rs, _ -> rs.getObject(1, UUID::class.java) },
                scope.departmentId,
                scope.position.name,
                scope.clearance.name,
                scope.isAdmin,
            ).toSet()
    }

    @Test
    fun accessibleDocumentVersionsMatchesKotlinAccessPolicy() {
        val admin = principal("doc.admin@local.docs199")
        val hr = principal("hr.staff@local.docs199")
        val dev = principal("dev.manager@local.docs199")
        val source = documentService.createSource(admin, SourceKind.LOCAL, "권한함수-${UUID.randomUUID()}", null, Clearance.PUBLIC)

        val publicDocId =
            documentService.uploadOne(admin, source.id, "public.md", file("공개"), Clearance.PUBLIC, emptySet(), null).documentId!!
        val restrictedDocId =
            documentService
                .uploadOne(
                    admin,
                    source.id,
                    "restricted.md",
                    file("제한"),
                    Clearance.CONFIDENTIAL,
                    setOf(dev.departmentId),
                    Position.MANAGER,
                ).documentId!!
        val deletedDocId =
            documentService.uploadOne(admin, source.id, "deleted.md", file("삭제될 문서"), Clearance.PUBLIC, emptySet(), null).documentId!!
        val deletedVersionId = repository.currentVersion(deletedDocId)!!.id
        documentService.delete(deletedDocId)

        val revisedDocId =
            documentService.uploadOne(admin, source.id, "revised.md", file("v1"), Clearance.PUBLIC, emptySet(), null).documentId!!
        val oldVersionId = repository.currentVersion(revisedDocId)!!.id
        documentService.uploadOne(admin, source.id, "revised.md", file("v2"), null, null, null)

        for (person in listOf(admin, hr, dev)) {
            val accessible = accessibleVersionIds(person)
            for (docId in listOf(publicDocId, restrictedDocId)) {
                val document = repository.document(docId)!!
                val departments = repository.departments(docId)
                val expected = policy.canAccess(person, DocumentAccessRule(document.clearance, departments, document.minimumPosition))
                val currentVersionId = repository.currentVersion(docId)!!.id
                assertEquals(expected, currentVersionId in accessible, "document=$docId principal=${person.userId}")
            }
            assertFalse(deletedVersionId in accessible, "deleted document must never be accessible (principal=${person.userId})")
            assertFalse(oldVersionId in accessible, "superseded version must never be accessible (principal=${person.userId})")
        }
    }

    @Test
    fun filterAccessibleChunkIdsOnlyReturnsCurrentlyVisibleOnes() {
        val admin = principal("doc.admin@local.docs199")
        val hr = principal("hr.staff@local.docs199")
        val dev = principal("dev.manager@local.docs199")
        val source = documentService.createSource(admin, SourceKind.LOCAL, "청크재검증-${UUID.randomUUID()}", null, Clearance.PUBLIC)

        val visibleDocId =
            documentService.uploadOne(admin, source.id, "visible.md", file("공개"), Clearance.PUBLIC, emptySet(), null).documentId!!
        val hiddenDocId =
            documentService
                .uploadOne(admin, source.id, "hidden.md", file("기밀"), Clearance.CONFIDENTIAL, setOf(dev.departmentId), null)
                .documentId!!
        val visibleVersionId = repository.currentVersion(visibleDocId)!!.id
        val hiddenVersionId = repository.currentVersion(hiddenDocId)!!.id
        val visibleChunkId = UUID.randomUUID()
        val hiddenChunkId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO chunks(id, document_version_id, chunk_index, content) VALUES (?, ?, 0, 'v')",
            visibleChunkId,
            visibleVersionId,
        )
        jdbc.update(
            "INSERT INTO chunks(id, document_version_id, chunk_index, content) VALUES (?, ?, 0, 'h')",
            hiddenChunkId,
            hiddenVersionId,
        )

        val scope = policy.scope(hr)
        val unknownId = UUID.randomUUID()
        val filtered =
            jdbc
                .query(
                    "SELECT * FROM filter_accessible_chunk_ids(?, ?, ?, ?, ?)",
                    { rs, _ -> rs.getObject(1, UUID::class.java) },
                    scope.departmentId,
                    scope.position.name,
                    scope.clearance.name,
                    scope.isAdmin,
                    arrayOf(visibleChunkId, hiddenChunkId, unknownId),
                ).toSet()

        assertEquals(setOf(visibleChunkId), filtered)
    }

    @Test
    fun aiAppCanReadWriteChunksAndCallFunctionsButNotUsersOrDdl() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("SET ROLE ai_app") }
            try {
                val chunkId = UUID.randomUUID()
                connection
                    .prepareStatement(
                        "INSERT INTO chunks(id, document_version_id, chunk_index, content) SELECT ?, id, 99, 'probe' FROM document_versions LIMIT 1",
                    ).use { ps ->
                        ps.setObject(1, chunkId)
                        ps.executeUpdate()
                    }
                connection.prepareStatement("DELETE FROM chunks WHERE id = ?").use { ps ->
                    ps.setObject(1, chunkId)
                    ps.executeUpdate()
                }
                connection.prepareStatement("SELECT * FROM accessible_chunks(?, ?, ?, ?)").use { ps ->
                    ps.setObject(1, UUID.randomUUID())
                    ps.setString(2, "STAFF")
                    ps.setString(3, "PUBLIC")
                    ps.setBoolean(4, false)
                    ps.executeQuery().close()
                }

                assertThrows(SQLException::class.java) {
                    connection.createStatement().use { it.executeQuery("SELECT 1 FROM users LIMIT 1") }
                }
                assertThrows(SQLException::class.java) {
                    connection.createStatement().use { it.execute("CREATE TABLE chunks_access_probe(id int)") }
                }
            } finally {
                connection.createStatement().use { it.execute("RESET ROLE") }
            }
        }
    }

    @Test
    fun systemAdminSeesEverythingIncludingRestrictedDocuments() {
        val admin = principal("doc.admin@local.docs199")
        val hr = principal("hr.staff@local.docs199")
        val systemAdmin = principal("system.admin@local.docs199")
        val source = documentService.createSource(admin, SourceKind.LOCAL, "관리자-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val restrictedDocId =
            documentService
                .uploadOne(admin, source.id, "admin-only.md", file("기밀"), Clearance.CONFIDENTIAL, setOf(hr.departmentId), null)
                .documentId!!
        val restrictedVersionId = repository.currentVersion(restrictedDocId)!!.id

        assertTrue(restrictedVersionId in accessibleVersionIds(systemAdmin))
    }
}
