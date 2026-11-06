package ru.zakon.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CabinetPinKdfTest {
    @Test fun samePinAndSaltProduceSameVerifier() {
        val salt = ByteArray(16) { it.toByte() }
        assertArrayEquals(
            CabinetPinKdf.derive("correct horse", salt),
            CabinetPinKdf.derive("correct horse", salt)
        )
    }

    @Test fun differentSaltsProduceDifferentVerifiers() {
        val firstSalt = ByteArray(16) { it.toByte() }
        val secondSalt = ByteArray(16) { (it + 1).toByte() }
        assertFalse(
            CabinetPinKdf.derive("correct horse", firstSalt)
                .contentEquals(CabinetPinKdf.derive("correct horse", secondSalt))
        )
    }

    @Test fun differentPinsProduceDifferentVerifiers() {
        val salt = ByteArray(16) { 3 }
        assertFalse(
            CabinetPinKdf.derive("pin-one", salt)
                .contentEquals(CabinetPinKdf.derive("pin-two", salt))
        )
    }

    @Test fun verifierHas256Bits() {
        assertEquals(32, CabinetPinKdf.derive("correct horse", ByteArray(16) { 7 }).size)
    }

    @Test fun matchesAcceptsCorrectPin() {
        val salt = CabinetPinKdf.newSalt()
        val verifier = CabinetPinKdf.derive("secret-pin", salt)
        assertTrue(CabinetPinKdf.matches("secret-pin", salt, verifier))
        assertFalse(CabinetPinKdf.matches("wrong-pin", salt, verifier))
    }

    @Test fun base64RoundTrip() {
        val salt = CabinetPinKdf.newSalt()
        val encoded = CabinetPinKdf.toBase64(salt)
        assertArrayEquals(salt, CabinetPinKdf.fromBase64(encoded))
    }

    @Test fun constantTimeEquals() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(1, 2, 3)
        val c = byteArrayOf(1, 2, 4)
        assertTrue(CabinetPinKdf.constantTimeEquals(a, b))
        assertFalse(CabinetPinKdf.constantTimeEquals(a, c))
    }
}
