package com.codefrak.engine

import org.operaton.bpm.engine.RuntimeService
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

data class ProcessStarted(val processInstanceId: String, val ended: Boolean)

/** Unauthenticated: starts one instance of the `hello` process. */
@RestController
class ProcessTestController(private val runtimeService: RuntimeService) {
    @RequestMapping("/camunda-test", method = [RequestMethod.GET, RequestMethod.POST])
    fun start(): ProcessStarted {
        val instance = runtimeService.startProcessInstanceByKey("hello")
        return ProcessStarted(instance.id, instance.isEnded)
    }
}
