package com.jodae.docs199.domain.conversation

import com.jodae.docs199.global.response.ApiResponse
import com.jodae.docs199.global.security.CurrentUser
import com.jodae.docs199.global.security.UserPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/conversations")
class ConversationController(
    private val service: ConversationService,
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
}
