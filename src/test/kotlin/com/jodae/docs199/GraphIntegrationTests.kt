package com.jodae.docs199

import com.jodae.docs199.domain.document.DocumentService
import com.jodae.docs199.domain.document.SourceKind
import com.jodae.docs199.domain.graph.ConflictDetail
import com.jodae.docs199.domain.graph.GraphService
import com.jodae.docs199.domain.graph.RelationRequest
import com.jodae.docs199.domain.graph.RelationType
import com.jodae.docs199.domain.user.Clearance
import com.jodae.docs199.domain.user.UserRepository
import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.security.UserPrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import java.util.UUID

class GraphIntegrationTests : IntegrationTestBase() {
    @Autowired lateinit var graph: GraphService

    @Autowired lateinit var documents: DocumentService

    @Autowired lateinit var users: UserRepository

    @Autowired lateinit var rest: TestRestTemplate

    @Autowired lateinit var jdbc: JdbcTemplate

    private fun principal(email: String): UserPrincipal {
        val user = users.findByEmail(email)!!
        return UserPrincipal(user.id, user.role, user.department.id, user.position, user.clearance)
    }

    private fun token(email: String): String {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val response =
            rest.postForEntity(
                "/api/auth/login",
                HttpEntity("""{"email":"$email","password":"local-demo-1234"}""", headers),
                String::class.java,
            )
        return Regex("\"accessToken\":\"([^\"]+)\"").find(response.body!!)!!.groupValues[1]
    }

    private fun createDocument(
        principal: UserPrincipal,
        sourceId: UUID,
        name: String,
        clearance: Clearance = Clearance.PUBLIC,
        departments: Set<UUID> = emptySet(),
    ): UUID =
        documents
            .uploadOne(
                principal,
                sourceId,
                "$name.md",
                MockMultipartFile("file", "$name.md", "text/markdown", name.toByteArray()),
                clearance,
                departments,
                null,
            ).documentId!!

