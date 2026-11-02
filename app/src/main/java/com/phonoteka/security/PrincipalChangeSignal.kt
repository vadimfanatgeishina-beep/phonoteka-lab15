package com.phonoteka.security

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update

/**
 * ЛР15: сигнал «текущий пользователь сменился или вышел». Используется, чтобы закрыть
 * живой сокет сразу при блокировке, выходе или смене роли, а не только при следующем кадре.
 */
class PrincipalChangeSignal {
    private val state = MutableStateFlow(0L)
    val epoch: StateFlow<Long> = state.asStateFlow()
    fun signal() { state.update { Math.addExact(it, 1) } }

    companion object {
        /** События смены из нескольких StateFlow; начальные значения не считаются сменой. */
        fun changes(vararg sources: StateFlow<*>): Flow<Unit> = merge(*sources.map { source -> source.drop(1).map { } }.toTypedArray())
    }
}
