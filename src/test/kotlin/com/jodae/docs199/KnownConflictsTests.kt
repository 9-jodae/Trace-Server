package com.jodae.docs199

import com.jodae.docs199.domain.conversation.AgentChatRequest
import com.jodae.docs199.domain.conversation.AnswerType
import com.jodae.docs199.domain.conversation.ConversationRepository
import com.jodae.docs199.domain.conversation.ConversationService
import com.jodae.docs199.domain.conversation.QuestionResponse
import com.jodae.docs199.domain.conversation.QuestionService
import com.jodae.docs199.domain.document.DocumentService
import com.jodae.docs199.domain.document.SourceKind
import com.jodae.docs199.domain.graph.ConflictDetail
import com.jodae.docs199.domain.graph.DeclaredRelationType
import com.jodae.docs199.domain.graph.GraphRepository
import com.jodae.docs199.domain.graph.GraphService
import com.jodae.docs199.domain.graph.RelationRequest
import com.jodae.docs199.domain.user.Clearance
import com.jodae.docs199.domain.user.Position
import com.jodae.docs199.domain.user.UserRepository
import com.jodae.docs199.global.security.AccessPolicy
import com.jodae.docs199.global.security.UserPrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Transactional
class KnownConflictsTests : IntegrationTestBase() {
    @Autowired lateinit var conversations: ConversationService

    @Autowired lateinit var conversationRepository: ConversationRepository

    @Autowired lateinit var documents: DocumentService

    @Autowired lateinit var graph: GraphService

    @Autowired lateinit var graphRepository: GraphRepository

    @Autowired lateinit var policy: AccessPolicy

    @Autowired lateinit var users: UserRepository

    @Autowired lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun isolateRelations() {
        jdbc.update("DELETE FROM document_relations")
    }

    private fun principal(email: String): UserPrincipal {
        val user = users.findByEmail(email)!!
        return UserPrincipal(user.id, user.role, user.department.id, user.position, user.clearance)
    }

    private fun source(admin: UserPrincipal): UUID =
        documents.createSource(admin, SourceKind.LOCAL, "known-conflicts-${UUID.randomUUID()}", null, Clearance.PUBLIC).id

    private fun document(
        admin: UserPrincipal,
        sourceId: UUID,
        label: String,
        clearance: Clearance = Clearance.PUBLIC,
        departments: Set<UUID> = emptySet(),
        minimumPosition: Position? = null,
    ): UUID {
        val path = "known-conflicts-$label-${UUID.randomUUID()}.md"
        return documents
            .uploadOne(
                admin,
                sourceId,
                path,
                MockMultipartFile("file", path, "text/markdown", label.toByteArray()),
                clearance,
                departments,
                minimumPosition,
            ).documentId!!
    }

    private fun conflict(
        admin: UserPrincipal,
        first: UUID,
        second: UUID,
        item: String,
    ): UUID =
        graph
            .register(
                admin,
                RelationRequest(first, second, DeclaredRelationType.CONFLICTS, ConflictDetail(item, "private-first", "private-second")),
            ).id

    private fun ask(
        principal: UserPrincipal,
        enabled: Boolean = true,
    ): Pair<AgentChatRequest, QuestionResponse> {
        val client =
            FakeAgentChatClient().apply {
                nextAnswer = "ordinary answer"
                nextAnswerType = AnswerType.SMALL_TALK
            }
        val service = QuestionService(conversations, conversationRepository, jdbc, policy, client, graphRepository, enabled)
        val response = service.ask(principal, null, "question")
        return client.lastRequest to response
    }

    @Test
    fun includesOnlyConflictsWhoseBothDocumentsAreVisibleAndKeepsResponseUnchanged() {
        val admin = principal("system.admin@local.docs199")
        val hr = principal("hr.staff@local.docs199")
        val other = principal("dev.manager@local.docs199")
        val sourceId = source(admin)
        val visible = document(admin, sourceId, "visible")
        val alsoVisible = document(admin, sourceId, "also-visible")
        val restrictedByClearance = document(admin, sourceId, "secret", Clearance.CONFIDENTIAL)
        val restrictedByDepartment = document(admin, sourceId, "department", Clearance.PUBLIC, setOf(other.departmentId))
        val restrictedByRank = document(admin, sourceId, "rank", Clearance.PUBLIC, minimumPosition = Position.MANAGER)
        val includedId = conflict(admin, visible, alsoVisible, "visible-item")
        val referenceId = graph.register(admin, RelationRequest(visible, alsoVisible, DeclaredRelationType.REFERENCES)).id
        val hiddenIds =
            setOf(
                conflict(admin, visible, restrictedByClearance, "clearance-item"),
                conflict(admin, visible, restrictedByDepartment, "department-item"),
                conflict(admin, visible, restrictedByRank, "rank-item"),
            )

        val (request, response) = ask(hr)
        val sent = request.knownConflicts!!
        assertEquals(listOf(includedId), sent.map { it.relationId })
        assertEquals(setOf(visible, alsoVisible), setOf(sent.single().documentId, sent.single().otherDocumentId))
        assertEquals("visible-item", sent.single().item)
        assertTrue(sent.none { it.relationId == referenceId })
        assertTrue(sent.none { it.relationId in hiddenIds })
        assertEquals("ordinary answer", response.answer)
        assertEquals(AnswerType.SMALL_TALK, response.answerType)
        assertTrue(response.sources.isEmpty())
        assertTrue(response.evidence.isEmpty())
    }

