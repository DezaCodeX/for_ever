package com.dezacodex.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Cryptographic and validation utilities for HeartSync.
 * Removes all hardcoded bypasses and plaintext secret handling.
 */
object SecurityUtils {

    private val secureRandom = SecureRandom()
    private val emailRegex = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    fun hashPassword(password: String, existingSaltBase64: String? = null): String {
        val salt = if (existingSaltBase64 != null) {
            Base64.getDecoder().decode(existingSaltBase64)
        } else {
            ByteArray(16).also { secureRandom.nextBytes(it) }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        val hash = digest.digest(password.toByteArray(Charsets.UTF_8))
        val saltEncoded = Base64.getEncoder().encodeToString(salt)
        val hashEncoded = Base64.getEncoder().encodeToString(hash)
        return "$saltEncoded:$hashEncoded"
    }

    fun verifyPassword(password: String, storedHash: String): Boolean {
        if (!storedHash.contains(":")) {
            return false
        }
        val parts = storedHash.split(":", limit = 2)
        if (parts.size != 2) return false
        val expected = try {
            hashPassword(password, parts[0])
        } catch (_: Exception) {
            return false
        }
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            storedHash.toByteArray(Charsets.UTF_8)
        )
    }

    fun generateSixDigitCode(): String {
        val value = 100000 + secureRandom.nextInt(900000)
        return value.toString()
    }

    fun generateInviteCode(): String {
        val value = 1000 + secureRandom.nextInt(9000)
        return "HS-$value"
    }

    fun isValidEmail(email: String): Boolean = emailRegex.matches(email.trim())

    fun isValidPasswordStrength(password: String): Boolean {
        if (password.length < 8) return false
        val hasLetter = password.any { it.isLetter() }
        val hasDigit = password.any { it.isDigit() }
        return hasLetter && hasDigit
    }

    fun sanitizeForLog(raw: String): String {
        return raw.replace(Regex("mongodb\\+srv://[^\\s]+"), "mongodb+srv://[REDACTED]")
            .replace(Regex("AIza[0-9A-Za-z\\-_]{20,}"), "[REDACTED_KEY]")
            .take(200)
    }
}
