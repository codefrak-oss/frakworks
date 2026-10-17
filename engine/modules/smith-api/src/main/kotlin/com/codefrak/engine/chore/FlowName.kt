package com.codefrak.engine.chore

import org.operaton.bpm.engine.delegate.DelegateExecution

/** `chore`, `bug`, `feature` or `proposal`: the process these shared delegates run in, for what they write. */
fun DelegateExecution.flow() = processDefinitionId.substringBefore(':')
