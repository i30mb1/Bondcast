package n7.bondcast.obs

import java.security.MessageDigest
import java.util.Base64

/**
 * Токен аутентификации obs-websocket v5:
 * base64(sha256(base64(sha256(password + salt)) + challenge)).
 */
internal fun obsAuthToken(password: String, salt: String, challenge: String): String {
    val secret = sha256Base64(password + salt)
    return sha256Base64(secret + challenge)
}

private fun sha256Base64(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return Base64.getEncoder().encodeToString(digest)
}
