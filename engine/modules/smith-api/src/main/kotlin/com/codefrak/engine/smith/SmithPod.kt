package com.codefrak.engine.smith

/** The Smith image's user, as the inputs tar owns the inputs; the first-mate session pods run as it too. */
const val SMITH_UID = 65533L

/** The PriorityClass of every Smith pod (deploy/k8s/base/smith.yaml): the lowest, so Smiths are evicted before the engine and Postgres. */
const val SMITH_PRIORITY_CLASS = "frakworks-smith"
