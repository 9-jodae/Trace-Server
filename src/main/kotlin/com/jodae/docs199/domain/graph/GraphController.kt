package com.jodae.docs199.domain.graph

import com.jodae.docs199.global.response.ApiResponse
import com.jodae.docs199.global.security.CurrentUser
import com.jodae.docs199.global.security.UserPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/graph")
class GraphController(
    private val service: GraphService,
) {
    @GetMapping
    fun graph(
        @CurrentUser principal: UserPrincipal,
        @RequestParam(required = false) documentId: UUID?,
        @RequestParam(required = false) depth: Int?,
    ): ApiResponse<GraphResponse> = ApiResponse.ok(service.graph(principal, documentId, depth))
}

@RestController
@RequestMapping("/api/admin/document-relations")
class AdminGraphController(
    private val service: GraphService,
) {
    @PostMapping
    fun register(
        @CurrentUser principal: UserPrincipal,
        @RequestBody request: RelationRequest,
    ): ApiResponse<RelationResponse> = ApiResponse.ok(service.register(principal, request))

    @DeleteMapping("/{id}")
    fun delete(
        @CurrentUser principal: UserPrincipal,
        @PathVariable id: UUID,
    ): ApiResponse<Map<String, Boolean>> {
        service.delete(principal, id)
        return ApiResponse.ok(mapOf("deleted" to true))
    }
}
