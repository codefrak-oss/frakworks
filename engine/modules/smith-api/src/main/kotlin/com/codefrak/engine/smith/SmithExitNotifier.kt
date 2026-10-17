package com.codefrak.engine.smith

/** The message `smith-run` waits at after starting its sandbox (bpmn/smith-run.bpmn, `event-smith-exited`). */
const val SMITH_EXITED_MESSAGE = "message-smith-exited"

/**
 * How `smith-run` learns its sandbox exited (engine/README.md, "Smith runs"): the process waits at a message catch
 * event, and [deliver] correlates [SMITH_EXITED_MESSAGE] to the run, with the exit code. `SandboxExitNotifier`
 * (engine-smith) is the one bean: it delivers from the provider's exit events and from a backstop scan; a flow that
 * found a run whose exit was never delivered (the doctor) delivers it through this.
 */
interface SmithExitNotifier {
    enum class Delivery { COLLECTED, ALREADY, NOT_WAITING }

    /**
     * Correlate the message to the run's process instance: by run id when the exit carries one, else by the
     * `sandboxHandle` variable. Exactly one delivery of an exit gets [Delivery.COLLECTED]; the others are no-ops.
     */
    fun deliver(exit: SandboxExit): Delivery
}
