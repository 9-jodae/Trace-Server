package com.docs199.server.global.ai

import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody

@Profile("local")
@RestController
@RequestMapping("/api/dev/ai")
class DevAIController(
    private val relay: SseRelay,
) {
    @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(): StreamingResponseBody = relay.relay("/sample", "{}")
}
