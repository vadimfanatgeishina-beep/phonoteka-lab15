package com.phonoteka.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.phonoteka.data.PhonotekaRepository
import com.phonoteka.ui.components.MiniPlayer
import com.phonoteka.ui.screens.AboutVersionScreen
import com.phonoteka.ui.screens.Lab15Screen
import com.phonoteka.ui.screens.HomeScreen
import com.phonoteka.ui.screens.SplashScreen
import com.phonoteka.ui.screens.SettingsScreen
import com.phonoteka.ui.screens.TrackDetailScreen
import com.phonoteka.ui.screens.TrackListScreen
import com.phonoteka.viewmodel.PlayerViewModel
import com.phonoteka.viewmodel.SettingsViewModel
import com.phonoteka.viewmodel.TrackDetailViewModel
import com.phonoteka.viewmodel.TrackListViewModel
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

/**
 * Навигация приложения:
 *  - home — главный экран
 *  - trackList — список треков
 *  - trackDetail/{trackId} — детали трека 
 *  - settings — настройки
 *
 * [TrackListViewModel] создаётся один раз здесь (activity scope) — инициализация
 * (импорт) запускается сразу на главном экране, а список использует тот же
 * экземпляр. [PlayerViewModel] тоже activity-scoped, мини-плеер рендерится один
 * раз поверх всех экранов.
 *
 *  - about — «О версии» (меню ⋮)
 *
 * Дополнительно:
 *  - [CompositionLocalProvider] предоставляет настройки всем экранам через
 *    [LocalSettings] — без явной передачи через параметры;
 *  - [BackHandler] на главном экране: двойное нажатие «назад» для выхода;
 *  - Анимации переходов: fade + slide между экранами.
 */
@Composable
fun Navigation(
    repository: PhonotekaRepository,
    settingsViewModel: SettingsViewModel,
    playerViewModel: PlayerViewModel,
) {
    val navController = rememberNavController()
    val settingsState by settingsViewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Один общий экземпляр списка на всё приложение.
    val trackListViewModel: TrackListViewModel = viewModel(
        factory = TrackListViewModel.Factory(repository)
    )

    androidx.compose.runtime.DisposableEffect(trackListViewModel) {
        onDispose { trackListViewModel.cancelBatch() }
    }

    // Наблюдаем текущий маршрут для условного BackHandler.
    val currentRoute by navController.currentBackStackEntryAsState()
    val isHome = currentRoute?.destination?.route == "home"
    val isSplash = currentRoute?.destination?.route == "splash"

    // Время последнего нажатия «назад» на главном экране — для двойного тапа.
    var lastBackPressMs by remember { mutableLongStateOf(0L) }
    val exitWindowMs = 2000L

    // BackHandler: активен только на главном экране — двойное нажатие для выхода.
    BackHandler(enabled = isHome) {
        val now = System.currentTimeMillis()
        if (now - lastBackPressMs < exitWindowMs) {
            (context as? Activity)?.finish()
        } else {
            lastBackPressMs = now
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = "Нажмите «назад» ещё раз, чтобы выйти",
                    duration = SnackbarDuration.Short
                )
            }
        }
    }

    CompositionLocalProvider(LocalSettings provides settingsState) {
        Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
            Box(modifier = Modifier.weight(1f)) {
                NavHost(
                    navController = navController,
                    startDestination = "splash",
                    // Анимации переходов: fade + slide
                    enterTransition = {
                        fadeIn(tween(250)) + slideInHorizontally(tween(250)) { it / 4 }
                    },
                    exitTransition = {
                        fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { -it / 8 }
                    },
                    popEnterTransition = {
                        fadeIn(tween(250)) + slideInHorizontally(tween(250)) { -it / 8 }
                    },
                    popExitTransition = {
                        fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { it / 4 }
                    }
                ) {
                    // Заставка при запуске
                    composable("splash") {
                        SplashScreen(
                            onNavigateToHome = {
                                navController.navigate("home") {
                                    popUpTo("splash") { inclusive = true }
                                }
                            }
                        )
                    }

                    // Главный экран
                    composable("home") {
                        val doneCount by trackListViewModel.doneCount.collectAsState(initial = 0)
                        val totalCount by trackListViewModel.totalCount.collectAsState(initial = 0)
                        val errorCount by trackListViewModel.errorCount.collectAsState(initial = 0)
                        val reviewCount by trackListViewModel.reviewCount.collectAsState(initial = 0)

                        HomeScreen(
                            doneCount = doneCount,
                            totalCount = totalCount,
                            errorCount = errorCount,
                            reviewCount = reviewCount,
                            onNavigateToList = { navController.navigate("trackList") },
                            onNavigateToSettings = { navController.navigate("settings") },
                            onAboutVersion = { navController.navigate("about") },
                            onOpenDemo = { navController.navigate("lab") }
                        )
                    }

                    // Список треков (тот же общий ViewModel)
                    composable("trackList") {
                        TrackListScreen(
                            viewModel = trackListViewModel,
                            onTrackClick = { trackId ->
                                navController.navigate("trackDetail/$trackId")
                            },
                            onNavigateToSettings = { navController.navigate("settings") }
                        )
                    }

                    // Детали трека
                    composable(
                        route = "trackDetail/{trackId}",
                        arguments = listOf(
                            navArgument("trackId") { type = NavType.LongType }
                        )
                    ) { backStackEntry ->
                        val trackId = backStackEntry.arguments?.getLong("trackId") ?: 0L
                        val viewModel: TrackDetailViewModel = viewModel(factory = TrackDetailViewModel.Factory(repository, trackId))
                        TrackDetailScreen(viewModel = viewModel, onBack = { navController.popBackStack() },
                            onPlay = { track -> playerViewModel.play(track, trackListViewModel.playbackCatalogue()) })
                    }

                    // Настройки
                    composable("settings") {
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            onBack = { navController.popBackStack() },
                            onAboutVersion = { navController.navigate("about") }
                        )
                    }

                    // ЛР15: экран лабы (меню ⋮ → «Экран ЛР15»)
                    composable("lab") {
                        Lab15Screen(onBack = { navController.popBackStack() })
                    }

                    // ⋮ → «О версии»: требования лабы для этой версии
                    composable("about") {
                        AboutVersionScreen(onBack = { navController.popBackStack() }, onOpenDemo = { navController.navigate("lab") })
                    }
                }
            }

            // Snackbar для сообщения о выходе
            if (!isSplash) {
                SnackbarHost(hostState = snackbarHostState, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            }

            // Мини-плеер — один раз, поверх всех экранов (кроме заставки).
            if (!isSplash) {
                MiniPlayerHost(
                    playerViewModel = playerViewModel,
                    modifier = Modifier
                )
            }
        }
    }
}

/**
 * Изолирует чтение состояния плеера, чтобы обновления позиции
 * не перерисовывали NavHost и экраны.
 */
@Composable
private fun MiniPlayerHost(
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val playerState by playerViewModel.uiState.collectAsState()

    MiniPlayer(
        state = playerState,
        onPlayPause = { playerViewModel.togglePlayPause() },
        onSeek = { positionMs -> playerViewModel.seekTo(positionMs) },
        onNext = { playerViewModel.next() },
        onDismissError = { playerViewModel.clearError() },
        onRetry = { playerViewModel.retryConnect() },
        modifier = modifier
    )
}
