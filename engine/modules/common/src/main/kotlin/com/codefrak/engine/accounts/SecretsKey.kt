package com.codefrak.engine.accounts

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The one secret in the engine's configuration: the key that unlocks the rest
 * (engine/README.md, "AI accounts"). AES-256-GCM; the row's name is the
 * associated data, so a ciphertext lifted from one row does not open in another.
 * Unset, the engine starts, but storing or reading a credential fails.
 */
@Component
class SecretsKey(@Value("\${engine.secrets.key:}") base64: String) {
    private val key: SecretKeySpec? = base64.trim().takeIf { it.isNotEmpty() }?.let {
        val bytes = Base64.getDecoder().decode(it)
        require(bytes.size == 32) { "ENGINE_SECRETS_KEY must be 32 bytes, base64 (openssl rand -base64 32)" }
        SecretKeySpec(bytes, "AES")
    }

    val configured: Boolean get() = key != null

    /** Names the key a row was sealed with, for rotation; not the key. */
    val id: String? = key?.let { Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(it.encoded)).take(16) }

    fun seal(plaintext: String, aad: String): ByteArray {
        val k = key ?: throw IllegalStateException("ENGINE_SECRETS_KEY is not set")
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad.toByteArray())
        return nonce + cipher.doFinal(plaintext.toByteArray())
    }

    fun open(sealed: ByteArray, aad: String): String {
        val k = key ?: throw IllegalStateException("ENGINE_SECRETS_KEY is not set")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, sealed, 0, 12))
        cipher.updateAAD(aad.toByteArray())
        return String(cipher.doFinal(sealed, 12, sealed.size - 12))
    }
}
