package com.codefrak.engine

import com.codefrak.engine.intake.tickets
import com.codefrak.engine.chore.FakeGitHubPulls
import com.codefrak.engine.chore.FakeGitPush
import com.codefrak.engine.chore.ScriptedSandbox
import com.codefrak.engine.ci.FakeCheckRuns
import com.codefrak.engine.intake.TicketRecorder
import com.codefrak.engine.jev.FakeJevDecider
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.postdeploy.FakeProductionCheck
import com.codefrak.engine.workitems.FakeTickets
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestContext
import org.springframework.test.context.TestExecutionListener
import java.lang.reflect.Modifier

/**
 * The fakes a flow test runs against: GitHub's issues and the tickets ending them, pulls and check runs, the push, the Smith
 * sandbox, Jev and what is in production (the post-deployment check's ops answer). A test class
 * imports this with `engine.smith.sandbox=fake` and
 * `@AutoConfigureMockMvc` rather than declaring its own, so every class that does shares one
 * Spring context (each distinct set of fakes was one more context to start, about a second
 * each). A class needing a fake this does not have keeps a configuration of its own.
 *
 * The context outlives one test class, the fakes in it too: [Fresh] puts each back to a new
 * instance's state before every class, so a class sees them as fresh as when it had its own.
 */
@TestConfiguration
class ProcessFakes {
    @Bean @Primary fun github() = TicketRecorder()
    @Bean @Primary fun tickets(github: com.codefrak.engine.intake.TicketRecorder) = github.tickets()
    @Bean @Primary fun pulls() = FakeGitHubPulls()
    @Bean @Primary fun git() = FakeGitPush()
    @Bean @Primary fun sandbox() = ScriptedSandbox()
    @Bean @Primary fun checkRuns() = FakeCheckRuns()
    @Bean @Primary fun jev(calls: JevCalls) = FakeJevDecider(calls)
    @Bean @Primary fun production() = FakeProductionCheck()

    /**
     * Before each test class on a context with these fakes, copies a new instance's fields into each
     * fake bean, so whatever an earlier class left in them is gone. Registered for every Spring test
     * in META-INF/spring.factories; a context without [ProcessFakes] is left alone.
     */
    class Fresh : TestExecutionListener {
        override fun beforeTestClass(testContext: TestContext) {
            val context = testContext.applicationContext
            if (context.getBeanNamesForType(ProcessFakes::class.java).isEmpty()) return
            val factory = ProcessFakes()
            val calls = context.getBean(JevCalls::class.java)
            listOf(factory.github(), factory.pulls(), factory.git(), factory.sandbox(), factory.checkRuns(), factory.jev(calls), factory.production())
                .forEach { fresh -> copyInto(context.getBean(fresh.javaClass), fresh) }
            // The tickets' listener records into the live github fake, so they are emptied rather than copied over.
            context.getBean(FakeTickets::class.java).reset()
        }

        private fun copyInto(live: Any, fresh: Any) {
            generateSequence<Class<*>>(fresh.javaClass) { it.superclass }.takeWhile { it != Any::class.java }.forEach { type ->
                type.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.forEach { field ->
                    field.isAccessible = true
                    field.set(live, field.get(fresh))
                }
            }
        }
    }
}
