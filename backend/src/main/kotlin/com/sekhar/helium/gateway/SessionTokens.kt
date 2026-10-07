package com.sekhar.helium.gateway

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Mints and verifies the short-lived session tokens the app sends as
 * `Authorization: Bearer ...`.
 *
 * A token is `base64url(clientId).expiryEpochSeconds.hmacSha256Hex`, so the
 * gateway stays stateless and a restart does not sign everyone out. The token
 * carries no privilege beyond "this client id": it exists to attribute usage and
 * to give the app a single, revocable-looking credential without ever putting a
 * provider key on the device.
 *
 * `HELIUM_SESSION_SECRET` is the only signing key. When it is absent every mint
 * and verify call fails closed, so a misconfigured deployment denies access
 * rather than accepting unsigned tokens.
 */
class SessionTokens(
    private val secret: String,
    private val lifetimeSeconds: Long,
) {
    val configured: Boolean get() = secret.isNotBlank()

    fun mint(clientId: String, nowEpochSeconds: Long = nowSeconds()): SessionResponse {
        check(configured) { "HELIUM_SESSION_SECRET is not set" }
        require(clientId.isNotBlank()) { "clientId must not be blank" }
        val expires = nowEpochSeconds + lifetimeSeconds
        val payload = "${encode(clientId)}.$expires"
        return SessionResponse("$payload.${sign(payload)}", expires)
    }

    /** @return the client id when the token is well formed, unexpired and signed. */
    fun verify(token: String, nowEpochSeconds: Long = nowSeconds()): String? {
        if (!configured) return null
        val parts = token.split('.')
        if (parts.size != 3) return null
        val (encodedId, expiresRaw, signature) = parts
        if (!constantTimeEquals(signature, sign("$encodedId.$expiresRaw"))) return null
        val expires = expiresRaw.toLongOrNull() ?: return null
        if (expires <= nowEpochSeconds) return null
        return runCatching { decode(encodedId) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun sign(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

    private fun decode(value: String): String =
        String(Base64.getUrlDecoder().decode(value))

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private companion object {
        fun nowSeconds(): Long = System.currentTimeMillis() / 1000
    }
}
