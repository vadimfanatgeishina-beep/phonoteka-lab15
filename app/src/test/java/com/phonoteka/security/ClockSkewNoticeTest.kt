package com.phonoteka.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ЛР15: текст для сценария «часы сдвинуты» — проверяем формулировки и отсутствие падений. */
class ClockSkewNoticeTest {

    @Test
    fun fastClockIsExplained() {
        val text = ClockSkewNotice.describe(deviceSeconds = 1_000_060, serverSeconds = 1_000_000)
        assertTrue("должно быть сказано, что часы спешат: $text", text.contains("спешат на 60 с"))
        assertTrue("должно быть окно: $text", text.contains("±30 с"))
        assertTrue("должна быть подсказка про синхронизацию: $text", text.contains("синхронизацию"))
    }

    @Test
    fun slowClockIsExplained() {
        val text = ClockSkewNotice.describe(deviceSeconds = 1_000_000, serverSeconds = 1_000_060)
        assertTrue("должно быть сказано, что часы отстают: $text", text.contains("отстают на 60 с"))
    }

    @Test
    fun driftInsideWindowIsReportedAsFine() {
        val text = ClockSkewNotice.describe(deviceSeconds = 1_000_005, serverSeconds = 1_000_000)
        assertTrue("в пределах окна: $text", text.contains("в пределах окна"))
        assertTrue("не должно быть слова «отклонён»: $text", !text.contains("отклонён"))
    }

    @Test
    fun boundaryIsInsideWindow() {
        val text = ClockSkewNotice.describe(deviceSeconds = 1_000_030, serverSeconds = 1_000_000)
        assertTrue("ровно 30 с ещё допустимо: $text", text.contains("в пределах окна"))
    }

    @Test
    fun overflowDoesNotCrash() {
        val text = ClockSkewNotice.describe(Long.MIN_VALUE, Long.MAX_VALUE)
        assertTrue("сообщение должно остаться осмысленным: $text", text.isNotEmpty())
    }

    @Test
    fun expiredResultUsesTheSameExplanation() {
        val text = ClockSkewNotice.describeResult(
            VerificationResult.EXPIRED, deviceSeconds = 1_000_000, serverSeconds = 1_000_120
        )
        assertTrue("EXPIRED должен объяснять причину: $text", text.contains("отстают на 120 с"))
    }

    @Test
    fun otherResultsStayShort() {
        assertEquals("Подпись и время приняты.", ClockSkewNotice.describeResult(
            VerificationResult.ACCEPTED, deviceSeconds = 1_000_000, serverSeconds = 1_000_000
        ))
        assertTrue(ClockSkewNotice.describeResult(
            VerificationResult.REPLAY, deviceSeconds = 1_000_000, serverSeconds = 1_000_000
        ).contains("Повтор nonce"))
    }
}
