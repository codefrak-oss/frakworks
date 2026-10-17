package com.codefrak.engine

import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** "delegate 1" in bpmn/hello.bpmn, bound by `${helloDelegate}`. */
@Component("helloDelegate")
class HelloDelegate : JavaDelegate {
    private val log = LoggerFactory.getLogger(HelloDelegate::class.java)

    override fun execute(execution: DelegateExecution) {
        log.info("hello from delegate")
    }
}
