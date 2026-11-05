package com.jodae.docs199

import com.jodae.docs199.domain.document.IngestionProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.PropertySourcesPropertyResolver
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.env.MockEnvironment

class IngestionModeTests {
    @Test
    fun ingestionModeDefaultsToChatModeWhenUnset() {
        val source = YamlPropertySourceLoader().load("application", ClassPathResource("application.yaml")).first()
        val environment = MockEnvironment().withProperty("AI_MODE", "http")
        environment.propertySources.addFirst(source)

        assertEquals("http", PropertySourcesPropertyResolver(environment.propertySources).getProperty("app.ai.ingestion-mode"))
    }

    @Test
    fun invalidIngestionModeFailsConfigurationBinding() {
        ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration::class.java)
            .withPropertyValues("app.ai.ingestion-mode=invalid")
            .run { context -> assertNotNull(context.startupFailure) }
    }

    @Test
    fun validIndependentIngestionModeBindsWithHttpChatMode() {
        ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration::class.java)
            .withPropertyValues("app.ai.mode=http", "app.ai.ingestion-mode=mock")
            .run { context ->
                assertNull(context.startupFailure)
                assertEquals("MOCK", context.getBean(IngestionProperties::class.java).ingestionMode.name)
            }
    }

    @EnableConfigurationProperties(IngestionProperties::class)
    private class PropertiesConfiguration
}
