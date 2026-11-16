package com.dezacodex.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Salted PBKDF2-HMAC-SHA256 password hashing and constant-time verification.
 * Format: pbkdf2$iterations$base64Salt$base64DerivedKey
 */
object PasswordHasher {
    private const val PREFIX = "pbkdf2"
    private const val ITERATIONS = 65_536
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16
    private val secureRandom = SecureRandom()

    fun hashPassword(rawPassword: String): String {
        val salt = ByteArray(SALT_BYTES)
        secureRandom.nextBytes(salt)
        val derived = deriveKey(rawPassword.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        val saltB64 = Base64.getEncoder().encodeToString(salt)
        val keyB64 = Base64.getEncoder().encodeToString(derived)
        return "$PREFIX$$ITERATIONS$$saltB64$$keyB64"
    }

    fun verifyPassword(rawPassword: String, storedHash: String): Boolean {
        if (rawPassword.isEmpty() || storedHash.isEmpty()) return false
        return if (storedHash.startsWith("$PREFIX$")) {
            val parts = storedHash.split("$")
            if (parts.size != 4) return false
            val iterations = parts[1].toIntOrNull() ?: return false
            val salt = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return false
            val expectedKey = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull() ?: return false
            val actualKey = deriveKey(rawPassword.toCharArray(), salt, iterations, expectedKey.size * 8)
            MessageDigest.isEqual(expectedKey, actualKey)
        } else {
            // Backward compatibility for legacy unhashed records; constant-time comparison
            MessageDigest.isEqual(
                rawPassword.toByteArray(Charsets.UTF_8),
                storedHash.toByteArray(Charsets.UTF_8)
            )
        }
    }

    fun isHashed(storedHash: String): Boolean = storedHash.startsWith("$PREFIX$")

    fun generateSecureOtp(digits: Int = 6): String {
        val max = Math.pow(10.0, digits.toDouble()).toInt()
        val min = Math.pow(10.0, (digits - 1).toDouble()).toInt()
        val value = min + secureRandom.nextInt(max - min)
        return value.toString()
    }

    private fun deriveKey(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
        keyLengthBits: Int
    ): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, keyLengthBits)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }
}

object InputValidator {
    private val EMAIL_REGEX = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    fun normalizeEmail(email: String): String = email.trim().lowercase()

    fun isValidEmail(email: String): Boolean {
        val normalized = normalizeEmail(email)
        return normalized.isNotEmpty() && normalized.length <= 254 && EMAIL_REGEX.matches(normalized)
    }

    fun validateRegistration(
        name: String,
        email: String,
        password: String,
        age: Int
    ): String? {
        if (name.trim().length < 2) return "Please enter your full name (at least 2 characters)."
        if (!isValidEmail(email)) return "Please enter a valid email address."
        if (password.length < 6) return "Password must be at least 6 characters long."
        if (age !in 13..120) return "Please enter a valid age between 13 and 120."
        return null
    }

    fun validateLogin(email: String, password: String): String? {
        if (email.trim().isEmpty()) return "Email address is required."
        if (!isValidEmail(email)) return "Please enter a valid email address."
        if (password.isEmpty()) return "Password is required."
        if (password.length < 6) return "Password must be at least 6 characters long."
        return null
    }

    fun sanitizeDisplayText(input: String, maxLength: Int = 500): String {
        val cleaned = input
            .replace("\u0000", "")
            .filter { ch -> ch == '\n' || ch == '\t' || !ch.isISOControl() }
            .trim()
        return cleaned.take(maxLength.coerceAtLeast(0))
    }
}
