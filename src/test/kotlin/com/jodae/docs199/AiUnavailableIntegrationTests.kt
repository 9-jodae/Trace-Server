package com.jodae.docs199

import com.jodae.docs199.domain.conversation.AgentChatClient
import com.jodae.docs199.domain.conversation.AgentChatRequest
import com.jodae.docs199.domain.conversation.AgentChatResponse
import com.jodae.docs199.domain.user.UserRepository
import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.error.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ContextConfiguration

// Overrides the normal mock AgentChatClient with one that always fails like a down/unreachable AI
// server, so we can prove end-to-end (through the real HTTP controller and GlobalExceptionHandler)
// that the failure surfaces as the common error envelope with AI_UNAVAILABLE/502, and that the
// user's already-committed question message is not lost or rolled back because of it.
@TestConfiguration
class FailingAgentChatClientConfig {
    @Bean
    @Primary
    fun failingAgentChatClient(): AgentChatClient =
        object : AgentChatClient {
            override fun chat(request: AgentChatRequest): AgentChatResponse = throw ApiException(ErrorCode.AI_UNAVAILABLE)
        }
}

@ContextConfiguration(classes = [FailingAgentChatClientConfig::class])
@DirtiesContext
class AiUnavailableIntegrationTests : IntegrationTestBase() {
    @Autowired lateinit var rest: TestRestTemplate

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var users: UserRepository

    private fun token(email: String): String {
        val response =
            rest.postForEntity(
                "/api/auth/login",
                HttpEntity(
                    """{"email":"$email","password":"local-demo-1234"}""",
                    HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON },
                ),
                String::class.java,
            )
        return Regex("\"accessToken\":\"([^\"]+)\"").find(response.body!!)!!.groupValues[1]
    }

    @Test
    fun aiFailureReturnsTheCommonErrorEnvelopeAndKeepsTheStoredQuestion() {
        val headers =
            HttpHeaders().apply {
                setBearerAuth(token("hr.staff@local.docs199"))
                contentType = MediaType.APPLICATION_JSON
            }
        val question = "AI 서버가 응답하지 않을 때의 질문 ${java.util.UUID.randomUUID()}"

        val response =
            rest.postForEntity(
                "/api/conversations/messages",
                HttpEntity("""{"message":"$question"}""", headers),
                String::class.java,
            )

        assertEquals(HttpStatus.BAD_GATEWAY, response.statusCode)
        assertTrue(response.body!!.contains("\"success\":false"))
        assertTrue(response.body!!.contains("\"data\":null"))
        assertTrue(response.body!!.contains("\"code\":\"AI_UNAVAILABLE\""))

        // The user's message was already committed in its own transaction before the AI call,
        // so a downstream AI failure must not roll it back or otherwise drop it.
        val stored =
            jdbc.queryForObject(
                "SELECT count(*) FROM conversation_messages WHERE content = ?",
                Int::class.java,
                question,
            )
        assertEquals(1, stored)
    }
}
