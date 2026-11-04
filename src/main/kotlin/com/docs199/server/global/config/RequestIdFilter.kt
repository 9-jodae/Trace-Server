package com.docs199.server.global.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
class RequestIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val id = request.getHeader("X-Request-Id")?.takeIf { it.matches(Regex("[a-zA-Z0-9-]{1,64}")) } ?: UUID.randomUUID().toString()
        MDC.put("requestId", id)
        response.setHeader("X-Request-Id", id)
        try {
            chain.doFilter(request, response)
        } finally {
            MDC.remove("requestId")
        }
    }
}
