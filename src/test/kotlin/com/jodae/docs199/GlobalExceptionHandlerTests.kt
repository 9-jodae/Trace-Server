package com.jodae.docs199

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.jodae.docs199.global.error.GlobalExceptionHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC

class GlobalExceptionHandlerTests {
    @Test
    fun unexpectedErrorLogDoesNotContainExceptionMessage() {
        val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            MDC.put("requestId", "test-request-id")
            val response = GlobalExceptionHandler().unknown(IllegalStateException("Bearer secret-token https://private.example/document"))
            assertEquals(500, response.statusCode.value())
            val event = appender.list.single()
            assertTrue(event.formattedMessage.contains("requestId=test-request-id"))
            assertTrue(event.formattedMessage.contains("exceptionType=IllegalStateException"))
            assertFalse(event.formattedMessage.contains("secret-token"))
            assertFalse(event.formattedMessage.contains("private.example"))
            assertNull(event.throwableProxy)
        } finally {
            MDC.remove("requestId")
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
