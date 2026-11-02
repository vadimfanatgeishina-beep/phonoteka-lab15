package com.phonoteka

import android.app.Application
import com.phonoteka.data.PhonotekaDatabase
import com.phonoteka.data.PhonotekaRepository

/** Application — база данных и репозиторий (ручной DI). */
class PhonotekaApp : Application() {
    val database: PhonotekaDatabase by lazy { PhonotekaDatabase.get(this) }
    val repository: PhonotekaRepository by lazy { PhonotekaRepository(database.trackDao(), this) }
    // ---- ЛР15: учебный пользователь WSS-сокета и сигнал его смены ----
    val principalChanges = com.phonoteka.security.PrincipalChangeSignal()
    val lab15User = kotlinx.coroutines.flow.MutableStateFlow("student-a")
    fun lab15SwitchUser() {
        lab15User.value = if (lab15User.value == "student-a") "student-b" else "student-a"
        principalChanges.signal()
    }
}
