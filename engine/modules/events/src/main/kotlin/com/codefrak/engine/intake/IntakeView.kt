package com.codefrak.engine.intake

/** The intake that filed a ticket, as the issues board shows it: the intake's instance id, its issue's number and url. */
data class IntakeView(val id: String, val number: Long?, val url: String?)
