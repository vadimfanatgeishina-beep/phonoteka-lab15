package com.phonoteka.viewmodel

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Состояние настроек приложения.
 * Настройки Фонотеки (хранятся в DataStore).
 */
data class SettingsState(
    val showHints: Boolean = true,          // Показывать подсказки (Лаба 4: чекбокс)
    val autoDownload: Boolean = false,      // Автоскачивание после поиска (Лаба 4: чекбокс)
    val showExplicitBadge: Boolean = true   // Показывать бейдж explicit (Лаба 4: чекбокс)
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "phonoteka_settings"
)

private val SHOW_HINTS_KEY = booleanPreferencesKey("show_hints")
private val AUTO_DOWNLOAD_KEY = booleanPreferencesKey("auto_download")
private val SHOW_EXPLICIT_BADGE_KEY = booleanPreferencesKey("show_explicit_badge")

/**
 * ViewModel настроек.
 *
 * Настройки хранятся в DataStore и переживают перезапуск приложения.
 * Скачивание из интернета в приложении отсутствует, поэтому настроек
 * источников/загрузки здесь нет.
 */
class SettingsViewModel @JvmOverloads constructor(application: Application,
    private val store: DataStore<Preferences> = application.applicationContext.settingsDataStore
) : AndroidViewModel(application) {


    val state: StateFlow<SettingsState> = store.data
        .catch { e ->
            if (e is IOException) {
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs ->
            SettingsState(
                showHints = prefs[SHOW_HINTS_KEY] ?: true,
                autoDownload = prefs[AUTO_DOWNLOAD_KEY] ?: false,
                showExplicitBadge = prefs[SHOW_EXPLICIT_BADGE_KEY] ?: true
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SettingsState()
        )

    fun setShowHints(enabled: Boolean) {
        viewModelScope.launch {
            store.edit { prefs ->
                prefs[SHOW_HINTS_KEY] = enabled
            }
        }
    }

    fun setAutoDownload(enabled: Boolean) {
        viewModelScope.launch {
            store.edit { prefs ->
                prefs[AUTO_DOWNLOAD_KEY] = enabled
            }
        }
    }

    fun setShowExplicitBadge(enabled: Boolean) {
        viewModelScope.launch {
            store.edit { prefs ->
                prefs[SHOW_EXPLICIT_BADGE_KEY] = enabled
            }
        }
    }
}
