package ru.zakon.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CabinetLockoutTest {
    @Test
    fun activeLockReturnsRemainingTime() {
        assertEquals(50_000L, remainingLockMs(150_000L, 100_000L, 200_000L))
    }

    @Test
    fun rebootResetsLockWhenElapsedClockMovesBackwards() {
        assertEquals(0L, remainingLockMs(10_000L, 100_000L, 200_000L))
    }

    @Test
    fun expiredLockReturnsZero() {
        assertEquals(0L, remainingLockMs(250_000L, 100_000L, 200_000L))
    }

    @Test
    fun legacyLockWithoutBootMarkerIsIgnored() {
        assertEquals(0L, remainingLockMs(150_000L, 0L, 200_000L))
    }
}
