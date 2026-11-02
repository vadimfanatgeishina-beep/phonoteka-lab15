package com.phonoteka.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.phonoteka.data.PhonotekaRepository
import com.phonoteka.data.Track
import com.phonoteka.data.TrackCandidate
import com.phonoteka.data.TrackStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Состояние экрана деталей трека.
 * Явные состояния: загрузка / успех / не найдено / ошибка.
 */
sealed class DetailUiState {
    object Loading : DetailUiState()
    data class Success(val track: Track) : DetailUiState()
    object NotFound : DetailUiState()
    data class Error(val message: String) : DetailUiState()
}

/** Прогресс операции (поиск или скачивание). */
sealed class OperationProgress {
    object Idle : OperationProgress()
    data class Searching(val active: Boolean = true) : OperationProgress()
    data class Downloading(val downloadedBytes: Long = 0, val totalBytes: Long? = null) : OperationProgress()
    object Done : OperationProgress()
}

/**
 * ViewModel экрана деталей.
 *
 * Отвечает за:
 *  - наблюдение за треком из репозитория (Flow);
 *  - поиск трека на YouTube/SoundCloud;
 *  - выбор и скачивание кандидата;
 *  - привязку локального аудиофайла через SAF (`attachAudio`).
 */
class TrackDetailViewModel(
    private val repo: PhonotekaRepository,
    private val trackId: Long
) : ViewModel() {

    private val attachBusy = AtomicBoolean(false)
    private val searchBusy = AtomicBoolean(false)
    private val downloadBusy = AtomicBoolean(false)

    private val _isAttaching = MutableStateFlow(false)
    val isAttaching: StateFlow<Boolean> = _isAttaching.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _operation = MutableStateFlow<OperationProgress>(OperationProgress.Idle)
    val operation: StateFlow<OperationProgress> = _operation.asStateFlow()

    private var downloadJob: Job? = null

    val uiState: StateFlow<DetailUiState> = repo.getTrackById(trackId)
        .onStart { repo.importFromAssets() }
        .map { track ->
            if (track == null) DetailUiState.NotFound else DetailUiState.Success(track)
        }
        .catch { e ->
            emit(DetailUiState.Error(e.message ?: "Не удалось загрузить трек"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DetailUiState.Loading
        )

    val candidates: StateFlow<List<TrackCandidate>> = repo.getCandidates(trackId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * Привязывает выбранный пользователем локальный аудиофайл к треку.
     */
    fun attachAudio(uri: Uri) {
        if (!attachBusy.compareAndSet(false, true)) return
        _isAttaching.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                repo.attachLocalFile(trackId, uri)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                _error.value = e.message?.takeIf { it.isNotBlank() }
                    ?: "Не удалось привязать аудиофайл"
            } finally {
                attachBusy.set(false)
                _isAttaching.value = false
            }
        }
    }

    /**
     * Ищет трек на YouTube и SoundCloud.
     * @param source "youtube", "soundcloud" или "both"
     */
    fun searchTrack(source: String = "both") {
        if (!searchBusy.compareAndSet(false, true)) return
        _operation.value = OperationProgress.Searching()
        _error.value = null
        viewModelScope.launch {
            try {
                val results = repo.searchTrack(trackId, source)
                _operation.value = if (results.isEmpty()) {
                    _error.value = "Ничего не найдено. Попробуйте другой источник."
                    OperationProgress.Idle
                } else {
                    // Автовыбор лучшего кандидата
                    repo.autoSelectCandidate(trackId)
                    OperationProgress.Idle
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                _error.value = e.message?.takeIf { it.isNotBlank() }
                    ?: "Ошибка поиска"
                _operation.value = OperationProgress.Idle
            } finally {
                searchBusy.set(false)
            }
        }
    }

    /** Ручной выбор кандидата из списка. */
    fun selectCandidate(candidateId: String) {
        viewModelScope.launch {
            try {
                repo.selectCandidate(trackId, candidateId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Не удалось выбрать кандидата"
            }
        }
    }

    /** Автовыбор лучшего кандидата. */
    fun autoSelectCandidate() {
        viewModelScope.launch {
            try {
                repo.autoSelectCandidate(trackId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Не удалось выбрать кандидата"
            }
        }
    }

    /**
     * Скачивает выбранного кандидата.
     * Прогресс обновляется через [OperationProgress.Downloading].
     */
    fun downloadTrack() {
        if (!downloadBusy.compareAndSet(false, true)) return
        _error.value = null
        _operation.value = OperationProgress.Downloading(0, null)

        downloadJob = viewModelScope.launch {
            try {
                repo.downloadTrack(trackId) { downloaded ->
                    _operation.value = OperationProgress.Downloading(downloaded, null)
                }
                _operation.value = OperationProgress.Done
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                _error.value = e.message?.takeIf { it.isNotBlank() }
                    ?: "Ошибка скачивания"
                _operation.value = OperationProgress.Idle
            } finally {
                downloadBusy.set(false)
            }
        }
    }

    /** Отмена скачивания. */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        downloadBusy.set(false)
        _operation.value = OperationProgress.Idle
    }

    /**
     * Комбинированная операция: поиск + автовыбор + скачивание.
     * Удобно для кнопки «Найти и скачать».
     */
    fun searchAndDownload(source: String = "both") {
        if (!searchBusy.compareAndSet(false, true)) return
        _operation.value = OperationProgress.Searching()
        _error.value = null
        viewModelScope.launch {
            try {
                val results = repo.searchTrack(trackId, source)
                if (results.isEmpty()) {
                    _error.value = "Ничего не найдено. Попробуйте другой источник."
                    _operation.value = OperationProgress.Idle
                    return@launch
                }
                repo.autoSelectCandidate(trackId)
                searchBusy.set(false)

                // Download phase
                if (!downloadBusy.compareAndSet(false, true)) return@launch
                _operation.value = OperationProgress.Downloading(0, null)
                downloadJob = viewModelScope.launch {
                    try {
                        repo.downloadTrack(trackId) { downloaded ->
                            _operation.value = OperationProgress.Downloading(downloaded, null)
                        }
                        _operation.value = OperationProgress.Done
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (e: Exception) {
                        _error.value = e.message?.takeIf { it.isNotBlank() }
                            ?: "Ошибка скачивания"
                        _operation.value = OperationProgress.Idle
                    } finally {
                        downloadBusy.set(false)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                _error.value = e.message?.takeIf { it.isNotBlank() }
                    ?: "Ошибка поиска"
                _operation.value = OperationProgress.Idle
                searchBusy.set(false)
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    class Factory(
        private val repo: PhonotekaRepository,
        private val trackId: Long
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return TrackDetailViewModel(repo, trackId) as T
        }
    }
}
