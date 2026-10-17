package com.codefrak.engine.friction

/**
 * What the intake flow tells the friction groups when it files a ticket (engine/README.md, "Friction notes"):
 * `FrictionBugs` and `FrictionFixes` implement it, so the intake module does not depend on the friction one.
 */
interface FrictionBugFilings {
    /** Group [groupId]'s two-things bug was filed as [issue]. */
    fun filed(groupId: Long, issue: Long)
}

/** See [FrictionBugFilings]: a filed ticket that names friction groups as what it fixes. */
interface FrictionFixFilings {
    /** [repo]#[issue], whose [body] names the groups it fixes, was filed: answers the groups linked. */
    fun filed(repo: String, issue: Long, body: String): List<Long>
    /** Intake [intake] filed [repo]#[issue]: the groups pending on it are linked to it; answers them. */
    fun intakeFiled(intake: String, repo: String, issue: Long): List<Long>
}