    @Test
    fun graphFiltersNodesAndEdgesAndReturnsNotFoundForHiddenCenter() {
        val systemAdmin = principal("system.admin@local.docs199")
        val hr = principal("hr.staff@local.docs199")
        val source = documents.createSource(systemAdmin, SourceKind.LOCAL, "graph-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val visible = createDocument(systemAdmin, source.id, "visible-${UUID.randomUUID()}")
        val hidden =
            createDocument(systemAdmin, source.id, "hidden-${UUID.randomUUID()}", Clearance.CONFIDENTIAL, setOf(systemAdmin.departmentId))
        graph.register(systemAdmin, RelationRequest(visible, hidden, RelationType.REFERENCES))

        val result = graph.graph(hr, null, null)
        assertTrue(result.nodes.any { it.id == visible })
        assertFalse(result.nodes.any { it.id == hidden })
        assertFalse(result.edges.any { it.source == visible || it.target == visible })
        val isolated = graph.graph(hr, visible, 1)
        assertEquals(listOf(visible), isolated.nodes.map { it.id })
        assertTrue(isolated.edges.isEmpty())
        assertThrows(ApiException::class.java) { graph.graph(hr, hidden, 1) }
        assertThrows(ApiException::class.java) { graph.graph(hr, visible, 0) }
        assertThrows(ApiException::class.java) { graph.graph(hr, null, 1) }
    }

    @Test
    fun graphRequiresLoginAndRelationWritesRequireAdminRole() {
        val anonymous = rest.getForEntity("/api/graph", String::class.java)
        assertEquals(401, anonymous.statusCode.value())

        val headers =
            HttpHeaders().apply {
                setBearerAuth(token("hr.staff@local.docs199"))
                contentType = MediaType.APPLICATION_JSON
            }
        val graphResponse = rest.exchange("/api/graph", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
        assertEquals(200, graphResponse.statusCode.value())

        val writeResponse =
            rest.postForEntity(
                "/api/admin/document-relations",
                HttpEntity("{}", headers),
                String::class.java,
            )
        assertEquals(403, writeResponse.statusCode.value())
    }

    @Test
    fun depthUsesUndirectedHopsAndReturnsEveryEdgeInsideSelectedNodes() {
        val systemAdmin = principal("system.admin@local.docs199")
        val source = documents.createSource(systemAdmin, SourceKind.LOCAL, "depth-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val center = createDocument(systemAdmin, source.id, "center-${UUID.randomUUID()}")
        val left = createDocument(systemAdmin, source.id, "left-${UUID.randomUUID()}")
        val right = createDocument(systemAdmin, source.id, "right-${UUID.randomUUID()}")
        val outside = createDocument(systemAdmin, source.id, "outside-${UUID.randomUUID()}")
        graph.register(systemAdmin, RelationRequest(left, center, RelationType.REFERENCES))
        graph.register(systemAdmin, RelationRequest(center, right, RelationType.REFERENCES))
        graph.register(systemAdmin, RelationRequest(left, right, RelationType.REFERENCES))
        graph.register(systemAdmin, RelationRequest(right, outside, RelationType.REFERENCES))

        val depthOne = graph.graph(systemAdmin, center, 1)
        assertEquals(setOf(center, left, right), depthOne.nodes.map { it.id }.toSet())
        assertEquals(3, depthOne.edges.size)
        assertFalse(depthOne.nodes.any { it.id == outside })
        val depthTwo = graph.graph(systemAdmin, center, 2)
        assertTrue(depthTwo.nodes.any { it.id == outside })
    }

    @Test
    fun conflictsNormalizeDirectionAndPreserveDifferentItems() {
        val systemAdmin = principal("system.admin@local.docs199")
        val source = documents.createSource(systemAdmin, SourceKind.LOCAL, "conflict-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val first = createDocument(systemAdmin, source.id, "first-${UUID.randomUUID()}")
        val second = createDocument(systemAdmin, source.id, "second-${UUID.randomUUID()}")
        val initial = RelationRequest(first, second, RelationType.CONFLICTS, ConflictDetail("item-a", "A", "B"))
        val reversed = RelationRequest(second, first, RelationType.CONFLICTS, ConflictDetail("item-a", "B", "A"))
        val original = graph.register(systemAdmin, initial)
        val repeat = graph.register(systemAdmin, reversed)
        val another =
            graph.register(
                systemAdmin,
                RelationRequest(first, second, RelationType.CONFLICTS, ConflictDetail("item-b", "C", "D")),
            )

        assertEquals(original.id, repeat.id)
        assertEquals(original.detail, repeat.detail)
        assertNotEquals(original.id, another.id)
        assertEquals(
            2,
            graph.graph(systemAdmin, null, null).edges.count {
                it.type == RelationType.CONFLICTS &&
                    setOf(it.source, it.target) == setOf(first, second)
            },
        )
    }

    @Test
    fun databaseRejectsConflictDetailsWithMissingRequiredKeys() {
        val systemAdmin = principal("system.admin@local.docs199")
        val source = documents.createSource(systemAdmin, SourceKind.LOCAL, "constraint-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val first = createDocument(systemAdmin, source.id, "constraint-a-${UUID.randomUUID()}")
        val second = createDocument(systemAdmin, source.id, "constraint-b-${UUID.randomUUID()}")
        val (left, right) = listOf(first, second).sortedBy { it.toString() }

        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update(
                """
                INSERT INTO document_relations (id, source_document_id, target_document_id, relation_type, detail)
                VALUES (?, ?, ?, 'CONFLICTS', '{"item":"연차","thisValue":"5일"}'::jsonb)
                """.trimIndent(),
                UUID.randomUUID(),
                left,
                right,
            )
        }
    }

    @Test
    fun docAdminCannotRegisterRelationToDocumentOutsideItsAccessScope() {
        val systemAdmin = principal("system.admin@local.docs199")
        val docAdmin = principal("doc.admin@local.docs199")
        val source = documents.createSource(systemAdmin, SourceKind.LOCAL, "admin-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val visible = createDocument(systemAdmin, source.id, "visible-admin-${UUID.randomUUID()}")
        val hidden =
            createDocument(
                systemAdmin,
                source.id,
                "hidden-admin-${UUID.randomUUID()}",
                Clearance.INTERNAL,
                setOf(principal("hr.staff@local.docs199").departmentId),
            )

        assertThrows(ApiException::class.java) {
            graph.register(docAdmin, RelationRequest(visible, hidden, RelationType.REFERENCES))
        }
    }
}
