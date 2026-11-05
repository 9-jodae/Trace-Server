package com.jodae.docs199

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode

@TestPropertySource(properties = ["app.embedding-proxy.enabled=true", "app.internal-secret=test-secret"])
class EmbeddingProxyTests : IntegrationTestBase() {
    @Autowired lateinit var rest: TestRestTemplate

    private fun post(
        texts: List<String>,
        secret: String? = "test-secret",
    ) = rest.postForEntity(
        "/api/internal/embeddings",
        HttpEntity(mapOf("texts" to texts), HttpHeaders().apply { if (secret != null) set("X-Internal-Secret", secret) }),
        JsonNode::class.java,
    )

    @Test
    fun `secret required even for malformed or authenticated requests`() {
        assertEquals(HttpStatus.UNAUTHORIZED, post(listOf("hello"), null).statusCode)
        assertEquals(HttpStatus.UNAUTHORIZED, post(listOf("hello"), "wrong").statusCode)
        assertEquals(HttpStatus.UNAUTHORIZED, post(emptyList(), null).statusCode)
    }

    @Test
    fun `rejects invalid text count and length`() {
        listOf(emptyList(), List(33) { "x" }, listOf(""), listOf(" "), listOf("x".repeat(8001))).forEach {
            assertEquals(HttpStatus.BAD_REQUEST, post(it).statusCode)
        }
        assertEquals(HttpStatus.OK, post(listOf("x".repeat(8000))).statusCode)
    }

    @Test
    fun `mock returns deterministic vectors in input order`() {
        val first = post(listOf("first", "second")).body!!
        val reverse = post(listOf("second", "first")).body!!
        assertEquals("amazon.titan-embed-text-v2:0", first["model"].asText())
        assertEquals(1024, first["dimensions"].asInt())
        assertEquals(2, first["embeddings"].size())
        assertEquals(1024, first["embeddings"][0].size())
        assertEquals(1024, first["embeddings"][1].size())
        assertEquals(first["embeddings"][0], reverse["embeddings"][1])
        assertNotEquals(first["embeddings"][0], first["embeddings"][1])
    }
}

@TestPropertySource(properties = ["app.embedding-proxy.enabled=false"])
class DisabledEmbeddingProxyTests : IntegrationTestBase() {
    @Autowired lateinit var rest: TestRestTemplate

    @Test
    fun `disabled endpoint is not found`() {
        val response = rest.postForEntity("/api/internal/embeddings", mapOf("texts" to listOf("hello")), JsonNode::class.java)
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }
}
