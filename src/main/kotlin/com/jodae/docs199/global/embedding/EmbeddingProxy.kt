package com.jodae.docs199.global.embedding

import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.error.ErrorCode
import com.jodae.docs199.global.response.ApiResponse
import jakarta.annotation.PreDestroy
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.filter.OncePerRequestFilter
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private const val DIMENSIONS = 1024

interface EmbeddingProvider {
    fun embed(text: String): List<Double>
}

class BedrockEmbeddingProvider(
    private val client: BedrockRuntimeClient,
    private val mapper: ObjectMapper,
    private val model: String,
) : EmbeddingProvider {
    override fun embed(text: String): List<Double> {
        val body = mapper.writeValueAsBytes(mapOf("inputText" to text, "dimensions" to DIMENSIONS, "normalize" to true))
        val request =
            InvokeModelRequest
                .builder()
                .modelId(model)
                .contentType("application/json")
                .body(SdkBytes.fromByteArray(body))
                .build()
        val embedding = mapper.readTree(client.invokeModel(request).body().asByteArray()).path("embedding")
        check(embedding.isArray && embedding.size() == DIMENSIONS)
        return (0 until embedding.size()).map { index ->
            val value = embedding[index]
            check(value.isNumber)
            value.asDouble().also { number -> check(number.isFinite()) }
        }
    }
}

class MockEmbeddingProvider : EmbeddingProvider {
    override fun embed(text: String): List<Double> {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return List(DIMENSIONS) { digest[it % digest.size].toDouble() / 128.0 }
    }
}

@Configuration
@ConditionalOnProperty(name = ["app.embedding-proxy.enabled"], havingValue = "true")
class EmbeddingConfiguration {
    @Bean
    @Profile("!local & !test")
    fun bedrockClient(
        @Value("\${app.embedding-proxy.region}") region: String,
    ): BedrockRuntimeClient =
        BedrockRuntimeClient
            .builder()
            .region(Region.of(region))
            .overrideConfiguration(
                ClientOverrideConfiguration
                    .builder()
                    .apiCallTimeout(Duration.ofSeconds(25))
                    .apiCallAttemptTimeout(Duration.ofSeconds(20))
                    .build(),
            ).build()

    @Bean
    @Profile("!local & !test")
    fun bedrockProvider(
        client: BedrockRuntimeClient,
        mapper: ObjectMapper,
        @Value("\${app.embedding-proxy.model-id}") model: String,
    ): EmbeddingProvider = BedrockEmbeddingProvider(client, mapper, model)

    @Bean
    @Profile("local | test")
    fun mockProvider(): EmbeddingProvider = MockEmbeddingProvider()

    @Bean
    fun embeddingService(provider: EmbeddingProvider): EmbeddingService = EmbeddingService(provider)
}

class EmbeddingUnavailable : RuntimeException()

class EmbeddingService(
    private val provider: EmbeddingProvider,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val executor = ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue(128))

    fun embed(texts: List<String>): List<List<Double>> {
        val start = System.nanoTime()
        val futures = mutableListOf<Future<List<Double>>>()
        try {
            texts.forEach { text -> futures.add(executor.submit<List<Double>> { provider.embed(text) }) }
            val deadline = start + TimeUnit.SECONDS.toNanos(30)
            return futures.map { it.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS) }
        } catch (ex: Exception) {
            if (ex is InterruptedException) Thread.currentThread().interrupt()
            throw EmbeddingUnavailable()
        } finally {
            futures.forEach { it.cancel(true) }
            executor.purge()
            logger.info("임베딩 요청 count={} elapsedMs={}", texts.size, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
        }
    }

    @PreDestroy
    fun close() {
        executor.shutdownNow()
    }
}

@Component
@ConditionalOnProperty(name = ["app.embedding-proxy.enabled"], havingValue = "true")
class EmbeddingSecretFilter(
    @Value("\${app.internal-secret}") private val secret: String,
    private val mapper: ObjectMapper,
) : OncePerRequestFilter() {
    init {
        require(secret.isNotBlank())
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.requestURI != "/api/internal/embeddings" || request.method != "POST"

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val supplied = request.getHeader("X-Internal-Secret")
        if (supplied == null || !MessageDigest.isEqual(secret.toByteArray(Charsets.UTF_8), supplied.toByteArray(Charsets.UTF_8))) {
            response.status = HttpStatus.UNAUTHORIZED.value()
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            mapper.writeValue(response.outputStream, ApiResponse.fail(ErrorCode.UNAUTHENTICATED.name, ErrorCode.UNAUTHENTICATED.message))
            return
        }
        filterChain.doFilter(request, response)
    }
}

data class EmbeddingRequest(
    val texts: List<String?>? = null,
)

data class EmbeddingResponse(
    val model: String,
    val dimensions: Int,
    val embeddings: List<List<Double>>,
)

@RestController
@ConditionalOnProperty(name = ["app.embedding-proxy.enabled"], havingValue = "true")
class EmbeddingController(
    private val service: EmbeddingService,
    @Value("\${app.internal-secret}") private val secret: String,
    @Value("\${app.embedding-proxy.model-id}") private val model: String,
) {
    init {
        require(secret.isNotBlank())
    }

    @PostMapping("/api/internal/embeddings")
    fun embed(
        @RequestHeader(name = "X-Internal-Secret", required = false) supplied: String?,
        @RequestBody request: EmbeddingRequest,
    ): EmbeddingResponse {
        if (supplied == null || !MessageDigest.isEqual(secret.toByteArray(Charsets.UTF_8), supplied.toByteArray(Charsets.UTF_8))) {
            throw ApiException(ErrorCode.UNAUTHENTICATED)
        }
        val texts = request.texts
        if (texts == null || texts.size !in 1..32 || texts.any { it.isNullOrBlank() || it.codePointCount(0, it.length) > 8000 }) {
            throw ApiException(ErrorCode.INVALID_INPUT)
        }
        return EmbeddingResponse(model, DIMENSIONS, service.embed(texts.filterNotNull()))
    }

    @ExceptionHandler(EmbeddingUnavailable::class)
    fun unavailable(): ResponseEntity<ApiResponse<Nothing>> =
        ResponseEntity
            .status(
                HttpStatus.SERVICE_UNAVAILABLE,
            ).body(ApiResponse.fail(ErrorCode.AI_UNAVAILABLE.name, ErrorCode.AI_UNAVAILABLE.message))
}
