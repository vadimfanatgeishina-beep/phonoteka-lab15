package com.phonoteka.security

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LocalReconnectPolicyTest {
    @Test fun defaultsFollowOneTwoFourSecondsAndCapAtThirty() {
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L), (1..7).map { LocalReconnectPolicy.delayMillis(it) })
    }
    @Test fun exponentialWaitsUseVirtualTimeAndNeverExceedCap() = runTest {
        val waits = mutableListOf<Long>()
        for (retry in 1..7) { val before = testScheduler.currentTime; delay(LocalReconnectPolicy.delayMillis(retry)); waits += testScheduler.currentTime - before }
        assertEquals(91_000L, waits.sum()); assertTrue(waits.all { it <= 30_000 })
    }
    @Test fun hugeRetryAndInitialValuesDoNotOverflow() {
        assertEquals(30_000L, LocalReconnectPolicy.delayMillis(Int.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, LocalReconnectPolicy.delayMillis(2, Long.MAX_VALUE, Long.MAX_VALUE))
    }
    @Test fun negativeConfigurationIsRejected() {
        try { LocalReconnectPolicy.delayMillis(0); fail("retry0") } catch (_: IllegalArgumentException) {}
        try { LocalReconnectPolicy.delayMillis(1, -1); fail("negative delay") } catch (_: IllegalArgumentException) {}
    }
}
