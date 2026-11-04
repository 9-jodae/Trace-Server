package com.jodae.docs199

import com.jodae.docs199.global.ai.AIClient
import com.jodae.docs199.global.storage.FileStorage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.time.Duration
import java.util.UUID

class FoundationIntegrationTests : IntegrationTestBase() {
    @Autowired lateinit var rest: TestRestTemplate

    @Autowired lateinit var storage: FileStorage

    @Autowired lateinit var ai: AIClient

    @Test
    fun contextAndHealth() {
        val response = rest.getForEntity("/actuator/health", String::class.java)
        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body!!.contains("UP"))
        val api = rest.getForEntity("/api/health", String::class.java)
        assertEquals(HttpStatus.OK, api.statusCode)
        listOf("status", "version", "commit", "buildTime", "profile").forEach { field ->
            assertTrue(api.body!!.contains("\"$field\""))
        }
    }

    @Test
    fun authenticationAndErrors() {
        assertEquals(HttpStatus.UNAUTHORIZED, rest.getForEntity("/api/dev/me", String::class.java).statusCode)
        val token = rest.postForEntity("/api/dev/token", null, String::class.java).body!!
        val value = Regex("\"accessToken\":\"([^\"]+)\"").find(token)!!.groupValues[1]
        val headers = HttpHeaders().apply { setBearerAuth(value) }
        val me = rest.exchange("/api/dev/me", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
        assertEquals(HttpStatus.OK, me.statusCode)
        assertTrue(me.body!!.contains("SYSTEM_ADMIN"))
        val missing = rest.exchange("/missing-route", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
        assertEquals(HttpStatus.NOT_FOUND, missing.statusCode)
        assertTrue(missing.body!!.contains("NOT_FOUND"))
    }

    @Test
    fun storageRoundTrip() {
        val key = "test/${UUID.randomUUID()}"
        val content = "hello storage".toByteArray()
        storage.upload(key, content.inputStream(), content.size.toLong(), "text/plain")
        assertEquals("hello storage", storage.download(key).use { it.readAllBytes().decodeToString() })
        assertTrue(storage.presignedDownloadUrl(key, Duration.ofMinutes(1)).contains(key))
        storage.delete(key)
    }

    @Test
    fun mockAiStream() {
        val chunks = mutableListOf<String>()
        ai.stream("/sample", "{}", chunks::add)
        assertTrue(chunks.joinToString("").contains("모의 응답"))
        assertTrue(chunks.joinToString("").contains("event: done"))
    }
}
