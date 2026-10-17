package com.codefrak.engine

import com.codefrak.engine.smith.Brief
import com.codefrak.engine.smith.BRIEF_FILE
import com.codefrak.engine.smith.SmithSpec
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import org.operaton.bpm.engine.HistoryService

fun brief(spec: SmithSpec, json: ObjectMapper = jacksonObjectMapper()): Brief? = spec.inputs[BRIEF_FILE]?.let { json.readValue<Brief>(it) }

fun HistoryService.finished(id: String): Boolean = createHistoricProcessInstanceQuery().processInstanceId(id).finished().count() == 1L
