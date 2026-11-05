package com.jodae.docs199.domain.conversation

import com.fasterxml.jackson.annotation.JsonInclude
import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.error.ErrorCode
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration
import java.util.UUID

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AgentAccessScope(
    val departmentId: UUID,
    val position: String,
    val clearance: String,
    val isAdmin: Boolean,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AgentChatRequest(
    val message: String,
    val threadId: String?,
    val filters: SearchFilters? = null,
    val accessScope: AgentAccessScope? = null,
)

data class AgentSource(
    val id: String,
    val text: String,
    val source: String,
    val documentId: String? = null,
    val versionId: String? = null,
    val charStart: Int? = null,
    val charEnd: Int? = null,
)

data class AgentEvidence(
    val chunkId: String,
    val source: String,
    val documentId: String? = null,
    val versionId: String? = null,
    val quote: String,
    val charStart: Int? = null,
    val charEnd: Int? = null,
)

data class AgentKeyword(
    val text: String,
    val chunkId: String,
)

enum class AnswerType {
    ANSWER,
    NO_EVIDENCE,
    CLARIFICATION,
    SMALL_TALK,
}

data class AgentChatResponse(
    val threadId: String,
    val answer: String,
    val answerType: AnswerType? = null,
    val route: String? = null,
    val contextProbability: Double? = null,
    val sources: List<AgentSource> = emptyList(),
    val evidence: List<AgentEvidence> = emptyList(),
    val failedSources: List<String> = emptyList(),
    val fallbackReason: String? = null,
    val keywords: List<AgentKeyword> = emptyList(),
)

interface AgentChatClient {
    fun chat(request: AgentChatRequest): AgentChatResponse
}

@Component
@ConditionalOnProperty(name = ["app.ai.mode"], havingValue = "mock")
class MockAgentChatClient : AgentChatClient {
    override fun chat(request: AgentChatRequest): AgentChatResponse =
        AgentChatResponse(
            threadId = request.threadId ?: UUID.randomUUID().toString(),
            answer = "모의 응답입니다: ${request.message}",
            route = "SEARCH",
        )
}

@Component
@ConditionalOnProperty(name = ["app.ai.mode"], havingValue = "http")
class HttpAgentChatClient(
    @Value("\${app.ai.base-url}") baseUrl: String,
    @Value("\${app.ai.chat-timeout-seconds:70}") timeoutSeconds: Long,
    @Value("\${app.internal-secret}") private val secret: String,
) : AgentChatClient {
    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 5L
        private const val MAX_TOTAL_TIMEOUT_SECONDS = 80L
    }

    private val logger = LoggerFactory.getLogger(javaClass)

    // 연결과 응답 대기를 같은 timeoutSeconds로 두면 둘 다 최악으로 지연될 때 최대 2*timeoutSeconds까지
    // SimpleClientHttpRequestFactory의 read timeout은 수신 사이의 유휴 시간만 제한해, AI가 바이트를 조금씩
    // 계속 보내면 전체 요청이 90초를 넘을 수 있었다. JDK HttpClient 요청 제한은 본문 소비까지 포함한 전체
    // 요청 시간에 적용되므로, 설정값을 최대 80초로 제한해 외부 게이트웨이의 90초 제한보다 먼저 끝낸다.
    private val client =
        RestClient
            .builder()
            .baseUrl(baseUrl)
            .requestFactory(
                JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)).build(),
                ).apply {
                    setReadTimeout(Duration.ofSeconds(timeoutSeconds.coerceIn(1L, MAX_TOTAL_TIMEOUT_SECONDS)))
                },
            ).build()

    // 질문·답변 본문은 로그에 남기지 않는다. X-Request-Id는 RequestIdFilter가 같은 요청 스레드의
    // MDC에 넣어 둔 값을 그대로 전달해, Spring과 AI 서버 로그를 같은 요청 ID로 이어 붙일 수 있게 한다.
    override fun chat(request: AgentChatRequest): AgentChatResponse {
        val requestId = MDC.get("requestId")
        logger.info("AI 채팅 호출 시작 threadId={}", request.threadId)
        try {
            val response =
                client
                    .post()
                    .uri("/api/v1/agent/chat")
                    .header("X-Internal-Secret", secret)
                    .apply { if (!requestId.isNullOrBlank()) header("X-Request-Id", requestId) }
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(AgentChatResponse::class.java) ?: throw ApiException(ErrorCode.AI_UNAVAILABLE)
            logger.info("AI 채팅 호출 성공 threadId={}", request.threadId)
            return response
        } catch (ex: ApiException) {
            logger.warn("AI 채팅 호출 실패 threadId={}", request.threadId)
            throw ex
        } catch (ex: Exception) {
            logger.warn("AI 채팅 호출 실패 threadId={}", request.threadId)
            throw ApiException(ErrorCode.AI_UNAVAILABLE)
        }
    }
}
