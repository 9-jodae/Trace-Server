package com.jodae.docs199

import com.jodae.docs199.domain.conversation.AgentChatClient
import com.jodae.docs199.domain.conversation.AgentChatRequest
import com.jodae.docs199.domain.conversation.AgentChatResponse
import com.jodae.docs199.domain.conversation.AgentSource
import com.jodae.docs199.domain.conversation.ConversationRepository
import com.jodae.docs199.domain.conversation.ConversationService
import com.jodae.docs199.domain.conversation.QuestionService
import com.jodae.docs199.domain.document.DocumentRepository
import com.jodae.docs199.domain.document.DocumentService
import com.jodae.docs199.domain.document.SourceKind
import com.jodae.docs199.domain.user.Clearance
import com.jodae.docs199.domain.user.UserRepository
import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.security.AccessPolicy
import com.jodae.docs199.global.security.UserPrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockMultipartFile
import java.util.UUID

class FakeAgentChatClient : AgentChatClient {
    var nextSources: List<AgentSource> = emptyList()
    var nextAnswer: String = "답변"
    lateinit var lastRequest: AgentChatRequest

    override fun chat(request: AgentChatRequest): AgentChatResponse {
        lastRequest = request
        return AgentChatResponse(threadId = request.threadId ?: UUID.randomUUID().toString(), answer = nextAnswer, sources = nextSources)
    }
}

class QuestionServiceTests : IntegrationTestBase() {
    @Autowired lateinit var conversations: ConversationService

    @Autowired lateinit var conversationRepository: ConversationRepository

    @Autowired lateinit var documents: DocumentRepository

    @Autowired lateinit var documentService: DocumentService

    @Autowired lateinit var policy: AccessPolicy

    @Autowired lateinit var users: UserRepository

    private fun principal(email: String): UserPrincipal {
        val user = users.findByEmail(email)!!
        return UserPrincipal(user.id, user.role, user.department.id, user.position, user.clearance)
    }

    private fun file(text: String) = MockMultipartFile("file", "memo.md", "text/markdown", text.toByteArray())

    @Test
    fun askFiltersSourcesByAccessAndStoresEvidenceChunkIds() {
        val admin = principal("doc.admin@local.docs199")
        val hr = principal("hr.staff@local.docs199")
        val dev = principal("dev.manager@local.docs199")
        val source = documentService.createSource(admin, SourceKind.LOCAL, "질문테스트-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val visibleId =
            documentService
                .uploadOne(admin, source.id, "public.md", file("공개 문서"), Clearance.PUBLIC, emptySet(), null)
                .documentId!!
        val hiddenId =
            documentService
                .uploadOne(admin, source.id, "secret.md", file("기밀 문서"), Clearance.CONFIDENTIAL, setOf(dev.departmentId), null)
                .documentId!!

        val client = FakeAgentChatClient()
        val visibleChunkId = UUID.randomUUID()
        val hiddenChunkId = UUID.randomUUID()
        client.nextAnswer = "연차는 5일까지 이월할 수 있습니다"
        client.nextSources =
            listOf(
                AgentSource(visibleChunkId.toString(), "본문1", "공개 문서", visibleId.toString()),
                AgentSource(hiddenChunkId.toString(), "본문2", "기밀 문서", hiddenId.toString()),
            )
        val service = QuestionService(conversations, conversationRepository, documents, policy, client)

        val response = service.ask(hr, null, "연차는 며칠까지 이월되나요?")

        assertEquals(1, response.sources.size)
        assertEquals(visibleChunkId.toString(), response.sources[0].id)
        assertEquals("공개 문서", response.sources[0].source)
        assertEquals(client.lastRequest.threadId, response.conversationId.toString())

        val detail = conversations.detail(hr, response.conversationId)
        assertEquals(2, detail.messages.size)
        assertEquals(setOf(visibleChunkId), detail.messages[1].evidenceChunkIds)
    }

    @Test
    fun askOnAnotherUsersConversationIsRejected() {
        val hr = principal("hr.staff@local.docs199")
        val dev = principal("dev.manager@local.docs199")
        val client = FakeAgentChatClient()
        val service = QuestionService(conversations, conversationRepository, documents, policy, client)
        val conversation = conversations.create(hr, "인사팀 전용 대화")

        assertThrows(ApiException::class.java) { service.ask(dev, conversation.id, "다른 사람 대화에 질문") }
    }

    @Test
    fun askOnExistingConversationReusesThreadId() {
        val hr = principal("hr.staff@local.docs199")
        val client = FakeAgentChatClient()
        val service = QuestionService(conversations, conversationRepository, documents, policy, client)
        val conversation = conversations.create(hr, "기존 대화")

        service.ask(hr, conversation.id, "첫 질문")
        assertEquals(conversation.id.toString(), client.lastRequest.threadId)
        assertTrue(conversations.detail(hr, conversation.id).messages.size == 2)
    }
}
