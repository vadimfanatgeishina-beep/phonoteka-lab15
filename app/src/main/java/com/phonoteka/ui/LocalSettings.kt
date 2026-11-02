package com.phonoteka.ui

import androidx.compose.runtime.compositionLocalOf
import com.phonoteka.viewmodel.SettingsState

/** Настройки приложения, доступные из любого экрана. */
val LocalSettings = compositionLocalOf { SettingsState() }
