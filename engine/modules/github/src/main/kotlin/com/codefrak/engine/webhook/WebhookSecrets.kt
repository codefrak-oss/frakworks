package com.codefrak.engine.webhook

/** A target repo's webhook secret, or null when it has none or is no target repo ([com.codefrak.engine.repos.TargetRepos]). */
fun interface WebhookSecrets {
    fun webhookSecret(repo: String): String?
}
