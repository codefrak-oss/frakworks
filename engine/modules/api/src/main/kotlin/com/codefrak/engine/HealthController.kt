package com.codefrak.engine

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

data class HealthResponse(val status: String)

/** Unauthenticated: reports the service is up. */
@RestController
class HealthController {
    @GetMapping("/health")
    fun health(): HealthResponse = HealthResponse("ok")
}
