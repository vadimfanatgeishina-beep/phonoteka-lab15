package com.phonoteka

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phonoteka.ui.Navigation
import com.phonoteka.ui.theme.PhonotekaTheme
import com.phonoteka.viewmodel.PlayerViewModel
import com.phonoteka.viewmodel.SettingsViewModel

/**
 * Главная и единственная Activity Фонотеки (Jetpack Compose, edge-to-edge).
 * Здесь создаются activity-scoped ViewModel настроек и плеера.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val app = applicationContext as PhonotekaApp
            val settingsViewModel: SettingsViewModel = viewModel()
            val playerViewModel: PlayerViewModel = viewModel()
            PhonotekaTheme {
                Surface {
                    Navigation(
                        repository = app.repository,
                        settingsViewModel = settingsViewModel,
                        playerViewModel = playerViewModel
                    )
                }
            }
        }
    }
}
