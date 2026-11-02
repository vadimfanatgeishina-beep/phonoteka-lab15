package com.phonoteka.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ЛР15 на устройстве: три сценария pinning прогоняются тем же кодом, что и экран
 * «Pinning: три сценарии» (debug-only). Стенд поднимается в процессе теста на
 * 127.0.0.1, внешняя сеть не используется.
 */
@RunWith(AndroidJUnit4::class)
class Lab15PinScenariosDeviceTest {

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
        assertTrue(result.reason, result.reason.contains("Certificate pinning failure"))
    }

    @Test
    fun labModeWithoutPinningAcceptsTheSameCertificate() {
        val result = Lab15PinScenarios.run(Lab15PinCase.TRUSTED_BUT_UNPINNED, pinningEnabled = false)
        assertTrue(result.actual, result.accepted)
    }

    @Test
    fun journalRecordsTheScenarioOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Lab15PinJournal.clear(context)
        val result = Lab15PinScenarios.run(Lab15PinCase.PRIMARY, pinningEnabled = true)
        Lab15PinJournal.append(context, result)
        val lines = Lab15PinJournal.read(context)
        assertTrue("Журнал должен содержать запись о сценарии", lines.isNotEmpty())
        assertNotNull(lines.first())
        Lab15PinJournal.clear(context)
    }
}
