package com.codefrak.engine.smith

import com.codefrak.engine.accounts.AiAccounts

/** An Anthropic account with a credential, so a real (non-dry) run has something to bill to. */
fun AiAccounts.ensureTestAccount() {
    add("test-anthropic", "anthropic")
    setCredential("test-anthropic", "sk-ant-oat01-test")
}

fun AiAccounts.dropTestAccount() = remove("test-anthropic")
