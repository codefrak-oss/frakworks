package com.codefrak.engine.events

import java.util.Date

/**
 * What [FlowEventHandler] records of every flow's structure, inside the command that writes the history event, for the
 * ticket timeline's projection (engine/README.md, "Ticket timeline"; `TimelineProjection` in engine-incidents). Every
 * call runs on the command's thread, in its transaction: a write joins the history it mirrors and rolls back with it.
 * Called processes are reported too, under their root. A failure is caught and logged by the handler, never the command's.
 */
interface FlowRecorder {
    /** A process instance started: [superInstance] is the calling instance, null for a root; [kind] its process definition key. */
    fun processStarted(id: String, root: String, superInstance: String?, kind: String, at: Date)
    /** A process instance ended, [cancelled] when it was deleted or terminated from outside. */
    fun processEnded(id: String, root: String, at: Date, cancelled: Boolean)
    /**
     * An activity instance started (a call activity too: its called process is reported on its own). [seq] is Operaton's
     * sequence counter of the activity instance: the order of activities that started in the same millisecond.
     */
    fun activityStarted(id: String, instance: String, root: String, activityId: String, activityType: String?, seq: Long, at: Date)
    /** An activity instance ended. */
    fun activityEnded(id: String, instance: String, root: String, activityId: String, activityType: String?, seq: Long, at: Date)
    /** An incident created, resolved or deleted; [state] `open`, `resolved` or `deleted`; [rootCause] the incident it propagated from. */
    fun incident(id: String, instance: String, root: String, activityId: String?, message: String?, rootCause: String?, state: String,
                 createdAt: Date, endedAt: Date?)
    /**
     * A variable created or updated in an instance: [value] its text, number or bytes (null when unreadable); [rootScope]
     * when it is the instance's own (not an activity's) variable.
     */
    fun variable(instance: String, root: String, name: String, value: Any?, rootScope: Boolean)

    companion object {
        /** Records nothing: for an engine built without the projection, and the events module's own tests. */
        val NONE: FlowRecorder = object : FlowRecorder {
            override fun processStarted(id: String, root: String, superInstance: String?, kind: String, at: Date) {}
            override fun processEnded(id: String, root: String, at: Date, cancelled: Boolean) {}
            override fun activityStarted(id: String, instance: String, root: String, activityId: String, activityType: String?, seq: Long, at: Date) {}
            override fun activityEnded(id: String, instance: String, root: String, activityId: String, activityType: String?, seq: Long, at: Date) {}
            override fun incident(id: String, instance: String, root: String, activityId: String?, message: String?, rootCause: String?,
                                  state: String, createdAt: Date, endedAt: Date?) {}
            override fun variable(instance: String, root: String, name: String, value: Any?, rootScope: Boolean) {}
        }
    }
}
