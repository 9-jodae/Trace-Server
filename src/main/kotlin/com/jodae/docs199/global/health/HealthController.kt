package com.jodae.docs199.global.health

import com.jodae.docs199.global.response.ApiResponse
import org.springframework.boot.info.BuildProperties
import org.springframework.core.env.Environment
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class HealthResponse(
    val status: String,
    val version: String,
    val commit: String,
    val buildTime: Instant,
    val profile: String,
)

@RestController
class HealthController(
    private val build: BuildProperties,
    private val environment: Environment,
) {
    @GetMapping("/api/health")
    fun health(): ApiResponse<HealthResponse> =
        ApiResponse.ok(
            HealthResponse(
                status = "UP",
                version = build.version ?: "unknown",
                commit = build.get("commit") ?: "unknown",
                buildTime = requireNotNull(build.time),
                profile = environment.activeProfiles.ifEmpty { environment.defaultProfiles }.joinToString(","),
            ),
        )
}
