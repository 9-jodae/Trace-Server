package com.jodae.docs199.global.security

import com.jodae.docs199.domain.user.TokenService
import com.jodae.docs199.domain.user.UserRepository
import com.jodae.docs199.global.response.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Profile("local")
@RestController
@RequestMapping("/api/dev")
class DevController(
    private val users: UserRepository,
    private val tokens: TokenService,
) {
    @PostMapping("/token")
    @SecurityRequirements
    fun token(): ApiResponse<Map<String, String>> {
        val user =
            users.findByEmail("system.admin@local.docs199")
                ?: throw com.jodae.docs199.global.error
                    .ApiException(com.jodae.docs199.global.error.ErrorCode.NOT_FOUND)
        return ApiResponse.ok(tokens.issue(user))
    }

    @GetMapping("/me")
    fun me(
        @CurrentUser principal: UserPrincipal,
    ): ApiResponse<UserPrincipal> = ApiResponse.ok(principal)
}
