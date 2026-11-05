package com.jodae.docs199

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

class LegacyUserMigrationTests : IntegrationTestBase() {
    @Test
    fun v2PreservesExistingV1Rows() {
        val schema = "legacy_test_${UUID.randomUUID().toString().replace("-", "")}".lowercase()

        fun migrate(target: String) {
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(target))
                .load()
                .migrate()
        }
        migrate("1")
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """INSERT INTO $schema.app_users(id, role, department, position)
                       VALUES ('00000000-0000-0000-0000-000000000099', 'EMPLOYEE', 'DEV', 'STAFF')""",
                )
            }
        }
        migrate("2")
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM $schema.legacy_app_users").use { result ->
                    result.next()
                    assertEquals(1, result.getInt(1))
                }
                statement.executeQuery("SELECT count(*) FROM $schema.users").use { result ->
                    result.next()
                    assertEquals(0, result.getInt(1))
                }
            }
        }
    }
}
