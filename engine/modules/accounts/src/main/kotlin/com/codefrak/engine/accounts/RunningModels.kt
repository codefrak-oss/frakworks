package com.codefrak.engine.accounts

import com.codefrak.engine.smith.ModelCount

/** The live runs per account, by model (`GET /accounts`'s `running`): `SmithTelemetry` in engine-smith (engine/README.md, "Engine modules"). */
fun interface RunningModels {
    fun running(): Map<String, List<ModelCount>>
}
