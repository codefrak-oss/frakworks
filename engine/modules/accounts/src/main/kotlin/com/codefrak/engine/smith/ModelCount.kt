package com.codefrak.engine.smith

/** How many live runs use one model, as `claude --model` was given it: one entry of `GET /accounts`'s `running`. */
data class ModelCount(val model: String, val count: Int)
