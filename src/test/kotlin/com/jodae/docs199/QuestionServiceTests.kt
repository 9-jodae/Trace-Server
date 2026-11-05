package com.jodae.docs199

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.jodae.docs199.domain.conversation.AgentAccessScope
import com.jodae.docs199.domain.conversation.AgentChatClient
import com.jodae.docs199.domain.conversation.AgentChatRequest
import com.jodae.docs199.domain.conversation.AgentChatResponse
import com.jodae.docs199.domain.conversation.AgentEvidence
import com.jodae.docs199.domain.conversation.AgentSource
import com.jodae.docs199.domain.conversation.ConversationRepository
import com.jodae.docs199.domain.conversation.ConversationService
import com.jodae.docs199.domain.conversation.HttpAgentChatClient
import com.jodae.docs199.domain.conversation.MessageRole
import com.jodae.docs199.domain.conversation.QuestionService
import com.jodae.docs199.domain.conversation.SearchFilters
import com.jodae.docs199.domain.document.DocumentRepository
import com.jodae.docs199.domain.document.DocumentService
import com.jodae.docs199.domain.document.SourceKind
import com.jodae.docs199.domain.user.Clearance
import com.jodae.docs199.domain.user.UserRepository
import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.error.ErrorCode
import com.jodae.docs199.global.security.AccessPolicy
import com.jodae.docs199.global.security.UserPrincipal
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import java.net.InetSocketAddress
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.measureTimeMillis

class FakeAgentChatClient : AgentChatClient {
    var nextSources: List<AgentSource> = emptyList()
    var nextEvidence: List<AgentEvidence> = emptyList()
    var nextAnswer: String = "답변"
    var failWith: ApiException? = null
    lateinit var lastRequest: AgentChatRequest

    override fun chat(request: AgentChatRequest): AgentChatResponse {
        lastRequest = request
        failWith?.let { throw it }
        return AgentChatResponse(
            threadId = request.threadId ?: UUID.randomUUID().toString(),
            answer = nextAnswer,
            sources = nextSources,
            evidence = nextEvidence,
        )
    }
}

