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
        tree["info"] = mapOf("title" to "docs199 API", "version" to "1.0.0")
        val paths = tree["paths"] as Map<*, *>
        paths.forEach { (path, pathValue) ->
            val pathItem = pathValue as? Map<*, *> ?: return@forEach
            pathItem.values.forEach { operationValue ->
                val operation = operationValue as? MutableMap<String, Any?> ?: return@forEach
                val parameters = operation["parameters"] as? MutableList<MutableMap<String, Any?>>
                parameters?.removeIf { it["name"] == "principal" }
                if (path == "/api/admin/sources/{sourceId}/documents" || path == "/api/admin/documents/{id}/revisions") {
                    normalizeMultipartRequestBody(operation, path.toString())
                }
            }
        }
        assertFalse(tree.toString().contains("principal"), "Spring principals are not client inputs")
        val target = File("docs/openapi.json")
        assertEquals("docs199 API", (tree["info"] as Map<*, *>)["title"])
        assertEquals("1.0.0", (tree["info"] as Map<*, *>)["version"])
        assertMultipartFields(
            tree,
            "/api/admin/sources/{sourceId}/documents",
            requiredFields = setOf("files", "paths"),
            expectedProperties = setOf("files", "paths", "titles"),
        )
        assertMultipartFields(
            tree,
            "/api/admin/documents/{id}/revisions",
            requiredFields = setOf("file"),
            expectedProperties = setOf("file", "author", "authoredAt", "sourceModifiedAt"),
        )

        if (System.getenv("OPENAPI_EXPORT") == "true") {
            target.parentFile?.mkdirs()
            target.writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree) + "\n")
        } else {
            assertTrue(target.isFile, "docs/openapi.json is missing; run with OPENAPI_EXPORT=true to generate it")
            val committed = mapper.readValue(target, object : TypeReference<Map<String, Any?>>() {})
            assertEquals(committed, tree, "docs/openapi.json is stale; regenerate and commit the updated contract")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun normalizeMultipartRequestBody(
        operation: MutableMap<String, Any?>,
        path: String,
    ) {
        val parameters = operation["parameters"] as? MutableList<MutableMap<String, Any?>> ?: mutableListOf()
        val bodyProperties = linkedMapOf<String, Any?>()
        val originalBody = operation["requestBody"] as? Map<String, Any?>
        val originalContent = originalBody?.get("content") as? Map<String, Any?>
        val originalJson = originalContent?.get("application/json") as? Map<String, Any?>
        val originalSchema = originalJson?.get("schema") as? Map<String, Any?>
        val originalProperties = originalSchema?.get("properties") as? Map<String, Any?>
        originalProperties?.forEach { (name, schema) -> bodyProperties[name] = schema }

        val movedParameters = parameters.filter { it["in"] == "query" }
        movedParameters.forEach { parameter ->
            val name = parameter["name"] as? String ?: return@forEach
            val schema = parameter["schema"] ?: return@forEach
            bodyProperties[name] = schema
        }
        parameters.removeAll(movedParameters.toSet())

        val requiredFields = if (path == "/api/admin/sources/{sourceId}/documents") {
            listOf("files", "paths")
        } else {
            listOf("file")
        }
        operation["parameters"] = parameters
        operation["requestBody"] = mapOf(
            "required" to true,
            "content" to mapOf(
                "multipart/form-data" to mapOf(
                    "schema" to mapOf(
                        "type" to "object",
                        "properties" to bodyProperties,
                        "required" to requiredFields,
                    ),
                ),
            ),
        )
    }

    private fun assertMultipartFields(
        spec: Map<String, Any?>,
        path: String,
        requiredFields: Set<String>,
        expectedProperties: Set<String>,
    ) {
        val paths = spec["paths"] as Map<*, *>
        val post = (paths[path] as Map<*, *>)["post"] as Map<*, *>
        val requestBody = post["requestBody"] as Map<*, *>
        val content = requestBody["content"] as Map<*, *>
        val multipart = content["multipart/form-data"] as Map<*, *>
        val schema = multipart["schema"] as Map<*, *>
        val properties = schema["properties"] as Map<*, *>
        assertEquals(requiredFields, (schema["required"] as List<*>).toSet())
        assertTrue(properties.keys.containsAll(expectedProperties), "multipart properties are missing for $path")
    }
}
