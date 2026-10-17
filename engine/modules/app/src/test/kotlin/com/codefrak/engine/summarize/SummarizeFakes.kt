package com.codefrak.engine.summarize

import com.codefrak.engine.intake.tickets
import com.codefrak.engine.CallerIdentity
import com.codefrak.engine.smith.FakeSandbox
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.concurrent.CopyOnWriteArrayList

/** A controllable [CallerIdentity]: answers [smith] (null, no Smith), or throws [failure] when set. */
class FakeCallerIdentity : CallerIdentity {
    @Volatile var smith: String? = null
    @Volatile var failure: RuntimeException? = null

    fun reset() { smith = null; failure = null }

    override fun smithRunAt(address: String): String? {
        failure?.let { throw it }
        return smith
    }
}

/** A summarizer that records what it was given and answers [answer], or throws [failure]. */
class FakeSummarizer : SessionSummarizer {
    val calls = CopyOnWriteArrayList<Triple<String, String, String>>()
    @Volatile var answer = SessionSummary("It is adding a session table.", "Writing tests.")
    @Volatile var failure: RuntimeException? = null

    fun reset() { calls.clear(); answer = SessionSummary("It is adding a session table.", "Writing tests."); failure = null }

    override fun summarize(context: String, digest: String, recent: String): SessionSummary {
        calls += Triple(context, digest, recent)
        failure?.let { throw it }
        return answer
    }
}

/**
 * One Spring context for the session and summarize tests (SmithSessionsTest, SummarizeControllerTest, PredictReviewControllerTest): a fake
 * sandbox whose stdout the test writes, a fake summarizer, a fake Jev (PredictReviewControllerTest), H2 with Flyway's migrations. Both
 * carry the same annotations, so they share one cached context.
 */
@TestConfiguration
class SummarizeFakes {
    @Bean @Primary fun sandbox() = FakeSandbox()
    @Bean @Primary fun summarizer() = FakeSummarizer()
    @Bean @Primary fun github() = com.codefrak.engine.intake.TicketRecorder()
    @Bean @Primary fun tickets(github: com.codefrak.engine.intake.TicketRecorder) = github.tickets()
    @Bean @Primary fun jev(calls: com.codefrak.engine.jev.JevCalls) = com.codefrak.engine.jev.FakeJevDecider(calls)
    @Bean @Primary fun caller() = FakeCallerIdentity()
}
