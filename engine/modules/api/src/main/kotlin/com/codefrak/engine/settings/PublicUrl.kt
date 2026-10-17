package com.codefrak.engine.settings

import com.codefrak.engine.engineSetting
import com.codefrak.engine.upsertEngineSetting
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.net.URI

/**
 * The engine's public URL (engine/README.md, "The public URL"; #2253): row `engine.public-url` of
 * `engine.engine_setting`, null while it has none. Set through `PUT /settings/public-url`; nothing outside the
 * database holds it, and nothing guesses it when unset.
 */
@Repository
class PublicUrl(private val jdbc: JdbcClient) {
    fun get(): String? = jdbc.engineSetting(NAME)

    /** Stores [url], or deletes the row for null. [url] must already pass [problem]. */
    fun set(url: String?) {
        if (url == null) jdbc.sql("DELETE FROM engine.engine_setting WHERE name = :n").param("n", NAME).update()
        else jdbc.upsertEngineSetting(NAME, url)
    }

    companion object {
        const val NAME = "engine.public-url"

        /** Why [url] is not an absolute http(s) URL with a host, or null when it is; [field] names it in the message (the HITLP URLs reuse this). */
        fun problem(url: String, field: String = "publicUrl"): String? {
            val uri = try { URI(url) } catch (e: Exception) { return "$field is not a URL: ${e.message}" }
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank())
                return "$field is an absolute http(s) URL with a host, e.g. https://engine.example.org, not $url"
            if (url.length > 2000) return "$field is at most 2000 characters"
            return null
        }
    }
}
