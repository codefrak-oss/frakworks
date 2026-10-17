package com.codefrak.engine

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.stereotype.Component

@SpringBootApplication
class EngineApplication

@Component
class HelloWorld : ApplicationRunner {
    private val log = LoggerFactory.getLogger(HelloWorld::class.java)

    override fun run(args: ApplicationArguments) {
        log.info("hello world")
    }
}

fun main(args: Array<String>) {
    runApplication<EngineApplication>(*args)
}
