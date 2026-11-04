package com.jodae.docs199.global.ai

import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.error.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.time.Duration

interface AIClient {
    fun stream(
        path: String,
        jsonBody: String,
        emit: (String) -> Unit,
    )
}

@Component
@ConditionalOnProperty(name = ["app.ai.mode"], havingValue = "mock")
class MockAIClient : AIClient {
    override fun stream(
        path: String,
        jsonBody: String,
        emit: (String) -> Unit,
    ) {
        emit("event: message\ndata: {\"text\":\"mock response\"}\n\n")
        emit("event: done\ndata: {}\n\n")
    }
}

@Component
@ConditionalOnProperty(name = ["app.ai.mode"], havingValue = "http")
class HttpAIClient(
    @Value("\${app.ai.base-url}") baseUrl: String,
    @Value("\${app.ai.timeout-seconds}") timeoutSeconds: Long,
    @Value("\${app.ai.retry-count}") private val retryCount: Int,
) : AIClient {
    private val client =
        RestClient
            .builder()
            .baseUrl(baseUrl)
            .requestFactory(
                org.springframework.http.client.SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(Duration.ofSeconds(timeoutSeconds))
                    setReadTimeout(Duration.ofSeconds(timeoutSeconds))
                },
            ).build()

    override fun stream(
        path: String,
        jsonBody: String,
        emit: (String) -> Unit,
    ) {
        require(path.startsWith("/") && !path.startsWith("//"))
        var attempt = 0
        while (true) {
            var emitted = false
            try {
                client
                    .post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .body(jsonBody)
                    .exchange { _, response ->
                        if (response.statusCode.isError) throw ApiException(ErrorCode.AI_UNAVAILABLE)
                        response.body.bufferedReader().use { reader ->
                            val buffer = CharArray(2048)
                            while (true) {
                                val size = reader.read(buffer)
                                if (size < 0) break
                                emitted = true
                                emit(String(buffer, 0, size))
                            }
                        }
                    }
                return
            } catch (ex: ResourceAccessException) {
                if (emitted || attempt++ >= retryCount) throw ApiException(ErrorCode.AI_UNAVAILABLE)
            } catch (ex: Exception) {
                throw ApiException(ErrorCode.AI_UNAVAILABLE)
            }
        }
    }
}

@Component
class SseRelay(
    private val aiClient: AIClient,
) {
    fun relay(
        path: String,
        jsonBody: String,
    ): StreamingResponseBody =
        StreamingResponseBody { output ->
            aiClient.stream(path, jsonBody) {
                output.write(it.toByteArray(Charsets.UTF_8))
                output.flush()
            }
        }
}
