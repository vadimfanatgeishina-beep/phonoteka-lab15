package com.phonoteka.security

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrincipalChangeSignalTest {
    @Test fun signalIncrementsEpoch() {
        val signal = PrincipalChangeSignal()
        signal.signal(); signal.signal()
        assertEquals(2L, signal.epoch.value)
    }

    @Test fun initialValuesAreNotChangesButEachSignalAndRoleSwitchIs() = runTest {
        val signal = PrincipalChangeSignal()
        val role = MutableStateFlow("EMPLOYEE")
        var closes = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            PrincipalChangeSignal.changes(signal.epoch, role).collect { closes++ }
        }
        assertEquals(0, closes) // открытие экрана не должно сразу закрывать сокет
        signal.signal()          // блокировка / выход
        assertEquals(1, closes)
        role.value = "ADMIN"     // смена роли
        assertEquals(2, closes)
        role.value = "ADMIN"     // то же значение — не смена
        assertEquals(2, closes)
    }
}
