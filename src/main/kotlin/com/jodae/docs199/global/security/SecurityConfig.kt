package com.jodae.docs199.global.security

import com.jodae.docs199.domain.user.Clearance
import com.jodae.docs199.domain.user.Position
import com.jodae.docs199.domain.user.Role
import com.jodae.docs199.domain.user.UserRepository
import com.jodae.docs199.global.error.ErrorCode
import com.jodae.docs199.global.response.ApiResponse
import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.nimbusds.jose.proc.SecurityContext
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import tools.jackson.databind.ObjectMapper
import javax.crypto.spec.SecretKeySpec

data class UserPrincipal(
    val userId: java.util.UUID,
    val role: Role,
    val departmentId: java.util.UUID,
    val position: Position,
    val clearance: Clearance,
)

interface AccessPolicy {
    fun canAccess(
        principal: UserPrincipal,
        resource: DocumentAccessRule,
    ): Boolean

    fun scope(principal: UserPrincipal): AccessScope
}

data class DocumentAccessRule(
    val clearance: Clearance,
    val departmentIds: Set<java.util.UUID>,
    val minimumPosition: Position?,
)

data class AccessScope(
    val departmentId: java.util.UUID,
    val position: Position,
    val clearance: Clearance,
    val isAdmin: Boolean,
)

class DefaultAccessPolicy : AccessPolicy {
    override fun canAccess(
        principal: UserPrincipal,
        resource: DocumentAccessRule,
    ): Boolean =
        principal.role == Role.SYSTEM_ADMIN ||
            (
                principal.clearance >= resource.clearance &&
                    (resource.departmentIds.isEmpty() || principal.departmentId in resource.departmentIds) &&
                    (resource.minimumPosition == null || principal.position >= resource.minimumPosition)
            )

    override fun scope(principal: UserPrincipal) =
        AccessScope(principal.departmentId, principal.position, principal.clearance, principal.role == Role.SYSTEM_ADMIN)
}

@Configuration
class SecurityConfig(
    @Value("\${app.jwt-secret}") private val secret: String,
    @Value("\${app.cors-origins}") private val origins: String,
    private val mapper: ObjectMapper,
    private val users: UserRepository,
) {
    @Bean
    fun accessPolicy(): AccessPolicy = DefaultAccessPolicy()

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun jwtEncoder(): JwtEncoder = NimbusJwtEncoder(ImmutableSecret<SecurityContext>(key()))

    @Bean
    fun jwtDecoder(): JwtDecoder = NimbusJwtDecoder.withSecretKey(key()).macAlgorithm(MacAlgorithm.HS256).build()

    private fun key() = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration()
        config.allowedOrigins = origins.split(',').map(String::trim).filter(String::isNotEmpty)
        config.allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        config.allowedHeaders = listOf("Authorization", "Content-Type", "X-Request-Id")
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", config)
        return source
    }

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        val converter = JwtAuthenticationConverter()
        converter.setJwtGrantedAuthoritiesConverter { jwt ->
            val userId = runCatching { java.util.UUID.fromString(jwt.subject) }.getOrNull()
            val role = userId?.let { users.findById(it).orElse(null)?.role }
            role?.let { listOf(SimpleGrantedAuthority("ROLE_${it.name}")) } ?: emptyList()
        }
        http
            .csrf { it.disable() }
            .cors(Customizer.withDefaults())
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it
                    .requestMatchers(
                        "/actuator/health",
                        "/api/health",
                        "/api/dev/token",
                        "/api/auth/login",
                        "/api/internal/ingestion-jobs/*/result",
                        "/api/internal/embeddings",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/v3/api-docs/**",
                    ).permitAll()
                    .requestMatchers("/api/admin/**")
                    .hasAnyRole("DOC_ADMIN", "SYSTEM_ADMIN")
                    .anyRequest()
                    .authenticated()
            }.oauth2ResourceServer { it.jwt { jwt -> jwt.jwtAuthenticationConverter(converter) } }
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ -> error(response, ErrorCode.UNAUTHENTICATED) }
                it.accessDeniedHandler { _, response, _ -> error(response, ErrorCode.FORBIDDEN) }
            }
        return http.build()
    }

    private fun error(
        response: HttpServletResponse,
        code: ErrorCode,
    ) {
        response.status = code.status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        mapper.writeValue(response.outputStream, ApiResponse.fail(code.name, code.message))
    }
}
