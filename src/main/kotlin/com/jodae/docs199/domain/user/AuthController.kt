package com.jodae.docs199.domain.user

import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.error.ErrorCode
import com.jodae.docs199.global.response.ApiResponse
import com.jodae.docs199.global.security.CurrentUser
import com.jodae.docs199.global.security.UserPrincipal
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class LoginRequest(
    @field:Email(message = "이메일 형식이 올바르지 않습니다") val email: String,
    @field:NotBlank(message = "비밀번호를 입력하세요") val password: String,
)

data class UserResponse(
    val id: UUID,
    val email: String,
    val name: String,
    val departmentId: UUID,
    val department: String,
    val position: Position,
    val role: Role,
    val clearance: Clearance,
)

class TokenService(
    private val encoder: JwtEncoder,
) {
    fun issue(user: User): Map<String, String> {
        val now = Instant.now()
        val claims =
            JwtClaimsSet
                .builder()
                .issuer("docs199")
                .subject(user.id.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .claim("role", user.role.name)
                .claim("departmentId", user.department.id.toString())
                .claim("position", user.position.name)
                .claim("clearance", user.clearance.name)
                .build()
        val jwt = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).tokenValue
        return mapOf("accessToken" to jwt, "tokenType" to "Bearer")
    }
}

@org.springframework.context.annotation.Configuration
class TokenConfig {
    @org.springframework.context.annotation.Bean
    fun tokenService(encoder: JwtEncoder) = TokenService(encoder)
}

@RestController
class AuthController(
    private val users: UserRepository,
    private val passwords: PasswordEncoder,
    private val tokens: TokenService,
) {
    private val dummyPasswordHash = requireNotNull(passwords.encode("not-a-real-account"))

    @PostMapping("/api/auth/login")
    @SecurityRequirements
    fun login(
        @Valid @RequestBody request: LoginRequest,
    ): ApiResponse<Map<String, String>> {
        val user = users.findByEmail(request.email)
        val hash = user?.passwordHash ?: dummyPasswordHash
        if (!passwords.matches(request.password, hash) || user == null) {
            throw ApiException(ErrorCode.UNAUTHENTICATED)
        }
        return ApiResponse.ok(tokens.issue(user))
    }

    @GetMapping("/api/users/me")
    fun me(
        @CurrentUser principal: UserPrincipal,
    ): ApiResponse<UserResponse> {
        val user = users.findById(principal.userId).orElseThrow { ApiException(ErrorCode.NOT_FOUND) }
        return ApiResponse.ok(
            UserResponse(
                user.id,
                user.email,
                user.name,
                user.department.id,
                user.department.name,
                user.position,
                user.role,
                user.clearance,
            ),
        )
    }
}
