package com.jodae.docs199

import com.jodae.docs199.domain.user.Clearance
import com.jodae.docs199.domain.user.Position
import com.jodae.docs199.domain.user.Role
import com.jodae.docs199.global.security.DefaultAccessPolicy
import com.jodae.docs199.global.security.DocumentAccessRule
import com.jodae.docs199.global.security.UserPrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import java.util.UUID

class AccessPolicyTests {
    private val policy = DefaultAccessPolicy()
    private val department = UUID.randomUUID()
    private val employee = UserPrincipal(UUID.randomUUID(), Role.EMPLOYEE, department, Position.SENIOR, Clearance.INTERNAL)

    @Test
    fun boundaries() {
        assertTrue(policy.canAccess(employee, DocumentAccessRule(Clearance.INTERNAL, setOf(department), Position.SENIOR)))
        assertFalse(policy.canAccess(employee, DocumentAccessRule(Clearance.CONFIDENTIAL, emptySet(), null)))
        assertFalse(policy.canAccess(employee, DocumentAccessRule(Clearance.PUBLIC, setOf(UUID.randomUUID()), null)))
        assertFalse(policy.canAccess(employee, DocumentAccessRule(Clearance.PUBLIC, emptySet(), Position.MANAGER)))
        assertTrue(
            policy.canAccess(
                employee.copy(role = Role.SYSTEM_ADMIN),
                DocumentAccessRule(Clearance.CONFIDENTIAL, setOf(UUID.randomUUID()), Position.DIRECTOR),
            ),
        )
        assertEquals(department, policy.scope(employee).departmentId)
        assertFalse(policy.scope(employee).isAdmin)
        assertTrue(policy.scope(employee.copy(role = Role.SYSTEM_ADMIN)).isAdmin)
    }
}

class LoginIntegrationTests : IntegrationTestBase() {
    @Autowired lateinit var rest: TestRestTemplate

    @Test
    fun loginAndMe() {
        val request =
            HttpEntity(
                """{"email":"hr.staff@local.docs199","password":"local-demo-1234"}""",
                HttpHeaders().apply {
                    contentType =
                        MediaType.APPLICATION_JSON
                },
            )
        val login = rest.postForEntity("/api/auth/login", request, String::class.java)
        assertEquals(HttpStatus.OK, login.statusCode)
        val token = Regex("\"accessToken\":\"([^\"]+)\"").find(login.body!!)!!.groupValues[1]
        val headers = HttpHeaders().apply { setBearerAuth(token) }
        val me = rest.exchange("/api/users/me", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
        assertEquals(HttpStatus.OK, me.statusCode)
        assertTrue(me.body!!.contains("인사팀 사원"))
        val failed =
            rest.postForEntity(
                "/api/auth/login",
                HttpEntity("""{"email":"hr.staff@local.docs199","password":"wrong"}""", request.headers),
                String::class.java,
            )
        assertEquals(HttpStatus.UNAUTHORIZED, failed.statusCode)
    }
}
