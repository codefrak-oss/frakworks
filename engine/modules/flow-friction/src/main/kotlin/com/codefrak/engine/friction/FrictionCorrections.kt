package com.codefrak.engine.friction

import com.codefrak.engine.jev.JevCalls
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * A user's corrections of the grouping (engine/README.md, "Friction notes"):
 * [pullOut] (`PATCH /friction/notes/{id}` with `group`), [clearTwoThings] (the same endpoint with `twoThings`) and
 * [merge] (`POST /friction/groups/{id}/merge`), each in one transaction,
 * and the Jev outcomes they write on `friction-group` calls ([FrictionGroupCheck.pulledOut], [FrictionGroupCheck.merged],
 * [FrictionGroupCheck.kept]) so `engine.jev_call` becomes an evaluation set. Any login may correct; the caller
 * ([FrictionController]) refuses a Smith sandbox. An outcome is never overwritten, and never written on a call of
 * another use case.
 */
@Service
class FrictionCorrections(private val groups: FrictionGroups, private val calls: JevCalls) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Note [noteId] leaves its group for a new one holding it alone, titled by its summary ([FrictionGroup.title]),
     * `grouped_by` `user`, `grouped_login` [by]; it keeps its call. Its key moves to the new group's signatures unless
     * another member of the old group shares it, in which case the key stays (a key points one way). A `jev` note's
     * call gets `joined g12: pulled out to g40 by @x`. 404 for no such note; 409 for a note not grouped yet, or the
     * only member of its group (pulling it out changes nothing). Answers the note as it is now.
     */
    @Transactional
    fun pullOut(noteId: String, by: String, now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)): FrictionNote {
        val note = groups.note(noteId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no friction note $noteId")
        val from = note.groupId ?: throw ResponseStatusException(HttpStatus.CONFLICT, "friction note $noteId is not grouped yet")
        val old = groups.get(from) ?: throw ResponseStatusException(HttpStatus.CONFLICT, "friction note $noteId is in no group")
        if (old.members.size == 1) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "friction note $noteId is the only member of ${FrictionGroupCheck.name(from)}: pulling it out changes nothing")
        }
        val to = groups.create(note.summary, now)
        groups.join(note.id, to, GroupedBy.USER, note.jevCallId, by)
        val key = note.signature ?: FrictionKey.of(note.summary, note.evidence)
        if (!groups.shared(from, note.id, key)) groups.moveSignature(key, from, to)
        if (note.groupedBy == GroupedBy.JEV && note.jevCallId != null) {
            outcome(note.jevCallId, FrictionGroupCheck.pulledOut(FrictionGroupCheck.name(from), FrictionGroupCheck.name(to), by))
        }
        log.info("friction: @{} pulled note {} out of {} to {}", by, noteId, FrictionGroupCheck.name(from), FrictionGroupCheck.name(to))
        return groups.note(noteId)!!
    }

    /**
     * Every note and every signature of group [id] moves into group [into], which keeps its title, and [id] is deleted;
     * the moved notes keep their `grouped_by`, call and login. The merged group's founder calls (its `new` members with
     * a call) get `new g31: merged into g12 by @x`, and every `jev` call of the receiving group with no outcome yet gets
     * `joined g12: kept`. 404 for either group missing; 409 for a merge into itself. Answers the receiving group.
     */
    @Transactional
    fun merge(id: Long, into: Long, by: String): FrictionGroup {
        if (id == into) throw ResponseStatusException(HttpStatus.CONFLICT, "${FrictionGroupCheck.name(id)} cannot be merged into itself")
        val from = groups.get(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no friction group ${FrictionGroupCheck.name(id)}")
        val target = groups.get(into) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no friction group ${FrictionGroupCheck.name(into)}")
        groups.merge(id, into)
        val merged = FrictionGroupCheck.merged(FrictionGroupCheck.name(id), FrictionGroupCheck.name(into), by)
        from.members.filter { it.groupedBy == GroupedBy.NEW && it.jevCallId != null }.forEach { outcome(it.jevCallId!!, merged) }
        val kept = FrictionGroupCheck.kept(FrictionGroupCheck.name(into))
        target.members.filter { it.groupedBy == GroupedBy.JEV && it.jevCallId != null }.forEach { outcome(it.jevCallId!!, kept) }
        log.info("friction: @{} merged {} ({} notes) into {}", by, FrictionGroupCheck.name(id), from.members.size, FrictionGroupCheck.name(into))
        return groups.get(into)!!
    }

    /**
     * Not two things: the mark on note [noteId] goes (`two_things` null; the call stays named), nothing is filed or
     * closed, and the call that marked it (a `friction-group` call, or a lone `friction-two-things` one) gets
     * `two things: cleared by @x` when it has no outcome yet. 404 for no such note; 409 for a note not marked.
     * Answers the note as it is now.
     */
    @Transactional
    fun clearTwoThings(noteId: String, by: String): FrictionNote {
        val note = groups.note(noteId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no friction note $noteId")
        if (note.twoThings == null) throw ResponseStatusException(HttpStatus.CONFLICT, "friction note $noteId is not marked as two things")
        groups.clearMark(noteId)
        note.twoThingsCallId?.let { outcome(it, FrictionGroupCheck.twoThingsCleared(by), setOf(FrictionGroupCheck.USE_CASE, FrictionGroupCheck.TWO_THINGS_USE_CASE)) }
        log.info("friction: @{} cleared the two-things mark on note {}", by, noteId)
        return groups.note(noteId)!!
    }

    /** [outcome] on call [call] when its use case is one of [useCases] (`friction-group` alone by default) and it has none yet; any other call is left alone. */
    private fun outcome(call: String, outcome: String, useCases: Set<String> = setOf(FrictionGroupCheck.USE_CASE)) {
        val row = runCatching { calls.get(call) }.getOrNull() ?: return
        if (row.useCase !in useCases || row.outcome != null) return
        runCatching { calls.recordOutcome(call, outcome) }.onFailure { log.warn("jev {} {}: outcome not recorded: {}", row.useCase, call, it.message) }
    }
}
