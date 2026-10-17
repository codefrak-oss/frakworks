// credential-rules.js — the engine's credential rules (credentialProblem in
// engine/src/main/kotlin/com/codefrak/engine/accounts/AiAccounts.kt), shared
// by main's accountAction and the renderer's form, so a bad paste is said as
// it is typed rather than as a 401 long after. The engine checks again.

// The start every credential of a provider has: an Anthropic Smith needs the
// OAuth token `claude setup-token` prints, not an API key. No jev entry:
// TypeSafe documents no prefix for a Jev key (its Python SDK checks only for a
// non-empty key without whitespace or control characters), so a jev key gets
// the empty and whitespace rules alone.
const CREDENTIAL_PREFIX = { anthropic: "sk-ant-oat01-", openai: "sk-" };

/**
 * Why `token` cannot be a credential for `provider`, or null when it can.
 * Whitespace at either end is not counted (every caller trims it); inside the
 * token it is almost always a copy across a terminal's line wrap.
 */
function credentialProblem(provider, token) {
  const t = typeof token === "string" ? token.trim() : "";
  if (!t) return "the credential is empty";
  const space = t.search(/\s/);
  if (space >= 0) return `the credential has whitespace at character ${space + 1} of ${t.length} (a copy across a line wrap?); paste it as one unbroken line`;
  const prefix = CREDENTIAL_PREFIX[provider];
  if (prefix && !t.startsWith(prefix)) {
    return `a ${provider} credential starts with ${prefix}` + (provider === "anthropic" ? " (the long-lived token from claude setup-token, not an API key)" : "");
  }
  return null;
}

module.exports = { credentialProblem, CREDENTIAL_PREFIX };
