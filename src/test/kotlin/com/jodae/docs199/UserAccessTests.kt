package com.jodae.docs199

import com.jodae.docs199.domain.user.Clearance
import com.jodae.docs199.domain.user.Department
import com.jodae.docs199.domain.user.DepartmentRepository
import com.jodae.docs199.domain.user.Position
import com.jodae.docs199.domain.user.Role
import com.jodae.docs199.domain.user.TokenService
import com.jodae.docs199.domain.user.User
import com.jodae.docs199.domain.user.UserRepository
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
import org.springframework.jdbc.core.JdbcTemplate
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

class DepartmentIntegrationTests : IntegrationTestBase() {
    @Autowired lateinit var rest: TestRestTemplate

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
    fun listRequiresAuthenticationAndReturnsAllDepartments() {
        val anonymous = rest.exchange("/api/departments", HttpMethod.GET, HttpEntity<Void>(HttpHeaders()), String::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, anonymous.statusCode)

        val headers = HttpHeaders().apply { setBearerAuth(token("hr.staff@local.docs199")) }
        val response = rest.exchange("/api/departments", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
        assertEquals(HttpStatus.OK, response.statusCode)
        listOf("경영지원팀", "인사팀", "개발팀", "영업팀").forEach { name ->
            assertTrue(response.body!!.contains(name))
        }
    }

    @Test
    fun confidentialAccountsExistForSalesAndHrDemoScenarios() {
        listOf("sales.manager@local.docs199" to "영업팀", "hr.manager@local.docs199" to "인사팀").forEach { (email, department) ->
            val headers = HttpHeaders().apply { setBearerAuth(token(email)) }
            val me = rest.exchange("/api/users/me", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
            assertEquals(HttpStatus.OK, me.statusCode)
            assertTrue(me.body!!.contains(department))
            assertTrue(me.body!!.contains("MANAGER"))
            assertTrue(me.body!!.contains("CONFIDENTIAL"))
        }
    }
}

class JwtPrivilegeFreshnessTests : IntegrationTestBase() {
    @Autowired lateinit var rest: TestRestTemplate

    @Autowired lateinit var departments: DepartmentRepository

    @Autowired lateinit var users: UserRepository

    @Autowired lateinit var tokens: TokenService

    @Autowired lateinit var jdbc: JdbcTemplate

    @Test
    fun existingTokenUsesCurrentRoleDepartmentPositionAndClearance() {
        val suffix = UUID.randomUUID().toString()
        val oldDepartment = departments.save(Department(name = "이전 부서 $suffix"))
        val newDepartment = departments.save(Department(name = "변경 부서 $suffix"))
        val user =
            users.save(
                User(
                    email = "jwt-freshness-$suffix@local.docs199",
                    passwordHash = "unused",
                    name = "권한 변경 테스트",
                    department = oldDepartment,
                    position = Position.DIRECTOR,
                    role = Role.SYSTEM_ADMIN,
                    clearance = Clearance.CONFIDENTIAL,
                ),
            )
        val token = tokens.issue(user).getValue("accessToken")

        jdbc.update(
            "UPDATE users SET role = ?, department_id = ?, position = ?, clearance = ? WHERE id = ?",
            Role.EMPLOYEE.name,
            newDepartment.id,
            Position.STAFF.name,
            Clearance.PUBLIC.name,
            user.id,
        )

        val headers = HttpHeaders().apply { setBearerAuth(token) }
        val current = rest.exchange("/api/dev/me", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
        assertEquals(HttpStatus.OK, current.statusCode)
        assertTrue(current.body!!.contains("\"role\":\"EMPLOYEE\""))
        assertTrue(current.body!!.contains("\"departmentId\":\"${newDepartment.id}\""))
        assertTrue(current.body!!.contains("\"position\":\"STAFF\""))
        assertTrue(current.body!!.contains("\"clearance\":\"PUBLIC\""))

        val adminRequest = rest.exchange("/api/admin/sources", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
        assertEquals(HttpStatus.FORBIDDEN, adminRequest.statusCode)
    }
}
