package com.jodae.docs199

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.env.MockEnvironment

class UploadLimitConfigurationTests {
    private val applicationProperties =
        YamlPropertySourceLoader()
            .load("application", ClassPathResource("application.yaml"))
            .first()

    @Test
    fun legacyUploadLimitIsUsedWhenSplitSettingsAreAbsent() {
        val environment = MockEnvironment().withProperty("MAX_UPLOAD_SIZE", "5MB")
        environment.propertySources.addLast(applicationProperties)

        assertEquals("5MB", resolve(environment, "spring.servlet.multipart.max-file-size"))
        assertEquals("5MB", resolve(environment, "spring.servlet.multipart.max-request-size"))
    }

    @Test
    fun splitUploadLimitsOverrideLegacyLimitAndHaveSafeDefaults() {
        val defaults = MockEnvironment().apply { propertySources.addLast(applicationProperties) }
        assertEquals("20MB", resolve(defaults, "spring.servlet.multipart.max-file-size"))
        assertEquals("100MB", resolve(defaults, "spring.servlet.multipart.max-request-size"))

        val overridden =
            MockEnvironment()
                .withProperty("MAX_UPLOAD_SIZE", "5MB")
                .withProperty("MAX_UPLOAD_FILE_SIZE", "7MB")
                .withProperty("MAX_UPLOAD_REQUEST_SIZE", "30MB")
        overridden.propertySources.addLast(applicationProperties)
        assertEquals("7MB", resolve(overridden, "spring.servlet.multipart.max-file-size"))
        assertEquals("30MB", resolve(overridden, "spring.servlet.multipart.max-request-size"))
    }

    private fun resolve(
        environment: MockEnvironment,
        property: String,
    ): String {
        val value = applicationProperties.getProperty(property).toString()
        return environment.resolveRequiredPlaceholders(value)
    }
}
