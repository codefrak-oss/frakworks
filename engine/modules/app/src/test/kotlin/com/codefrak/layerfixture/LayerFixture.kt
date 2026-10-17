package com.codefrak.layerfixture

// Fixtures for LayerRuleTest: classes it places in modules by hand, each using the next, so the layer rule has a
// lower layer that uses an upper one (CommonThing -> ApiThing) and a flow that uses another (IntakeFlowThing -> DoctorFlowThing).

/** Stands for a class in engine-api. */
class ApiThing {
    fun name() = "api"
}

/** Stands for a class in engine-common; uses engine-api, which the rule refuses. */
class CommonThing {
    fun name() = ApiThing().name()
}

/** Stands for a class in engine-flow-doctor. */
class DoctorFlowThing {
    fun name() = "doctor"
}

/** Stands for a class in engine-flow-intake; uses engine-flow-doctor, which the rule refuses. */
class IntakeFlowThing {
    fun name() = DoctorFlowThing().name()
}
