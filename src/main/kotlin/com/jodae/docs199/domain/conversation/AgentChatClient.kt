package com.jodae.docs199.domain.conversation

import com.fasterxml.jackson.annotation.JsonInclude
import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.error.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
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

data class AgentChatResponse(
    val threadId: String,
    val answer: String,
    val route: String? = null,
    val contextProbability: Double? = null,
    val sources: List<AgentSource> = emptyList(),
    val failedSources: List<String> = emptyList(),
    val fallbackReason: String? = null,
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
    private val client =
        RestClient
            .builder()
            .baseUrl(baseUrl)
            .requestFactory(
                org.springframework.http.client.SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(Duration.ofSeconds(timeoutSeconds))
                    setReadTimeout(Duration.ofSeconds(timeoutSeconds))
                },
            ).build()

    override fun chat(request: AgentChatRequest): AgentChatResponse =
        try {
            client
                .post()
                .uri("/api/v1/agent/chat")
                .header("X-Internal-Secret", secret)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(AgentChatResponse::class.java) ?: throw ApiException(ErrorCode.AI_UNAVAILABLE)
        } catch (ex: ApiException) {
            throw ex
        } catch (ex: Exception) {
            throw ApiException(ErrorCode.AI_UNAVAILABLE)
        }
}
