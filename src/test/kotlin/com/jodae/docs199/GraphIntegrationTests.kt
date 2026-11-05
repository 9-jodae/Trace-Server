package com.jodae.docs199

import com.jodae.docs199.domain.document.DocumentService
import com.jodae.docs199.domain.document.SourceKind
import com.jodae.docs199.domain.graph.ConflictDetail
import com.jodae.docs199.domain.graph.DeclaredRelationType
import com.jodae.docs199.domain.graph.GraphService
import com.jodae.docs199.domain.graph.RelationRequest
import com.jodae.docs199.domain.graph.RelationType
import com.jodae.docs199.domain.graph.TopicDetail
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
import org.springframework.test.context.TestPropertySource
import java.util.UUID

@TestPropertySource(properties = ["app.graph.topic-edges-enabled=true"])
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

    private fun addTopic(
        documentId: UUID,
        topic: String,
        currentVersionOnly: Boolean = true,
    ) {
        val sql =
            if (currentVersionOnly) {
                """
                SELECT dv.id FROM document_versions dv
                JOIN documents d ON d.id = dv.document_id
                WHERE d.id = ? AND dv.version_number = d.current_version
                """.trimIndent()
            } else {
                "SELECT id FROM document_versions WHERE document_id = ? ORDER BY version_number LIMIT 1"
            }
        val versionId = jdbc.queryForObject(sql, UUID::class.java, documentId)!!
        jdbc.update("INSERT INTO document_topics (document_version_id, topic) VALUES (?, ?)", versionId, topic)
    }

    @Test
    fun topicEdgesAreStableAggregatedAndAccessFiltered() {
        val systemAdmin = principal("system.admin@local.docs199")
        val hr = principal("hr.staff@local.docs199")
        val devManager = principal("dev.manager@local.docs199")
        val source = documents.createSource(systemAdmin, SourceKind.LOCAL, "topics-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val first = createDocument(systemAdmin, source.id, "topic-first-${UUID.randomUUID()}")
        val second = createDocument(systemAdmin, source.id, "topic-second-${UUID.randomUUID()}")
        val hidden =
            createDocument(
                systemAdmin,
                source.id,
                "topic-hidden-${UUID.randomUUID()}",
                Clearance.CONFIDENTIAL,
                setOf(devManager.departmentId),
            )

        addTopic(first, "공부")
        addTopic(first, "전공")
        addTopic(second, "공부")
        addTopic(second, "전공")
        addTopic(second, "기밀 주제")
        addTopic(hidden, "기밀 주제")

        val hrGraph = graph.graph(hr, null, null)
        val hrTopicEdge = hrGraph.edges.single { it.type == RelationType.TOPIC }
        assertEquals(setOf(first, second), setOf(hrTopicEdge.source, hrTopicEdge.target))
        assertEquals("공부 · 전공", hrTopicEdge.label)
        assertEquals(TopicDetail(listOf("공부", "전공")), hrTopicEdge.detail)
        assertFalse(hrGraph.nodes.any { it.id == hidden })
        assertEquals(
            hrTopicEdge.id,
            graph
                .graph(hr, null, null)
                .edges
                .single { it.type == RelationType.TOPIC }
                .id,
        )
        val topicSubgraph = graph.graph(hr, first, 1)
        assertTrue(topicSubgraph.nodes.any { it.id == second })
        assertTrue(topicSubgraph.edges.any { it.type == RelationType.TOPIC && setOf(it.source, it.target) == setOf(first, second) })

        val adminGraph = graph.graph(systemAdmin, null, null)
        assertTrue(adminGraph.edges.any { it.type == RelationType.TOPIC && hidden in setOf(it.source, it.target) })
    }

    @Test
    fun topicEdgesUseCurrentDocumentVersionsOnly() {
        val systemAdmin = principal("system.admin@local.docs199")
        val source = documents.createSource(systemAdmin, SourceKind.LOCAL, "topic-version-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val revisedPath = "topic-revised-${UUID.randomUUID()}"
        val revised = createDocument(systemAdmin, source.id, revisedPath)
        val other = createDocument(systemAdmin, source.id, "topic-other-${UUID.randomUUID()}")
        addTopic(revised, "구버전 주제", currentVersionOnly = false)
        addTopic(other, "구버전 주제")

        documents.uploadOne(
            systemAdmin,
            source.id,
            "$revisedPath.md",
            MockMultipartFile("file", "$revisedPath.md", "text/markdown", "new content ${UUID.randomUUID()}".toByteArray()),
            Clearance.PUBLIC,
            emptySet(),
            null,
        )

        val noOldTopic = graph.graph(systemAdmin, null, null)
        assertFalse(
            noOldTopic.edges.any {
                it.type == RelationType.TOPIC && setOf(it.source, it.target) == setOf(revised, other)
            },
        )
    }

    @Test
    fun aiRoleCanReplaceTopicsWithoutReadingThem() {
        assertTrue(jdbc.queryForObject("SELECT has_table_privilege('ai_app', 'document_topics', 'INSERT')", Boolean::class.java)!!)
        assertTrue(jdbc.queryForObject("SELECT has_table_privilege('ai_app', 'document_topics', 'DELETE')", Boolean::class.java)!!)
        assertFalse(jdbc.queryForObject("SELECT has_table_privilege('ai_app', 'document_topics', 'SELECT')", Boolean::class.java)!!)
    }

    @Test
    private fun topicLimitTestVersion(): UUID {
        val systemAdmin = principal("system.admin@local.docs199")
        val source = documents.createSource(systemAdmin, SourceKind.LOCAL, "topic-limits-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val documentId = createDocument(systemAdmin, source.id, "topic-limits-${UUID.randomUUID()}")
        return jdbc.queryForObject(
            "SELECT id FROM document_versions WHERE document_id = ? ORDER BY version_number LIMIT 1",
            UUID::class.java,
            documentId,
        )!!
    }

    @Test
    fun topicStorageRejectsMoreThanThreeTopics() {
        val versionId = topicLimitTestVersion()
        listOf("topic-one", "topic-two", "topic-three").forEach { topic ->
            jdbc.update("INSERT INTO document_topics (document_version_id, topic) VALUES (?, ?)", versionId, topic)
        }
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update("INSERT INTO document_topics (document_version_id, topic) VALUES (?, ?)", versionId, "topic-four")
        }
    }

    @Test
    fun topicStorageRejectsOverlongValues() {
        val versionId = topicLimitTestVersion()
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update("INSERT INTO document_topics (document_version_id, topic) VALUES (?, ?)", versionId, "x".repeat(101))
        }
    }

    @Test
    fun graphFiltersNodesAndEdgesAndReturnsNotFoundForHiddenCenter() {
        val systemAdmin = principal("system.admin@local.docs199")
        val hr = principal("hr.staff@local.docs199")
        val source = documents.createSource(systemAdmin, SourceKind.LOCAL, "graph-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val visible = createDocument(systemAdmin, source.id, "visible-${UUID.randomUUID()}")
        val hidden =
            createDocument(systemAdmin, source.id, "hidden-${UUID.randomUUID()}", Clearance.CONFIDENTIAL, setOf(systemAdmin.departmentId))
        graph.register(systemAdmin, RelationRequest(visible, hidden, DeclaredRelationType.REFERENCES))

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
        graph.register(systemAdmin, RelationRequest(left, center, DeclaredRelationType.REFERENCES))
        graph.register(systemAdmin, RelationRequest(center, right, DeclaredRelationType.REFERENCES))
        graph.register(systemAdmin, RelationRequest(left, right, DeclaredRelationType.REFERENCES))
        graph.register(systemAdmin, RelationRequest(right, outside, DeclaredRelationType.REFERENCES))

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
        val initial = RelationRequest(first, second, DeclaredRelationType.CONFLICTS, ConflictDetail("item-a", "A", "B"))
        val reversed = RelationRequest(second, first, DeclaredRelationType.CONFLICTS, ConflictDetail("item-a", "B", "A"))
        val original = graph.register(systemAdmin, initial)
        val repeat = graph.register(systemAdmin, reversed)
        val another =
            graph.register(
                systemAdmin,
                RelationRequest(first, second, DeclaredRelationType.CONFLICTS, ConflictDetail("item-b", "C", "D")),
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
            graph.register(docAdmin, RelationRequest(visible, hidden, DeclaredRelationType.REFERENCES))
        }
    }
}
