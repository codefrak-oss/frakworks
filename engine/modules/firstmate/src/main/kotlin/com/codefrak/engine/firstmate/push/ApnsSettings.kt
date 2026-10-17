package com.codefrak.engine.firstmate.push

import com.codefrak.engine.accounts.SecretsKey
import com.codefrak.engine.engineSetting
import com.codefrak.engine.upsertEngineSetting
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.OffsetDateTime
import java.util.Base64

/** The APNs auth key and what goes with it: the `.p8` PEM, its key id, the team id, the bundle id (the topic), the host. */
data class ApnsCredentials(val p8: String, val keyId: String, val teamId: String, val bundleId: String, val environment: String) {
    override fun toString() = "ApnsCredentials(keyId=$keyId, teamId=$teamId, bundleId=$bundleId, environment=$environment)"
}

/**
 * The APNs credentials (engine/README.md, "Push notifications"): one row of `engine.engine_setting` named [NAME], the
 * credentials as JSON sealed under `ENGINE_SECRETS_KEY` (the row's name is the associated data) and base64-encoded.
 * They are read here, to sign the push JWT, and never answered by an API.
 */
@Component
class ApnsSettings(private val jdbc: JdbcClient, private val key: SecretsKey) {
    private val log = LoggerFactory.getLogger(ApnsSettings::class.java)
    private val json = jacksonObjectMapper()

    companion object {
        const val NAME = "push.apns"
    }

    val keyConfigured: Boolean get() = key.configured

    /** Seals and stores [c]; throws when `ENGINE_SECRETS_KEY` is not set (the controller answers 503 first). */
    fun set(c: ApnsCredentials) {
        val plain = json.writeValueAsString(mapOf("p8" to c.p8, "keyId" to c.keyId, "teamId" to c.teamId, "bundleId" to c.bundleId, "environment" to c.environment))
        jdbc.upsertEngineSetting(NAME, Base64.getEncoder().encodeToString(key.seal(plain, NAME)))
    }

    fun clear(): Boolean = jdbc.sql("DELETE FROM engine.engine_setting WHERE name = :n").param("n", NAME).update() == 1

    /** When the credentials were last set, or null when they are not. */
    fun setAt(): OffsetDateTime? =
        jdbc.sql("SELECT updated_at FROM engine.engine_setting WHERE name = :n").param("n", NAME)
            .query { rs, _ -> rs.getObject("updated_at", OffsetDateTime::class.java) }.optional().orElse(null)

    /** The credentials, or null when none are set or they cannot be opened (no key, another key): logged at DEBUG only. */
    fun get(): ApnsCredentials? {
        val sealed = jdbc.engineSetting(NAME) ?: return null
        if (!key.configured) { log.debug("APNs credentials are set but ENGINE_SECRETS_KEY is not"); return null }
        return try {
            val m: Map<String, String> = json.readValue(key.open(Base64.getDecoder().decode(sealed), NAME))
            ApnsCredentials(m.getValue("p8"), m.getValue("keyId"), m.getValue("teamId"), m.getValue("bundleId"), m.getValue("environment"))
        } catch (e: Exception) {
            log.warn("APNs credentials do not open under ENGINE_SECRETS_KEY ({}); set them again with PUT /push/apns", e.javaClass.simpleName)
            null
        }
    }
}
