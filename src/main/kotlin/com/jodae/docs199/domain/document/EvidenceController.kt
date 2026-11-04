package com.jodae.docs199.domain.document

import com.jodae.docs199.global.response.ApiResponse
import com.jodae.docs199.global.security.CurrentUser
import com.jodae.docs199.global.security.UserPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/evidence")
class EvidenceController(
    private val service: EvidenceService,
) {
    @GetMapping("/{chunkId}")
    fun evidence(
        @CurrentUser principal: UserPrincipal,
        @PathVariable chunkId: UUID,
    ) = ApiResponse.ok(service.evidence(principal, chunkId))
}
