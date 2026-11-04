package com.docs199.server

import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test", "local")
@Testcontainers
abstract class IntegrationTestBase {
    companion object {
        private val bucket = "test-${java.util.UUID.randomUUID()}"

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("pgvector/pgvector:0.8.7-pg18")

        @Container
        @JvmStatic
        val minio =
            GenericContainer<Nothing>("quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z").apply {
                withExposedPorts(9000)
                withEnv("MINIO_ROOT_USER", "localminio")
                withEnv("MINIO_ROOT_PASSWORD", "localminio123")
                withCommand("server", "/data")
            }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("app.storage.endpoint") { "http://${minio.host}:${minio.getMappedPort(9000)}" }
            registry.add("app.storage.access-key") { "localminio" }
            registry.add("app.storage.secret-key") { "localminio123" }
            registry.add("app.storage.bucket") { bucket }
        }
    }
}
