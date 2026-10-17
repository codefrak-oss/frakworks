package com.codefrak.engine.board

import com.codefrak.engine.github.IssueListing

/** A repo's open tickets as the board lists them (`IssueBoard`): what the intake's duplicate check reads. */
fun interface OpenTickets {
    /** [repo]'s open tickets, newest update first. */
    fun openTickets(repo: String): List<IssueListing>
}
