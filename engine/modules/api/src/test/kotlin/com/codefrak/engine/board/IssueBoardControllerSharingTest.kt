package com.codefrak.engine.board

import com.codefrak.engine.repos.TargetRepos
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** The open board: concurrent requests share one build, a fresh one is reused, a failed one is not kept. */
class IssueBoardControllerSharingTest {
    private val builds = AtomicInteger()
    private val release = CountDownLatch(1)
    private val result: Board = mock(Board::class.java)
    private val board: IssueBoard = mock(IssueBoard::class.java)

    private fun controller(reuse: Duration) =
        IssueBoardController(board, mock(ClosedIssues::class.java), mock(TargetRepos::class.java), reuse)

    @Test
    fun `requests while a build runs share it`() {
        `when`(board.board(null)).thenAnswer { builds.incrementAndGet(); release.await(5, TimeUnit.SECONDS); result }
        val c = controller(Duration.ZERO)
        val pool = Executors.newFixedThreadPool(5)
        val answers = (1..5).map { pool.submit<Board> { c.open(null) } }
        Thread.sleep(300) // all five are waiting on the one build
        release.countDown()
        answers.forEach { assertSame(result, it.get(5, TimeUnit.SECONDS)) }
        pool.shutdown()
        assertEquals(1, builds.get())
    }

    @Test
    fun `a finished board is reused within the window, then built again`() {
        `when`(board.board(null)).thenAnswer { builds.incrementAndGet(); result }
        val reused = controller(Duration.ofMinutes(1))
        reused.open(null); reused.open(null)
        assertEquals(1, builds.get())
        val rebuilt = controller(Duration.ZERO)
        rebuilt.open(null); rebuilt.open(null)
        assertEquals(3, builds.get())
    }

    @Test
    fun `a failed build is not kept`() {
        `when`(board.board(null)).thenAnswer { if (builds.incrementAndGet() == 1) error("GitHub down") else result }
        val c = controller(Duration.ofMinutes(1))
        assertFailsWith<IllegalStateException> { c.open(null) }
        assertSame(result, c.open(null))
        assertEquals(2, builds.get())
    }
}
