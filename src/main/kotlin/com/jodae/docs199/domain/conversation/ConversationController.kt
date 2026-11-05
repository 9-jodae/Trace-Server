package com.jodae.docs199.domain.conversation

import com.fasterxml.jackson.annotation.JsonInclude
import com.jodae.docs199.global.response.ApiResponse
import com.jodae.docs199.global.security.CurrentUser
import com.jodae.docs199.global.security.UserPrincipal
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SearchFilters(
    val extensions: List<String>? = null,
    val documentTypes: List<String>? = null,
    val sourceKinds: List<String>? = null,
    val authoredFrom: LocalDate? = null,
    val authoredTo: LocalDate? = null,
)

data class QuestionRequest(
    @field:NotBlank(message = "질문을 입력하세요") val message: String,
    val filters: SearchFilters? = null,
)

@RestController
@RequestMapping("/api/conversations")
class ConversationController(
    private val service: ConversationService,
    private val questions: QuestionService,
) {
    @GetMapping
    fun list(
        @CurrentUser principal: UserPrincipal,
    ) = ApiResponse.ok(service.listMine(principal))

    @GetMapping("/{id}")
    fun detail(
        @CurrentUser principal: UserPrincipal,
        @PathVariable id: UUID,
    ) = ApiResponse.ok(service.detail(principal, id))

    @DeleteMapping("/{id}")
    fun delete(
        @CurrentUser principal: UserPrincipal,
        @PathVariable id: UUID,
    ): ApiResponse<Map<String, Boolean>> {
        service.delete(principal, id)
        return ApiResponse.ok(mapOf("deleted" to true))
    }

    @PostMapping("/messages")
    fun askNew(
        @CurrentUser principal: UserPrincipal,
        @Valid @RequestBody request: QuestionRequest,
    ) = ApiResponse.ok(questions.ask(principal, null, request.message, request.filters))

    @PostMapping("/{id}/messages")
    fun ask(
        @CurrentUser principal: UserPrincipal,
        @PathVariable id: UUID,
        @Valid @RequestBody request: QuestionRequest,
    ) = ApiResponse.ok(questions.ask(principal, id, request.message, request.filters))
}
