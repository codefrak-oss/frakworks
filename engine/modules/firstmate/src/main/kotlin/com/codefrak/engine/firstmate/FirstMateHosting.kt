package com.codefrak.engine.firstmate

import com.codefrak.engine.engineSetting
import com.codefrak.engine.upsertEngineSetting
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * Where the desktop's First mate runs, engine-wide (engine/README.md, "First-mate sessions"): row `firstmate.hosting`
 * of `engine.engine_setting`, [LOCAL] (a child process of the desktop app) or [KUBERNETES] (a session pod of this
 * engine, as the phone's). No migration seeds the row: an engine without one answers [LOCAL], so an upgrade changes
 * nothing. Set through `FirstMateHostingController`, which refuses [KUBERNETES] until the Smith hosting is Kubernetes
 * with its settings filled in; read by the desktop, nothing in the engine branches on it.
 */
@Repository
class FirstMateHosting(private val jdbc: JdbcClient) {
    fun get(): String = jdbc.engineSetting(HOSTING) ?: LOCAL

    fun set(value: String) = jdbc.upsertEngineSetting(HOSTING, value)

    companion object {
        const val HOSTING = "firstmate.hosting"
        const val LOCAL = "local"
        const val KUBERNETES = "kubernetes"
        val VALUES = listOf(LOCAL, KUBERNETES)
    }
}
