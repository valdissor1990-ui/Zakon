package ru.zakon.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CabinetPinKdfTest {
    @Test fun samePinAndSaltProduceSameVerifier() {
        val salt = ByteArray(16) { it.toByte() }
        assertArrayEquals(CabinetPinKdf.derive("correct horse", salt), CabinetPinKdf.derive("correct horse", salt))
    }

    @Test fun differentSaltsProduceDifferentVerifiers() {
        val firstSalt = ByteArray(16) { it.toByte() }
        val secondSalt = ByteArray(16) { (it + 1).toByte() }
        assertFalse(CabinetPinKdf.derive("correct horse", firstSalt).contentEquals(CabinetPinKdf.derive("correct horse", secondSalt)))
    }

    @Test fun verifierHas256Bits() {
        assertEquals(32, CabinetPinKdf.derive("correct horse", ByteArray(16) { 7 }).size)
    }
}
