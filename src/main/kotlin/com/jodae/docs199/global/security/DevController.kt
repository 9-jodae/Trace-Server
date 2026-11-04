package com.jodae.docs199.global.security

import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.error.ErrorCode
import com.jodae.docs199.global.response.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import org.springframework.context.annotation.Profile
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@Profile("local")
@RestController
@RequestMapping("/api/dev")
class DevController(
    private val encoder: JwtEncoder,
) {
    @PostMapping("/token")
    @SecurityRequirements
    fun token(): ApiResponse<Map<String, String>> {
        val now = Instant.now()
        val claims =
            JwtClaimsSet
                .builder()
                .issuer("docs199-local")
                .subject(UUID.fromString("00000000-0000-0000-0000-000000000001").toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .claim("role", "ADMIN")
                .claim("department", "DEV")
                .claim("position", "ENGINEER")
                .build()
        val jwt = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).tokenValue
        return ApiResponse.ok(mapOf("accessToken" to jwt, "tokenType" to "Bearer"))
    }

    @GetMapping("/me")
    fun me(
        @AuthenticationPrincipal jwt: Jwt,
    ): ApiResponse<UserPrincipal> =
        ApiResponse.ok(
            UserPrincipal(
                UUID.fromString(jwt.subject ?: throw ApiException(ErrorCode.UNAUTHENTICATED)),
                jwt.getClaimAsString("role") ?: "",
                jwt.getClaimAsString("department") ?: "",
                jwt.getClaimAsString("position") ?: "",
            ),
        )

    @GetMapping("/uuid/{id}")
    fun uuid(
        @org.springframework.web.bind.annotation.PathVariable id: UUID,
    ) = ApiResponse.ok(mapOf("id" to id))
}
