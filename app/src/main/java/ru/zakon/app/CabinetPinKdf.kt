package ru.zakon.app

import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN verifier derivation for the personal cabinet.
 * PBKDF2-HMAC-SHA256, 600_000 iterations, 256-bit output, random 16-byte salt.
 */
object CabinetPinKdf {
    const val ITERATIONS = 600_000
    const val KEY_BYTES = 32
    const val SALT_BYTES = 16

    fun newSalt(): ByteArray {
        val salt = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(salt)
        return salt
    }

    fun derive(pin: String, salt: ByteArray): ByteArray {
        require(salt.size >= 8) { "salt too short" }
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Constant-time comparison to reduce timing leaks. */
    fun matches(pin: String, salt: ByteArray, expected: ByteArray): Boolean {
        val actual = derive(pin, salt)
        return try {
            constantTimeEquals(actual, expected)
        } finally {
            Arrays.fill(actual, 0)
        }
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    fun toBase64(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes)

    fun fromBase64(text: String): ByteArray =
        Base64.getDecoder().decode(text)
}
