package com.jodae.docs199.global.error

import org.springframework.http.HttpStatus

enum class ErrorCode(
    val status: HttpStatus,
    val message: String,
) {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "Invalid request"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    STORAGE_ERROR(HttpStatus.BAD_GATEWAY, "Storage operation failed"),
    AI_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "AI server unavailable"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error"),
}

class ApiException(
    val code: ErrorCode,
    override val message: String = code.message,
) : RuntimeException(message)
