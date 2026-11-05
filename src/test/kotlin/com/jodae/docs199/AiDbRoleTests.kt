package com.jodae.docs199

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.SQLException
import javax.sql.DataSource

class AiDbRoleTests : IntegrationTestBase() {
    @Autowired lateinit var dataSource: DataSource

    @Test
    fun aiAppRoleCanOnlyReadAllowlistedTablesAndCannotWriteOrAlterSchema() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("SET ROLE ai_app") }
            try {
                listOf(
                    "documents",
                    "document_versions",
                    "document_departments",
                    "sources",
                    "departments",
                    "ingestion_jobs",
                ).forEach { table ->
                    connection.createStatement().use { it.executeQuery("SELECT 1 FROM $table LIMIT 1") }
                }

                listOf("users", "conversations", "conversation_messages", "document_relations", "flyway_schema_history").forEach { table ->
                    assertThrows(SQLException::class.java) {
                        connection.createStatement().use { it.executeQuery("SELECT 1 FROM $table LIMIT 1") }
                    }
                }

                assertThrows(SQLException::class.java) {
                    connection.createStatement().use { it.execute("CREATE TABLE ai_role_probe(id int)") }
                }
                assertThrows(SQLException::class.java) {
                    connection.createStatement().use { it.execute("DELETE FROM documents") }
                }
            } finally {
                connection.createStatement().use { it.execute("RESET ROLE") }
            }
        }
    }
}
