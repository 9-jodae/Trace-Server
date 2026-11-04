package com.jodae.docs199.global.response

data class ApiResponse<T>(
    val success: Boolean,
    val data: T?,
    val error: ApiError? = null,
) {
    companion object {
        fun <T> ok(data: T) = ApiResponse(true, data)

        fun fail(
            code: String,
            message: String,
            details: Any? = null,
        ) = ApiResponse<Nothing>(false, null, ApiError(code, message, details))
    }
}

data class ApiError(
    val code: String,
    val message: String,
    val details: Any? = null,
)

data class PageResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)
