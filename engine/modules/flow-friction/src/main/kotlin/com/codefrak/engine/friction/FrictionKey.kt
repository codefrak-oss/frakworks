package com.codefrak.engine.friction

import com.codefrak.engine.doctor.Signature
import java.security.MessageDigest

/**
 * A friction note's exact key (engine/README.md, "Friction notes"): the SHA-256 of the field the key line
 * came from ([EVIDENCE], or [SUMMARY] when the note has no evidence) and that field's first non-blank line normalised
 * by the doctor's [Signature.normalise] (UUIDs, hex runs of 7 or more and digit runs become `#`, whitespace collapsed,
 * cut to 160 characters). `kind` and `repo` are not in it: a kind is the author's label, and a sandbox problem is
 * the same friction in every target repo. Computed at insert ([FrictionNotes.insert]) and by the V39 backfill.
 */
object FrictionKey {
    const val EVIDENCE = "evidence"
    const val SUMMARY = "summary"

    /** Which field the key line comes from: the evidence when it has a non-blank line, else the summary. */
    fun field(evidence: String?): String = if (evidence != null && evidence.lineSequence().any { it.isNotBlank() }) EVIDENCE else SUMMARY

    /** The normalised key line. */
    fun line(summary: String, evidence: String?): String = Signature.normalise(if (field(evidence) == EVIDENCE) evidence else summary)

    /** The key, hex. */
    fun of(summary: String, evidence: String?): String = MessageDigest.getInstance("SHA-256")
        .digest("${field(evidence)}\n${line(summary, evidence)}".toByteArray())
        .joinToString("") { "%02x".format(it) }
}
