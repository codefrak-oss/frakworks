package com.codefrak.engine.github

import com.codefrak.engine.accounts.SecretsKey
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

/**
 * [DeploymentPatterns] as the one row of `engine.deployment_patterns` (V102), the patterns and the ALLOW lines each
 * sealed under the engine's key ([PATTERNS_AAD], [ALLOW_AAD]).
 */
@Repository
class StoredDeploymentPatterns(private val jdbc: JdbcClient, private val secrets: SecretsKey) : DeploymentPatterns {
    companion object {
        const val PATTERNS_AAD = "engine.deployment.patterns"
        const val ALLOW_AAD = "engine.deployment.allow"
    }

    override fun get(): DeploymentPatternsView =
        jdbc.sql("SELECT patterns_set_at, allow_set_at FROM engine.deployment_patterns")
            .query { rs, _ -> DeploymentPatternsView(rs.getObject(1, OffsetDateTime::class.java), rs.getObject(2, OffsetDateTime::class.java)) }
            .optional().orElse(DeploymentPatternsView(null, null))

    override fun rules(): DeploymentRules? =
        jdbc.sql("SELECT patterns, allow FROM engine.deployment_patterns")
            .query { rs, _ -> rs.getBytes(1) to rs.getBytes(2) }.optional().orElse(null)
            ?.let { (p, a) -> p?.let { DeploymentRules(secrets.open(it, PATTERNS_AAD), a?.let { s -> secrets.open(s, ALLOW_AAD) } ?: "") } }

    override fun set(patterns: String?, allow: String?) {
        jdbc.sql("INSERT INTO engine.deployment_patterns (singleton) SELECT TRUE WHERE NOT EXISTS (SELECT 1 FROM engine.deployment_patterns)").update()
        column("patterns", patterns, PATTERNS_AAD)
        column("allow", allow, ALLOW_AAD)
    }

    private fun column(name: String, value: String?, aad: String) {
        if (value.isNullOrBlank()) jdbc.sql("UPDATE engine.deployment_patterns SET $name = NULL, ${name}_key_id = NULL, ${name}_set_at = NULL").update()
        else jdbc.sql("UPDATE engine.deployment_patterns SET $name = :v, ${name}_key_id = :k, ${name}_set_at = CURRENT_TIMESTAMP")
            .param("v", secrets.seal(value, aad)).param("k", secrets.id).update()
    }
}
