package ru.zakon.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CabinetAuthPolicyTest {
    @Test
    fun preAndroid11UsesAppPasswordFallback() {
        assertFalse(newCabinetKeyRequiresSystemAuth(29))
    }

    @Test
    fun android11AndLaterRequireSystemAuthentication() {
        assertTrue(newCabinetKeyRequiresSystemAuth(30))
        assertTrue(newCabinetKeyRequiresSystemAuth(36))
    }
}
