package com.phonoteka.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ЛР15: проверка фактического поведения трёх сценариев pinning на JVM.
 * Используются только эфемерные собственные сертификаты и loopback.
 */
class Lab15PinScenariosTest {

    @Test
    fun primaryCertificateIsAccepted() {
        val result = Lab15PinScenarios.run(Lab15PinCase.PRIMARY, pinningEnabled = true)
        assertTrue(result.actual, result.accepted)
        assertEquals(200, result.httpCode)
    }

    @Test
    fun backupCertificateIsAcceptedAfterRotation() {
        val result = Lab15PinScenarios.run(Lab15PinCase.BACKUP_AFTER_ROTATION, pinningEnabled = true)
        assertTrue(result.actual, result.accepted)
        assertEquals(200, result.httpCode)
    }

    @Test
    fun trustedButUnpinnedCertificateIsRejectedByPinning() {
        val result = Lab15PinScenarios.run(Lab15PinCase.TRUSTED_BUT_UNPINNED, pinningEnabled = true)
        assertFalse(result.actual, result.accepted)
        assertTrue(result.actual, result.actual.contains("Certificate pinning failure"))
        assertTrue(result.reason, result.reason.contains("pinning", ignoreCase = true))
    }

    @Test
    fun disablingPinningInLabModeAcceptsUnpinnedCertificate() {
        val result = Lab15PinScenarios.run(Lab15PinCase.TRUSTED_BUT_UNPINNED, pinningEnabled = false)
        assertTrue(result.actual, result.accepted)
        assertEquals(200, result.httpCode)
    }

    @Test
    fun debugNetworkSecurityConfigKeepsTwoPinsAndNoCleartext() {
        val config = File("src/debug/res/xml/network_security_config.xml")
        assertTrue("debug network_security_config.xml not found", config.exists())
        val text = config.readText()
        assertTrue(text, text.contains("cleartextTrafficPermitted=\"false\""))
        assertTrue(text, text.contains(LocalMockTlsClient.PRIMARY_PIN.removePrefix("sha256/")))
        assertTrue(text, text.contains(LocalMockTlsClient.BACKUP_PIN.removePrefix("sha256/")))
        assertEquals(text, 2, Regex("<pin\\s").findAll(text).count())
        assertTrue("pin-set must keep an expiration", text.contains("expiration="))
    }

    @Test
    fun releaseNetworkSecurityConfigHasNoLabCertificateOrPin() {
        val config = File("src/main/res/xml/network_security_config.xml")
        assertTrue(config.exists())
        val text = config.readText()
        assertTrue(text, text.contains("cleartextTrafficPermitted=\"false\""))
        assertFalse(text, text.contains("local_mock"))
        assertFalse(text, text.contains("<pin"))
    }
}