class AgentChatHttpTests {
    @Test
    fun sendsAccessScopeWithExactAiContractFields() {
        val received = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/agent/chat") { exchange ->
            received.set(exchange.requestBody.bufferedReader().readText())
            val reply =
                """
                {"threadId":"thread","answer":"ok","evidence":[{"chunkId":"00000000-0000-0000-0000-000000000001","source":"source","documentId":null,"versionId":null,"quote":"핵심 문구","charStart":10,"charEnd":15}]}
                """.trimIndent().toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
        try {
            val client = HttpAgentChatClient("http://127.0.0.1:${server.address.port}", 5, "test-secret")
            val departmentId = UUID.randomUUID()
            for (admin in listOf(false, true)) {
                val response =
                    client.chat(
                        AgentChatRequest("질문", "thread", accessScope = AgentAccessScope(departmentId, "MANAGER", "INTERNAL", admin)),
                    )
                assertEquals("핵심 문구", response.evidence.single().quote)
                assertEquals(null, response.evidence.single().documentId)
                val body = received.get()
                assertTrue(body.contains("\"departmentId\":\"$departmentId\""))
                assertTrue(body.contains("\"position\":\"MANAGER\""))
                assertTrue(body.contains("\"clearance\":\"INTERNAL\""))
                assertTrue(body.contains("\"isAdmin\":$admin"))
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun sendsFiltersAsJsonAndOmitsThemWhenAbsent() {
        val received = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/agent/chat") { exchange ->
            received.set(exchange.requestBody.bufferedReader().readText())
            val reply = """{"threadId":"thread","answer":"ok"}""".toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
        try {
            val client = HttpAgentChatClient("http://127.0.0.1:${server.address.port}", 5, "test-secret")
            client.chat(
                AgentChatRequest("질문", "thread", SearchFilters(extensions = listOf("pdf"), authoredFrom = LocalDate.of(2026, 1, 1))),
            )
            assertTrue(received.get().contains("\"extensions\":[\"pdf\"]"))
            assertTrue(received.get().contains("\"authoredFrom\":\"2026-01-01\""))
            client.chat(AgentChatRequest("질문", "thread"))
            assertTrue(!received.get().contains("\"filters\""))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun propagatesTheCurrentRequestIdToTheAiServer() {
        val receivedHeader = AtomicReference<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/agent/chat") { exchange ->
            receivedHeader.set(exchange.requestHeaders.getFirst("X-Request-Id"))
            val reply = """{"threadId":"thread","answer":"ok"}""".toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
        try {
            MDC.put("requestId", "test-request-id-123")
            val client = HttpAgentChatClient("http://127.0.0.1:${server.address.port}", 5, "test-secret")
            client.chat(AgentChatRequest("질문", "thread"))
            assertEquals("test-request-id-123", receivedHeader.get())
        } finally {
            MDC.remove("requestId")
            server.stop(0)
        }
    }

    @Test
    fun timesOutWithinTheConfiguredBudgetInsteadOfHanging() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/agent/chat") { exchange ->
            Thread.sleep(3000)
            exchange.sendResponseHeaders(200, -1)
        }
        server.start()
        try {
            val client = HttpAgentChatClient("http://127.0.0.1:${server.address.port}", 1, "test-secret")
            val elapsed =
                measureTimeMillis {
                    val error = assertThrows(ApiException::class.java) { client.chat(AgentChatRequest("질문", "thread")) }
                    assertEquals(ErrorCode.AI_UNAVAILABLE, error.code)
                }
            assertTrue(elapsed < 3000, "타임아웃이 설정된 1초를 크게 넘겨 요청이 걸려 있었습니다: ${elapsed}ms")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun logsAiFailureWithoutQuestionContent() {
        val logger = LoggerFactory.getLogger(HttpAgentChatClient::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/agent/chat") { exchange ->
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()
        try {
            val client = HttpAgentChatClient("http://127.0.0.1:${server.address.port}", 5, "test-secret")
            assertThrows(ApiException::class.java) { client.chat(AgentChatRequest("비밀 질문 내용입니다", "thread")) }
            val messages = appender.list.map { it.formattedMessage }
            assertTrue(messages.any { it.contains("AI 채팅 호출 실패") })
            assertTrue(messages.none { it.contains("비밀 질문 내용입니다") })
        } finally {
            logger.detachAppender(appender)
            appender.stop()
            server.stop(0)
        }
    }
}

class QuestionServiceTests : IntegrationTestBase() {
    @Autowired lateinit var rest: TestRestTemplate

    @Autowired lateinit var conversations: ConversationService

    @Autowired lateinit var conversationRepository: ConversationRepository

    @Autowired lateinit var documents: DocumentRepository

    @Autowired lateinit var documentService: DocumentService

    @Autowired lateinit var policy: AccessPolicy

    @Autowired lateinit var users: UserRepository

    @Autowired lateinit var jdbc: JdbcTemplate

    private fun principal(email: String): UserPrincipal {
        val user = users.findByEmail(email)!!
        return UserPrincipal(user.id, user.role, user.department.id, user.position, user.clearance)
    }

    private fun file(text: String) = MockMultipartFile("file", "memo.md", "text/markdown", text.toByteArray())

    private fun insertChunk(
        versionId: UUID,
        content: String,
        chunkIndex: Int = 0,
        charStart: Int? = null,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO chunks(id, document_version_id, chunk_index, content, char_start) VALUES (?, ?, ?, ?, ?)",
            id,
            versionId,
            chunkIndex,
            content,
            charStart,
        )
        return id
    }

    @Test
    fun validFiltersAreForwardedAndOmittedFiltersRemainOptional() {
        val hr = principal("hr.staff@local.docs199")
        val client = FakeAgentChatClient()
        val service = QuestionService(conversations, conversationRepository, jdbc, policy, client)
        val filters =
            SearchFilters(listOf("pdf", "md"), listOf("REPORT"), listOf("LOCAL"), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
        service.ask(hr, null, "질문", filters)
        assertEquals(filters, client.lastRequest.filters)
        service.ask(hr, null, "질문")
        assertEquals(null, client.lastRequest.filters)
    }

    @Test
    fun accessScopeIsDerivedFromThePrincipalAndSentToTheAgent() {
        val hr = principal("hr.staff@local.docs199")
        val client = FakeAgentChatClient()
        val service = QuestionService(conversations, conversationRepository, jdbc, policy, client)

        service.ask(hr, null, "질문")

        val scope = policy.scope(hr)
        val sent = client.lastRequest.accessScope!!
        assertEquals(scope.departmentId, sent.departmentId)
        assertEquals(scope.position.name, sent.position)
        assertEquals(scope.clearance.name, sent.clearance)
        assertEquals(scope.isAdmin, sent.isAdmin)
    }

    @Test
    fun invalidFilterValuesReturnBadRequestBeforeCreatingConversation() {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val login =
            rest.postForEntity(
                "/api/auth/login",
                HttpEntity("""{"email":"hr.staff@local.docs199","password":"local-demo-1234"}""", headers),
                String::class.java,
            )
        val token = Regex("\\\"accessToken\\\":\\\"([^\\\"]+)\\\"").find(login.body!!)!!.groupValues[1]
        headers.setBearerAuth(token)
        listOf(
            """{"extensions":["exe"]}""",
            """{"extensions":[]}""",
            """{"documentTypes":["UNKNOWN"]}""",
            """{"sourceKinds":["UPLOAD"]}""",
            """{"authoredFrom":"2026-12-31","authoredTo":"2026-01-01"}""",
            """{"authoredFrom":"not-a-date"}""",
        ).forEach { filters ->
            val response =
                rest.postForEntity(
                    "/api/conversations/messages",
                    HttpEntity("""{"message":"질문","filters":$filters}""", headers),
                    String::class.java,
                )
            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        }
    }

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
        val visibleChunkId = insertChunk(documents.currentVersion(visibleId)!!.id, "연차는 5일까지 이월 가능")
        val hiddenChunkId = insertChunk(documents.currentVersion(hiddenId)!!.id, "기밀 연봉 정보")

        val client = FakeAgentChatClient()
        val evidenceChunkId = insertChunk(documents.currentVersion(visibleId)!!.id, "이차 근거 문구", 1)
        client.nextAnswer = "연차는 5일까지 이월할 수 있습니다"
        client.nextSources =
            listOf(
                AgentSource(visibleChunkId.toString(), "본문1", "공개 문서", visibleId.toString()),
                AgentSource(hiddenChunkId.toString(), "본문2", "기밀 문서", hiddenId.toString()),
            )
        client.nextEvidence = listOf(AgentEvidence(evidenceChunkId.toString(), "source", quote = "이차 근거"))
        val service = QuestionService(conversations, conversationRepository, jdbc, policy, client)

        val response = service.ask(hr, null, "연차는 며칠까지 이월되나요?")

        assertEquals(1, response.sources.size)
        assertEquals(visibleChunkId.toString(), response.sources[0].id)
        assertEquals("연차는 5일까지 이월 가능", response.sources[0].text)
        assertEquals(client.lastRequest.threadId, response.conversationId.toString())

        val detail = conversations.detail(hr, response.conversationId)
        assertEquals(2, detail.messages.size)
        assertEquals(setOf(visibleChunkId, evidenceChunkId), detail.messages[1].evidenceChunkIds)
    }

    @Test
    fun rejectsSourceWhenClaimedDocumentIdDoesNotMatchTheChunksRealDocument() {
        val admin = principal("doc.admin@local.docs199")
        val hr = principal("hr.staff@local.docs199")
        val dev = principal("dev.manager@local.docs199")
        val source = documentService.createSource(admin, SourceKind.LOCAL, "위조테스트-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val visibleId =
            documentService
                .uploadOne(admin, source.id, "public2.md", file("공개 문서"), Clearance.PUBLIC, emptySet(), null)
                .documentId!!
        val hiddenId =
            documentService
                .uploadOne(admin, source.id, "secret2.md", file("기밀 문서"), Clearance.CONFIDENTIAL, setOf(dev.departmentId), null)
                .documentId!!
        // The chunk actually belongs to the hidden document's version...
        val chunkFromHiddenDocument = insertChunk(documents.currentVersion(hiddenId)!!.id, "기밀 내용 유출 시도")

        val client = FakeAgentChatClient()
        // ...but the AI response falsely claims it belongs to the visible (accessible) document.
        client.nextSources = listOf(AgentSource(chunkFromHiddenDocument.toString(), "공개인 척", "공개 문서", visibleId.toString()))
        val service = QuestionService(conversations, conversationRepository, jdbc, policy, client)

        val response = service.ask(hr, null, "질문")

        assertEquals(0, response.sources.size)
    }

    @Test
    fun resolvesMultipleAndDuplicateSourcesInOneBatchQuery() {
        val admin = principal("doc.admin@local.docs199")
        val hr = principal("hr.staff@local.docs199")
        val source = documentService.createSource(admin, SourceKind.LOCAL, "배치테스트-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val docId = documentService.uploadOne(admin, source.id, "batch.md", file("본문"), Clearance.PUBLIC, emptySet(), null).documentId!!
        val versionId = documents.currentVersion(docId)!!.id
        val firstChunkId = insertChunk(versionId, "첫 번째 청크", 0)
        val secondChunkId = insertChunk(versionId, "두 번째 청크", 1)

        val client = FakeAgentChatClient()
        client.nextSources =
            listOf(
                AgentSource(firstChunkId.toString(), "", "", docId.toString()),
                AgentSource(firstChunkId.toString(), "", "", docId.toString()),
                AgentSource(secondChunkId.toString(), "", "", docId.toString()),
            )
        val service = QuestionService(conversations, conversationRepository, jdbc, policy, client)

        val response = service.ask(hr, null, "질문")

        assertEquals(3, response.sources.size)
        assertEquals(listOf(firstChunkId, firstChunkId, secondChunkId).map(UUID::toString), response.sources.map { it.id })
    }

    @Test
    fun evidenceIsAccessCheckedAndQuoteOffsetsUseUnicodeCodePoints() {
        val admin = principal("doc.admin@local.docs199")
        val hr = principal("hr.staff@local.docs199")
        val dev = principal("dev.manager@local.docs199")
        val source = documentService.createSource(admin, SourceKind.LOCAL, "evidence-source-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val visibleDocumentId =
            documentService
                .uploadOne(admin, source.id, "quote.md", file("공개 문서"), Clearance.PUBLIC, emptySet(), null)
                .documentId!!
        val hiddenSource =
            documentService.createSource(
                admin,
                SourceKind.LOCAL,
                "hidden-source-${UUID.randomUUID()}",
                null,
                Clearance.PUBLIC,
            )
        val hiddenDocumentId =
            documentService
                .uploadOne(admin, hiddenSource.id, "secret-quote.md", file("기밀 문서"), Clearance.CONFIDENTIAL, setOf(dev.departmentId), null)
                .documentId!!

        val chunkContent = "😀 급여는 20% 할인"
        val chunkStart = 40
        val visibleChunkId = insertChunk(documents.currentVersion(visibleDocumentId)!!.id, chunkContent, charStart = chunkStart)
        val hiddenChunkId = insertChunk(documents.currentVersion(hiddenDocumentId)!!.id, "기밀 할인율은 90%", charStart = 80)
        val quote = "20% 할인"
        val quoteLocalStart = chunkContent.codePointCount(0, chunkContent.indexOf(quote))

        val client = FakeAgentChatClient()
        client.nextEvidence =
            listOf(
                AgentEvidence(
                    chunkId = visibleChunkId.toString(),
                    source = "AI가 보낸 임의 출처",
                    documentId = null,
                    versionId = null,
                    quote = quote,
                    charStart = chunkStart + quoteLocalStart,
                    charEnd = chunkStart + quoteLocalStart + quote.codePointCount(0, quote.length),
                ),
                AgentEvidence(
                    chunkId = hiddenChunkId.toString(),
                    source = "기밀",
                    documentId = visibleDocumentId.toString(),
                    versionId = null,
                    quote = "기밀 할인율은 90%",
                ),
                AgentEvidence(
                    chunkId = visibleChunkId.toString(),
                    source = "위조 인용",
                    quote = "존재하지 않는 문구",
                ),
                AgentEvidence(
                    chunkId = visibleChunkId.toString(),
                    source = "잘못된 문서 ID",
                    documentId = hiddenDocumentId.toString(),
                    quote = quote,
                ),
            )
        val service = QuestionService(conversations, conversationRepository, jdbc, policy, client)

        val response = service.ask(hr, null, "할인율은?")

        assertEquals(1, response.evidence.size)
        val evidence = response.evidence.single()
        assertEquals(visibleChunkId.toString(), evidence.chunkId)
        assertEquals("quote", evidence.source)
        assertEquals(visibleDocumentId, evidence.documentId)
        assertEquals(documents.currentVersion(visibleDocumentId)!!.id, evidence.versionId)
        assertEquals(quote, evidence.quote)
        assertEquals(chunkStart + quoteLocalStart, evidence.charStart)
        assertEquals(chunkStart + quoteLocalStart + quote.codePointCount(0, quote.length), evidence.charEnd)

        val restored = conversations.detail(hr, response.conversationId)
        assertEquals(setOf(visibleChunkId), restored.messages[1].evidenceChunkIds)
    }

    @Test
    fun questionResponseCapsEvidenceAtThreeItems() {
        val admin = principal("doc.admin@local.docs199")
        val employee = principal("hr.staff@local.docs199")
        val source = documentService.createSource(admin, SourceKind.LOCAL, "evidence-limit-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val documentId =
            documentService
                .uploadOne(admin, source.id, "limit.md", file("문서"), Clearance.PUBLIC, emptySet(), null)
                .documentId!!
        val chunkId = insertChunk(documents.currentVersion(documentId)!!.id, "A B C D", charStart = 0)
        val client = FakeAgentChatClient()
        client.nextEvidence =
            listOf(
                AgentEvidence("잘못된-id-1", "임시 출처", quote = "무효1"),
                AgentEvidence("잘못된-id-2", "임시 출처", quote = "무효2"),
                AgentEvidence("잘못된-id-3", "임시 출처", quote = "무효3"),
            ) +
            listOf("A", "B", "C", "D").map { quote ->
                AgentEvidence(chunkId.toString(), "임시 출처", quote = quote)
            }
        val service = QuestionService(conversations, conversationRepository, jdbc, policy, client)

        val response = service.ask(employee, null, "질문")

        assertEquals(listOf("A", "B", "C"), response.evidence.map { it.quote })
    }

    @Test
    fun askOnAnotherUsersConversationIsRejected() {
        val hr = principal("hr.staff@local.docs199")
        val dev = principal("dev.manager@local.docs199")
        val client = FakeAgentChatClient()
        val service = QuestionService(conversations, conversationRepository, jdbc, policy, client)
        val conversation = conversations.create(hr, "인사팀 전용 대화")

        assertThrows(ApiException::class.java) { service.ask(dev, conversation.id, "다른 사람 대화에 질문") }
    }

    @Test
    fun aiFailureDoesNotLoseTheAlreadyStoredUserMessage() {
        val hr = principal("hr.staff@local.docs199")
        val client = FakeAgentChatClient()
        client.failWith = ApiException(ErrorCode.AI_UNAVAILABLE)
        val service = QuestionService(conversations, conversationRepository, jdbc, policy, client)

        val error = assertThrows(ApiException::class.java) { service.ask(hr, null, "AI가 응답하지 않을 때 질문") }
        assertEquals(ErrorCode.AI_UNAVAILABLE, error.code)

        val conversationId = UUID.fromString(client.lastRequest.threadId)
        val detail = conversations.detail(hr, conversationId)
        assertEquals(1, detail.messages.size)
        assertEquals(MessageRole.USER, detail.messages[0].role)
        assertEquals("AI가 응답하지 않을 때 질문", detail.messages[0].content)

        // 같은 대화로 다시 질문해도 대화 자체는 깨지지 않고 계속 쓸 수 있다.
        client.failWith = null
        client.nextAnswer = "복구된 답변"
        val response = service.ask(hr, conversationId, "다시 질문")
        assertEquals("복구된 답변", response.answer)
        assertEquals(3, conversations.detail(hr, conversationId).messages.size)
    }

    @Test
    fun malformedAiEvidenceIdReturnsAiUnavailableAndKeepsOnlyTheUserMessage() {
        val hr = principal("hr.staff@local.docs199")
        val client = FakeAgentChatClient()
        client.nextEvidence = listOf(AgentEvidence("not-a-uuid", "source", quote = "quote"))
        val service = QuestionService(conversations, conversationRepository, jdbc, policy, client)

        val error = assertThrows(ApiException::class.java) { service.ask(hr, null, "질문") }
        assertEquals(ErrorCode.AI_UNAVAILABLE, error.code)

        val conversationId = UUID.fromString(client.lastRequest.threadId)
        val detail = conversations.detail(hr, conversationId)
        assertEquals(1, detail.messages.size)
        assertEquals(MessageRole.USER, detail.messages.single().role)
    }

    @Test
    fun askOnExistingConversationReusesThreadId() {
        val hr = principal("hr.staff@local.docs199")
        val client = FakeAgentChatClient()
        val service = QuestionService(conversations, conversationRepository, jdbc, policy, client)
        val conversation = conversations.create(hr, "기존 대화")

        service.ask(hr, conversation.id, "첫 질문")
        assertEquals(conversation.id.toString(), client.lastRequest.threadId)
        assertTrue(conversations.detail(hr, conversation.id).messages.size == 2)
    }

    @Test
    fun askOnAnotherUsersConversationViaHttpReturnsNotFound() {
        val hr = principal("hr.staff@local.docs199")
        val conversation = conversations.create(hr, "소유자 확인용 대화")

        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val login =
            rest.postForEntity(
                "/api/auth/login",
                HttpEntity("""{"email":"dev.manager@local.docs199","password":"local-demo-1234"}""", headers),
                String::class.java,
            )
        val token = Regex("\"accessToken\":\"([^\"]+)\"").find(login.body!!)!!.groupValues[1]
        headers.setBearerAuth(token)

        val response =
            rest.postForEntity(
                "/api/conversations/${conversation.id}/messages",
                HttpEntity("""{"message":"다른 사람 대화에 질문"}""", headers),
                String::class.java,
            )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertTrue(response.body!!.contains("\"code\":\"NOT_FOUND\""))
    }

    // Covers Issue #34's "conversation restoration" angle: evidence that was visible when the answer was
    // first stored must be re-checked against *current* access on every restore, not trusted from history.
    @Test
    fun restoringConversationHidesEvidenceForADeletedDocument() {
        val admin = principal("doc.admin@local.docs199")
        val hr = principal("hr.staff@local.docs199")
        val source = documentService.createSource(admin, SourceKind.LOCAL, "복원-삭제-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val documentId =
            documentService
                .uploadOne(admin, source.id, "restore-deleted.md", file("삭제될 문서"), Clearance.PUBLIC, emptySet(), null)
                .documentId!!
        val chunkId = insertChunk(documents.currentVersion(documentId)!!.id, "삭제 전 근거 문구")

        val conversation = conversations.create(hr, "삭제 복원 테스트")
        conversations.addMessage(hr, conversation.id, MessageRole.USER, "질문")
        conversations.addMessage(hr, conversation.id, MessageRole.ASSISTANT, "답변", setOf(chunkId))
        val beforeDelete = conversations.detail(hr, conversation.id)
        assertEquals(setOf(chunkId), beforeDelete.messages[1].evidenceChunkIds)

        documentService.delete(documentId)

        val afterDelete = conversations.detail(hr, conversation.id)
        assertEquals(listOf("질문", "답변"), afterDelete.messages.map { it.content })
        assertEquals(emptySet<UUID>(), afterDelete.messages[1].evidenceChunkIds)
    }

    @Test
    fun restoringConversationHidesEvidenceFromASupersededDocumentVersion() {
        val admin = principal("doc.admin@local.docs199")
        val hr = principal("hr.staff@local.docs199")
        val source = documentService.createSource(admin, SourceKind.LOCAL, "복원-버전-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val documentId =
            documentService
                .uploadOne(admin, source.id, "restore-version.md", file("1판 본문"), Clearance.PUBLIC, emptySet(), null)
                .documentId!!
        val firstVersionId = documents.currentVersion(documentId)!!.id
        val chunkId = insertChunk(firstVersionId, "1판 근거 문구")

        val conversation = conversations.create(hr, "버전 복원 테스트")
        conversations.addMessage(hr, conversation.id, MessageRole.ASSISTANT, "답변", setOf(chunkId))

        documentService.uploadOne(admin, source.id, "restore-version.md", file("2판 본문으로 개정"), Clearance.PUBLIC, emptySet(), null)
        assertTrue(documents.currentVersion(documentId)!!.id != firstVersionId)

        val detail = conversations.detail(hr, conversation.id)
        assertEquals(emptySet<UUID>(), detail.messages.single().evidenceChunkIds)
    }

    @Test
    fun restoringConversationHidesEvidenceAfterAccessIsRevoked() {
        val admin = principal("doc.admin@local.docs199")
        val hr = principal("hr.staff@local.docs199")
        val source = documentService.createSource(admin, SourceKind.LOCAL, "복원-권한-${UUID.randomUUID()}", null, Clearance.PUBLIC)
        val documentId =
            documentService
                .uploadOne(admin, source.id, "restore-access.md", file("공개였던 문서"), Clearance.PUBLIC, emptySet(), null)
                .documentId!!
        val chunkId = insertChunk(documents.currentVersion(documentId)!!.id, "공개였던 근거 문구")

        val conversation = conversations.create(hr, "권한 회수 복원 테스트")
        conversations.addMessage(hr, conversation.id, MessageRole.ASSISTANT, "답변", setOf(chunkId))
        val beforeRevoke = conversations.detail(hr, conversation.id)
        assertEquals(setOf(chunkId), beforeRevoke.messages.single().evidenceChunkIds)

        documentService.updateAccess(documentId, Clearance.INTERNAL, emptySet(), null)

        val detail = conversations.detail(hr, conversation.id)
        assertEquals(emptySet<UUID>(), detail.messages.single().evidenceChunkIds)
    }
}
