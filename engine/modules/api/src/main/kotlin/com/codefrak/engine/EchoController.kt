package com.codefrak.engine

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class EchoResponse(val message: String)

/** Unauthenticated: returns the `message` query parameter unchanged. */
@RestController
class EchoController {
    @GetMapping("/echo")
    fun echo(@RequestParam message: String): EchoResponse = EchoResponse(message)
}