    @Test
    fun differentCallersReceiveDifferentListsForTheSameRelations() {
        val admin = principal("system.admin@local.docs199")
        val hr = principal("hr.staff@local.docs199")
        val dev = principal("dev.manager@local.docs199")
        val sourceId = source(admin)
        val shared = document(admin, sourceId, "shared")
        val hrOnly = document(admin, sourceId, "hr", Clearance.PUBLIC, setOf(hr.departmentId))
        val devOnly = document(admin, sourceId, "dev", Clearance.PUBLIC, setOf(dev.departmentId))
        val hrId = conflict(admin, shared, hrOnly, "hr-item")
        val devId = conflict(admin, shared, devOnly, "dev-item")

        assertEquals(listOf(hrId), ask(hr).first.knownConflicts!!.map { it.relationId })
        assertEquals(listOf(devId), ask(dev).first.knownConflicts!!.map { it.relationId })
        assertEquals(
            setOf(hrId, devId),
            ask(admin)
                .first.knownConflicts!!
                .map { it.relationId }
                .toSet(),
        )
    }

    @Test
    fun deletedDocumentsAreExcludedAndRevisedDocumentsUseTheirCurrentVersion() {
        val admin = principal("system.admin@local.docs199")
        val sourceId = source(admin)
        val common = document(admin, sourceId, "common")
        val deleted = document(admin, sourceId, "deleted")
        val revised = document(admin, sourceId, "revised")
        val deletedId = conflict(admin, common, deleted, "deleted-item")
        val revisedId = conflict(admin, common, revised, "revised-item")
        documents.delete(deleted)
        val oldVersion =
            jdbc.queryForObject("SELECT id FROM document_versions WHERE document_id = ?", UUID::class.java, revised)!!
        val revisedPath = jdbc.queryForObject("SELECT external_id FROM documents WHERE id = ?", String::class.java, revised)!!
        documents.uploadOne(
            admin,
            sourceId,
            revisedPath,
            MockMultipartFile("file", revisedPath, "text/markdown", "new version".toByteArray()),
            Clearance.PUBLIC,
            emptySet(),
            null,
        )
        val currentVersion =
            jdbc.queryForObject(
                "SELECT dv.id FROM document_versions dv JOIN documents d ON d.id = dv.document_id AND d.current_version = dv.version_number WHERE d.id = ?",
                UUID::class.java,
                revised,
            )!!
        assertFalse(oldVersion == currentVersion)

        val sent = ask(admin).first.knownConflicts!!
        assertEquals(listOf(revisedId), sent.map { it.relationId })
        assertTrue(sent.none { it.relationId == deletedId })
    }

    @Test
    fun limitsToTwentyInNewestFirstOrderAndBreaksTimestampTiesById() {
        val admin = principal("system.admin@local.docs199")
        val sourceId = source(admin)
        val first = document(admin, sourceId, "first")
        val second = document(admin, sourceId, "second")
        val ids = (0 until 23).map { index -> conflict(admin, first, second, "item-$index") }
        ids.forEachIndexed { index, id ->
            val day = if (index < 3) 1 else 2
            jdbc.update("UPDATE document_relations SET created_at = make_timestamptz(2030, 1, ?, 0, 0, 0) WHERE id = ?", day, id)
        }

        val sent = ask(admin).first.knownConflicts!!
        val expected = ids.drop(3).sortedByDescending(UUID::toString)
        assertEquals(20, sent.size)
        assertEquals(expected, sent.map { it.relationId })
        assertTrue(sent.all { it.item.startsWith("item-") })
    }

    @Test
    fun sendsEmptyArrayWhenNoConflictsAndOmitsFieldWhenDisabled() {
        val hr = principal("hr.staff@local.docs199")
        val enabled = ask(hr).first
        assertEquals(emptyList<Any>(), enabled.knownConflicts)

        val disabled = ask(hr, enabled = false).first
        assertNull(disabled.knownConflicts)
    }
}
