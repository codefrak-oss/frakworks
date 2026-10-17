package com.codefrak.engine.doctor

import java.security.MessageDigest

/** One open incident as the signature reads it: its activity, type and message, and the failed run's class for the generic message. */
data class IncidentShape(val activityId: String?, val type: String?, val message: String?, val runClass: String? = null)

/**
 * A sighting's signature (engine/README.md, "Doctor"): an exact key, SHA-256
 * over `where`, the position's leaves (sorted, without incident ids), and either each open incident's activity, type
 * and normalised first message line (with the failed run's class when the message is the generic "smith run …
 * ended failed; see its log"), or, for a `failed` doctor run, `doctor-failed` and the doctor's own run class.
 */
data class Signature(val where: String, val leaves: List<String>, val shape: List<String>) {
    /** The SHA-256 of the parts, hex. */
    val hash: String get() = MessageDigest.getInstance("SHA-256")
        .digest(listOf(where, leaves.joinToString(","), shape.joinToString("\n")).joinToString("\n\n").toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        /** The longest normalised message line. */
        const val MAX_LINE = 160
        const val DOCTOR_FAILED = "doctor-failed"
        private val UUID = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
        /** A hex run of 7 or more with a digit in it, so an English word of hex letters ("effaced") stays a word. */
        private val HEX = Regex("""(?<![0-9A-Za-z])(?=[a-fA-F]*\d)[0-9a-fA-F]{7,}(?![0-9A-Za-z])""")
        private val DIGITS = Regex("""\d+""")
        private val SPACE = Regex("""\s+""")
        /** `ReadResultDelegate`'s message for a Smith run that did not succeed: it names no cause, so the run's class is added. */
        private val GENERIC = Regex("""^smith run \S+ ended \S+; see its log""")

        /** The first line of [message] with UUIDs, hex runs of 7+ and digit runs as `#`, whitespace collapsed, cut to [MAX_LINE]. */
        fun normalise(message: String?): String {
            val line = message.orEmpty().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            return line.replace(UUID, "#").replace(HEX, "#").replace(DIGITS, "#").replace(SPACE, " ").trim().take(MAX_LINE)
        }

        /** Whether [message] is the generic "smith run … ended …; see its log". */
        fun generic(message: String?): Boolean = message != null && GENERIC.containsMatchIn(message.trim())

        /** The position's leaves, sorted, without its `incident:<id>` entries. */
        fun leaves(position: List<String>): List<String> = position.filterNot { it.startsWith("incident:") }.sorted()

        /** One line per incident, sorted: `<activity> <type> <normalised message>`, plus ` [<class>]` for the generic message. */
        fun shape(incidents: List<IncidentShape>): List<String> = incidents.map {
            listOf(it.activityId ?: "-", it.type ?: "-", normalise(it.message)).joinToString(" ") +
                (if (generic(it.message)) " [${it.runClass ?: "unknown"}]" else "")
        }.sorted()

        /** A sighting of a stuck ticket at [position] with [incidents]. */
        fun of(where: String, position: List<String>, incidents: List<IncidentShape>) = Signature(where, leaves(position), shape(incidents))

        /** A `failed` doctor run's: `where: engine`, `doctor-failed` and the doctor's own run class instead of the incidents. */
        fun doctorFailed(position: List<String>, runClass: String?) =
            Signature("engine", leaves(position), listOf("$DOCTOR_FAILED ${runClass ?: "unknown"}"))
    }
}
