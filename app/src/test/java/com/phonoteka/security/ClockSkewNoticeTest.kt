package com.phonoteka.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockSkewNoticeTest {

    @Test
    fun slowClockExplainsTheLagAndWhatToDo() {
        val text = ClockSkewNotice.describe(deviceSeconds = 1_000, serverSeconds = 1_060)
        assertTrue(text, text.contains("отстают на 60 с"))
        assertTrue(text, text.contains("±30 с"))
        assertTrue(text, text.contains("Настройки → Дата и время"))
        assertTrue(text, text.contains("nonce"))
    }

    @Test
    fun fastClockExplainsTheLead() {
        val text = ClockSkewNotice.describe(deviceSeconds = 1_060, serverSeconds = 1_000)
        assertTrue(text, text.contains("спешат на 60 с"))
    }

    @Test
    fun driftInsideTheWindowIsNotAnError() {
        val text = ClockSkewNotice.describe(deviceSeconds = 1_000, serverSeconds = 1_020)
        assertTrue(text, text.contains("Рассинхрона нет"))
        assertTrue(text, text.contains("20 с"))
    }

    @Test
    fun exactWindowBoundaryStillPasses() {
        val text = ClockSkewNotice.describe(deviceSeconds = 1_000, serverSeconds = 1_030)
        assertTrue(text, text.contains("Рассинхрона нет"))
        val beyond = ClockSkewNotice.describe(deviceSeconds = 1_000, serverSeconds = 1_031)
        assertTrue(beyond, beyond.contains("отстают на 31 с"))
    }

    @Test
    fun longOverflowDoesNotCrash() {
        val text = ClockSkewNotice.describe(Long.MIN_VALUE, Long.MAX_VALUE)
        assertTrue(text, text.isNotEmpty())
        assertEquals(0L, ClockSkewNotice.driftSeconds(Long.MIN_VALUE, Long.MAX_VALUE))
        assertEquals(Long.MIN_VALUE, ClockSkewNotice.driftSeconds(Long.MIN_VALUE, 0))
    }

    @Test
    fun expiredResultReusesTheClockExplanation() {
        val expected = ClockSkewNotice.describe(deviceSeconds = 940, serverSeconds = 1_000)
        val actual = ClockSkewNotice.describeResult(VerificationResult.EXPIRED, 940, 1_000)
        assertEquals(expected, actual)
    }

    @Test
    fun otherResultsHaveTheirOwnShortText() {
        assertTrue(ClockSkewNotice.describeResult(VerificationResult.REPLAY, 1_000, 1_000).contains("409"))
        assertTrue(ClockSkewNotice.describeResult(VerificationResult.INVALID_SIGNATURE, 1_000, 1_000).contains("401"))
        assertTrue(ClockSkewNotice.describeResult(VerificationResult.INVALID_NONCE, 1_000, 1_000).contains("16–64"))
        assertTrue(ClockSkewNotice.describeResult(VerificationResult.ACCEPTED, 1_000, 1_000).contains("200"))
    }
}
