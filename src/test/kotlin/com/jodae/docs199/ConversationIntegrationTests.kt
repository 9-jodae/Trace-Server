package com.jodae.docs199

import com.jodae.docs199.domain.conversation.ConversationService
import com.jodae.docs199.domain.conversation.MessageRole
import com.jodae.docs199.domain.user.UserRepository
import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.security.UserPrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.util.UUID

class ConversationIntegrationTests : IntegrationTestBase() {
    @Autowired lateinit var service: ConversationService

    @Autowired lateinit var users: UserRepository

    @Autowired lateinit var rest: TestRestTemplate

    @Autowired lateinit var jdbc: JdbcTemplate

    private fun principal(email: String): UserPrincipal {
        val user = users.findByEmail(email)!!
        return UserPrincipal(user.id, user.role, user.department.id, user.position, user.clearance)
    }

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
    fun createListDetailAndDeleteOwnConversation() {
        val hr = principal("hr.staff@local.docs199")
        val conversation = service.create(hr, "첫 질문 요약")
        val evidenceChunkId = UUID.randomUUID()
        service.addMessage(hr, conversation.id, MessageRole.USER, "연차는 며칠인가요?")
        service.addMessage(hr, conversation.id, MessageRole.ASSISTANT, "연차는 15일입니다", setOf(evidenceChunkId))

        assertTrue(service.listMine(hr).any { it.id == conversation.id })

        val detail = service.detail(hr, conversation.id)
        assertEquals(2, detail.messages.size)
        assertEquals(MessageRole.USER, detail.messages[0].role)
        assertEquals(MessageRole.ASSISTANT, detail.messages[1].role)
        assertEquals(setOf(evidenceChunkId), detail.messages[1].evidenceChunkIds)

        service.delete(hr, conversation.id)
        assertThrows(ApiException::class.java) { service.detail(hr, conversation.id) }
    }

    @Test
    fun messageOrderIsStableWhenCreatedAtTimestampsCollide() {
        val hr = principal("hr.staff@local.docs199")
        val conversation = service.create(hr, "순서 테스트")
        val first = service.addMessage(hr, conversation.id, MessageRole.USER, "첫 번째")
        val second = service.addMessage(hr, conversation.id, MessageRole.ASSISTANT, "두 번째")
        val third = service.addMessage(hr, conversation.id, MessageRole.USER, "세 번째")
        val collided = Timestamp.from(first.createdAt)
        jdbc.update(
            "UPDATE conversation_messages SET created_at = ? WHERE id IN (?, ?, ?)",
            collided,
            first.id,
            second.id,
            third.id,
        )

        val ordered = service.detail(hr, conversation.id).messages.map { it.content }
        assertEquals(listOf("첫 번째", "두 번째", "세 번째"), ordered)
    }

    @Test
    fun conversationsAreIsolatedPerUser() {
        val hr = principal("hr.staff@local.docs199")
        val dev = principal("dev.manager@local.docs199")
        val conversation = service.create(hr, "인사팀 전용 대화")

        assertFalse(service.listMine(dev).any { it.id == conversation.id })
        assertThrows(ApiException::class.java) { service.detail(dev, conversation.id) }
        assertThrows(ApiException::class.java) { service.delete(dev, conversation.id) }
        assertThrows(ApiException::class.java) { service.addMessage(dev, conversation.id, MessageRole.USER, "다른 사람 대화에 메시지 추가") }
    }

    @Test
    fun systemAdminIsNotExemptFromOwnershipRule() {
        val hr = principal("hr.staff@local.docs199")
        val admin = principal("system.admin@local.docs199")
        val conversation = service.create(hr, "관리자도 접근 불가")

        assertThrows(ApiException::class.java) { service.detail(admin, conversation.id) }
    }

    @Test
    fun httpApiReturnsOwnConversationsAndHidesOthers() {
        val hr = principal("hr.staff@local.docs199")
        val conversation = service.create(hr, "API로 조회할 대화")
        service.addMessage(hr, conversation.id, MessageRole.USER, "질문입니다")

        val hrHeaders = HttpHeaders().apply { setBearerAuth(token("hr.staff@local.docs199")) }
        val list = rest.exchange("/api/conversations", HttpMethod.GET, HttpEntity<Void>(hrHeaders), String::class.java)
        assertEquals(HttpStatus.OK, list.statusCode)
        assertTrue(list.body!!.contains(conversation.id.toString()))

        val detail =
            rest.exchange("/api/conversations/${conversation.id}", HttpMethod.GET, HttpEntity<Void>(hrHeaders), String::class.java)
        assertEquals(HttpStatus.OK, detail.statusCode)
        assertTrue(detail.body!!.contains("질문입니다"))

        val devHeaders = HttpHeaders().apply { setBearerAuth(token("dev.manager@local.docs199")) }
        val forbidden =
            rest.exchange("/api/conversations/${conversation.id}", HttpMethod.GET, HttpEntity<Void>(devHeaders), String::class.java)
        assertEquals(HttpStatus.NOT_FOUND, forbidden.statusCode)

        val deleted =
            rest.exchange("/api/conversations/${conversation.id}", HttpMethod.DELETE, HttpEntity<Void>(hrHeaders), String::class.java)
        assertEquals(HttpStatus.OK, deleted.statusCode)
        val afterDelete =
            rest.exchange("/api/conversations/${conversation.id}", HttpMethod.GET, HttpEntity<Void>(hrHeaders), String::class.java)
        assertEquals(HttpStatus.NOT_FOUND, afterDelete.statusCode)
    }
}
