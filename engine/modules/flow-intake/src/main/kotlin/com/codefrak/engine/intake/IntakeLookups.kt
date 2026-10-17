package com.codefrak.engine.intake

import com.codefrak.engine.smith.IntakeLookup
import org.springframework.stereotype.Component

/** The smith layer's view of the intake flow ([IntakeLookup]): [IntakeRefs] and [IntakeTier]. */
@Component
class IntakeLookups(private val refs: IntakeRefs, private val tier: IntakeTier) : IntakeLookup {
    override fun references(repo: String, text: String, own: Long?) = refs.references(repo, text, own)
    override fun refinerTier() = tier.get()
    override fun intakeOf(repo: String, number: Long) = refs.origin(repo, number)
    override fun ticketOfRun(run: String) = refs.ticketOfRun(run)
}
