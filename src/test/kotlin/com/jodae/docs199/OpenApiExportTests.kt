package com.jodae.docs199

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpStatus
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.io.File

/**
 * Exports the live OpenAPI spec (local profile, `/v3/api-docs`) to `docs/openapi.json` so the
 * frontend can generate typed API clients from a committed, up-to-date contract. Internal-only
 * paths are excluded via `springdoc.paths-to-exclude` (see `application.yaml`); this test also
 * guards that the exclusion stays effective.
 */
class OpenApiExportTests : IntegrationTestBase() {
    @Autowired lateinit var rest: TestRestTemplate

    @Autowired lateinit var mapper: ObjectMapper

    @Test
    fun `exports the openapi spec and excludes internal-only paths`() {
        val response = rest.getForEntity("/v3/api-docs", String::class.java)
        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body!!

        assertTrue(body.contains("\"/api/health\""), "spec should contain documented paths")
        assertFalse(body.contains("\"/api/internal/"), "internal paths should be excluded from the spec")
        assertFalse(body.contains("\"/api/dev/"), "dev paths should be excluded from the spec")

        val tree = mapper.readValue(body, object : TypeReference<MutableMap<String, Any?>>() {})
        // TestRestTemplate uses a random port. Do not bake that ephemeral address into the
        // portable client contract; consumers set their environment's base URL themselves.
        tree.remove("servers")
        val paths = tree["paths"] as Map<*, *>
        paths.values.forEach { pathItem ->
            (pathItem as Map<*, *>).values.forEach { value ->
                val operation = value as? MutableMap<String, Any?> ?: return@forEach
                val parameters = operation["parameters"] as? MutableList<MutableMap<String, Any?>> ?: return@forEach
                parameters.removeIf { it["name"] == "principal" }
            }
        }
        assertFalse(tree.toString().contains("principal"), "Spring principals are not client inputs")
        val pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree)
        val target = File("docs/openapi.json")
        target.parentFile?.mkdirs()
        target.writeText(pretty + "\n")
    }
}
