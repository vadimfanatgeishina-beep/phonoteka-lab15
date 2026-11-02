package com.phonoteka.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class E2eeHistoryTest {
    private val a = E2eeParty()
    private val b = E2eeParty()

    @Test fun bothSidesDeriveSameKeyAndDecrypt() {
        val sealed = E2eeHistory.seal(a.sessionKey(b.publicKey), "track 42", "history/v1")
        assertFalse(sealed.contains("track"))
        assertEquals("track 42", E2eeHistory.open(b.sessionKey(a.publicKey), sealed, "history/v1"))
    }

    @Test fun strangerTamperAndWrongAadFailClosed() {
        val key = a.sessionKey(b.publicKey)
        val sealed = E2eeHistory.seal(key, "track 42", "history/v1")
        assertNull(E2eeHistory.open(E2eeParty().sessionKey(a.publicKey), sealed, "history/v1"))
        assertNull(E2eeHistory.open(key, sealed, "history/v2"))
        val broken = (if (sealed[20] == 'A') 'B' else 'A').let { sealed.substring(0, 20) + it + sealed.substring(21) }
        assertNull(E2eeHistory.open(key, broken, "history/v1"))
    }

    @Test fun sameTextGivesDifferentCiphertext() {
        val key = a.sessionKey(b.publicKey)
        assertFalse(E2eeHistory.seal(key, "x", "h") == E2eeHistory.seal(key, "x", "h"))
    }
}
