package com.codefrak.engine.frakpanel

import com.codefrak.engine.accounts.AccountUsages
import com.codefrak.engine.accounts.AiAccounts
import com.codefrak.engine.board.IssueBoard
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.OffsetDateTime

/** One plan window of a tile row: utilization 0-1 and when it resets, either null when unread. */
data class TileWindow(val utilization: Double?, val reset: OffsetDateTime?)

/**
 * One AI account on the tile. A DTO of its own so nothing else of the account (its
 * credential, its email note) can reach a panel. `state` is `disabled`, `blocked`
 * (enabled, but a window is rejected with its reset ahead: [com.codefrak.engine.accounts.AccountUsage.rejectedUntil])
 * or `enabled`. `fable` is the Fable limit's own meter; `fableLimit` holds while
 * [com.codefrak.engine.accounts.AccountUsage.fableReached].
 */
data class TileAccount(
    val name: String, val provider: String, val state: String,
    val fiveHour: TileWindow, val sevenDay: TileWindow, val fable: TileWindow, val fableLimit: TileFableLimit,
)

/** The reached Fable limit of a tile row and its recorded reset; `reset` is null when not reached. */
data class TileFableLimit(val reached: Boolean, val reset: OffsetDateTime?)

/** `commit`: the engine's, as `GET /version` answers it; a page reloads when it changes (a new engine). */
data class AiAccountsTile(val accounts: List<TileAccount>, val at: Instant, val commit: String?)

/**
 * Where the engine is with one issue on the tile: its flow's `stage`, `outcome` and `ended` (all null/false
 * without a flow), whether an incident is open on it, and whether a question waits on the user.
 */
data class TileIssueState(val stage: String?, val outcome: String?, val ended: Boolean, val incident: Boolean, val pendingQuestion: Boolean)

/**
 * One open issue on the frakworks issues tile. A DTO of its own so nothing else of the board row (its
 * author, who asked, instance ids, PR URLs) can reach a panel.
 */
data class TileIssue(
    val repo: String, val number: Long, val title: String, val url: String, val labels: List<String>, val points: Int?,
    val kind: String?, val createdAt: OffsetDateTime, val updatedAt: OffsetDateTime, val state: TileIssueState,
)

/** `failures`: the board's, the repos GitHub could not be read for. */
data class FrakworksIssuesTile(val issues: List<TileIssue>, val failures: List<String>, val at: Instant, val commit: String?)

/**
 * The frakpanel tiles (engine/README.md, "frakpanel tiles"): pages in the frakpanel
 * contract (codefrak-oss/frakpanel TILES.md), 812x720, loaded in a panel slot by URL.
 * Every one answers 404 while [FrakpanelTiles] is off.
 */
@RestController
class FrakpanelTileController(private val tiles: FrakpanelTiles, private val accounts: AiAccounts, private val usages: AccountUsages,
                               private val board: IssueBoard, @Value("\${engine.build.commit:}") commit: String) {
    private val commit: String? = commit.trim().ifEmpty { null }

    @GetMapping("/frakpanel/tiles/ai-accounts", "/frakpanel/tiles/ai-accounts/")
    fun aiAccountsPage(): ResponseEntity<String> = page("ai-accounts")

    @GetMapping("/frakpanel/tiles/frakworks-issues", "/frakpanel/tiles/frakworks-issues/")
    fun frakworksIssuesPage(): ResponseEntity<String> = page("frakworks-issues")

    /** The board as `GET /issues` reads it, every target repo, one read per poll. */
    @GetMapping("/frakpanel/tiles/frakworks-issues/state")
    fun frakworksIssuesState(): ResponseEntity<FrakworksIssuesTile> {
        on()
        val b = board.board()
        val rows = b.issues.map { i ->
            val f = i.flow
            TileIssue(i.repo, i.number, i.title, i.url, i.labels, i.points, i.kind, i.createdAt, i.updatedAt,
                TileIssueState(f?.stage, f?.outcome, f?.ended ?: false, f?.incident != null, i.pendingQuestion != null))
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(FrakworksIssuesTile(rows, b.failures, Instant.now(), commit))
    }

    @GetMapping("/frakpanel/tiles/ai-accounts/state")
    fun aiAccountsState(): ResponseEntity<AiAccountsTile> {
        on()
        val now = Instant.now()
        val byAccount = usages.list().associateBy { it.account }
        val rows = accounts.list().map { a ->
            val u = byAccount[a.name]
            val state = when {
                !a.enabled -> "disabled"
                u?.rejectedUntil(now) != null -> "blocked"
                else -> "enabled"
            }
            TileAccount(a.name, a.provider, state,
                TileWindow(u?.fiveHourUtilization, u?.fiveHourReset), TileWindow(u?.sevenDayUtilization, u?.sevenDayReset),
                TileWindow(u?.fableUtilization, u?.fableReset),
                if (u?.fableReached(now) == true) TileFableLimit(true, u.fableLimitReset) else TileFableLimit(false, null))
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AiAccountsTile(rows, now, commit))
    }

    private fun page(name: String): ResponseEntity<String> {
        on()
        val html = ClassPathResource("frakpanel/$name.html").inputStream.use { String(it.readAllBytes(), Charsets.UTF_8) }
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).cacheControl(CacheControl.noStore()).body(html)
    }

    private fun on() {
        if (!tiles.enabled()) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }
}
