package com.jodae.docs199.global.security

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
    val role: String,
    val department: String,
    val position: String,
)

interface AccessPolicy {
    fun canAccess(
        principal: UserPrincipal,
        resource: Any,
    ): Boolean
}

@Configuration
class SecurityConfig(
    @Value("\${app.jwt-secret}") private val secret: String,
    @Value("\${app.cors-origins}") private val origins: String,
    private val mapper: ObjectMapper,
) {
    @Bean
    fun accessPolicy(): AccessPolicy =
        object : AccessPolicy {
            override fun canAccess(
                principal: UserPrincipal,
                resource: Any,
            ) = true
        }

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
        converter.setJwtGrantedAuthoritiesConverter { jwt -> listOf(SimpleGrantedAuthority("ROLE_${jwt.getClaimAsString("role")}")) }
        http
            .csrf { it.disable() }
            .cors(Customizer.withDefaults())
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it
                    .requestMatchers(
                        "/actuator/health",
                        "/api/dev/token",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/v3/api-docs/**",
                    ).permitAll()
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
