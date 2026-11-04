package com.jodae.docs199.global.error

import com.jodae.docs199.global.response.ApiResponse
import jakarta.validation.ConstraintViolationException
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(ApiException::class)
    fun api(ex: ApiException) = ResponseEntity.status(ex.code.status).body(ApiResponse.fail(ex.code.name, ex.message))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(ex: MethodArgumentNotValidException) =
        ResponseEntity.badRequest().body(
            ApiResponse.fail(
                ErrorCode.INVALID_INPUT.name,
                ErrorCode.INVALID_INPUT.message,
                ex.bindingResult.fieldErrors.map {
                    mapOf(
                        "field" to it.field,
                        "message" to it.defaultMessage,
                    )
                },
            ),
        )

    @ExceptionHandler(
        MethodArgumentTypeMismatchException::class,
        HttpMessageNotReadableException::class,
        ConstraintViolationException::class,
        MaxUploadSizeExceededException::class,
    )
    fun invalid(ex: Exception) =
        ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.INVALID_INPUT.name, ErrorCode.INVALID_INPUT.message))

    @ExceptionHandler(NoResourceFoundException::class)
    fun missing(ex: NoResourceFoundException) =
        ResponseEntity.status(404).body(ApiResponse.fail(ErrorCode.NOT_FOUND.name, ErrorCode.NOT_FOUND.message))

    @ExceptionHandler(Exception::class)
    fun unknown(ex: Exception): ResponseEntity<ApiResponse<Nothing>> =
        ResponseEntity.internalServerError().body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR.name, ErrorCode.INTERNAL_ERROR.message))
}
