package com.jodae.docs199.global.error

import org.springframework.http.HttpStatus

enum class ErrorCode(
    val status: HttpStatus,
    val message: String,
) {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "요청이 올바르지 않습니다"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다"),
    STORAGE_ERROR(HttpStatus.BAD_GATEWAY, "파일 저장소 작업에 실패했습니다"),
    AI_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "AI 서버에 연결할 수 없습니다"),
    REVISION_DISABLED(
        HttpStatus.CONFLICT,
        "\uBB38\uC11C \uAC1C\uC815 \uAE30\uB2A5\uC774 \uBE44\uD65C\uC131\uD654\uB418\uC5B4 \uC788\uC2B5\uB2C8\uB2E4",
    ),
    STALE_DOCUMENT_VERSION(
        HttpStatus.CONFLICT,
        "\uAE30\uC900 \uBB38\uC11C \uBC84\uC804\uC774 \uD604\uC7AC \uBC84\uC804\uACFC \uB2E4\uB985\uB2C8\uB2E4",
    ),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다"),
}

class ApiException(
    val code: ErrorCode,
    override val message: String = code.message,
) : RuntimeException(message)
