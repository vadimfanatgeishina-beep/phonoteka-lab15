package com.phonoteka.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.phonoteka.data.ErrorType
import com.phonoteka.data.Track
import com.phonoteka.data.TrackLibrary
import com.phonoteka.data.TrackStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class ListUiState {
    object Loading : ListUiState()
    data class Success(val tracks: List<Track>, val isRefreshing: Boolean = false, val totalMatches: Int = tracks.size) : ListUiState()
    data class Error(val message: String, val errorType: ErrorType = ErrorType.UNKNOWN,
                     val retryCount: Int = 0, val nextRetryDelayMs: Long = 0L) : ListUiState()
    data class Empty(val message: String = "Ничего не найдено") : ListUiState()
}

data class LocalImportUiState(val busy: Boolean = false, val message: String? = null)

/** Состояние диалога добавления трека (вручную или импорт .txt). */
data class AddTrackUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val trackId: Long? = null    // id добавленного трека, если успешно
)

data class AddTrackDraft(val artist: String = "", val title: String = "")

/** Прогресс пакетной операции (поиск/скачивание всех треков). */
data class BatchOperationState(
    val active: Boolean = false,
    val operation: String = "",       // "search" / "download"
    val current: Int = 0,
    val total: Int = 0,
    val currentTrackName: String = "",
    val message: String? = null
)

@OptIn(FlowPreview::class)
class TrackListViewModel(
    private val repo: TrackLibrary,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()
    private val _statusFilter = MutableStateFlow<TrackStatus?>(null)
    val statusFilter = _statusFilter.asStateFlow()
    private val allTracks = MutableStateFlow<List<Track>?>(null)
    private val pageLimit = MutableStateFlow(100)
    private data class LoadRequest(val sequence: Int = 0, val delayMs: Long = 0)
    private data class Operation(val refreshing: Boolean = true, val error: ListUiState.Error? = null)
    private val requests = MutableStateFlow(LoadRequest())
    private val operation = MutableStateFlow(Operation())
    private var requestActive = true
    private var retryCount = 0
    private val _localImport = MutableStateFlow(LocalImportUiState())
    val localImport = _localImport.asStateFlow()

    private val _batchState = MutableStateFlow(BatchOperationState())
    val batchState: StateFlow<BatchOperationState> = _batchState.asStateFlow()

    private var batchJob: kotlinx.coroutines.Job? = null

    val uiState: StateFlow<ListUiState> = combine(
        allTracks, _searchQuery.debounce(300), _statusFilter, operation, pageLimit
    ) { all, query, filter, op, limit ->
        when {
            op.error != null -> op.error
            all == null -> ListUiState.Loading
            else -> {
                val filtered = filterTracks(all, query, filter)
                if (filtered.isEmpty()) ListUiState.Empty()
                else ListUiState.Success(filtered.take(limit), op.refreshing, filtered.size)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ListUiState.Loading)

    val totalCount = repo.totalCount()
    val doneCount = repo.doneCount()
    val errorCount = repo.errorCount()
    val reviewCount = repo.reviewCount()

    val addTrackDraft: StateFlow<AddTrackDraft> = combine(
        savedStateHandle.getStateFlow(DRAFT_ARTIST_KEY, ""),
        savedStateHandle.getStateFlow(DRAFT_TITLE_KEY, "")
    ) { artist, title -> AddTrackDraft(artist, title) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AddTrackDraft())

    fun saveAddTrackDraft(artist: String, title: String) {
        savedStateHandle[DRAFT_ARTIST_KEY] = artist.take(MAX_DRAFT_CHARS)
        savedStateHandle[DRAFT_TITLE_KEY] = title.take(MAX_DRAFT_CHARS)
    }

    fun clearAddTrackDraft() {
        savedStateHandle[DRAFT_ARTIST_KEY] = ""
        savedStateHandle[DRAFT_TITLE_KEY] = ""
    }

    init {
        viewModelScope.launch {
            requests.collectLatest { request ->
                try {
                    delay(request.delayMs)
                    repo.importFromAssets()
                    // Keep observing Room: no stale snapshot cache can hide subsequent imports.
                    repo.getAllTracks().collect { tracks ->
                        allTracks.value = tracks
                        retryCount = 0
                        operation.value = Operation(refreshing = false)
                        requestActive = false
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    retryCount++
                    operation.value = Operation(false, ListUiState.Error(
                        e.message ?: "Не удалось прочитать фонотеку", errorType = localErrorType(e), retryCount = retryCount,
                        nextRetryDelayMs = retryDelay(retryCount)
                    ))
                    requestActive = false
                }
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query // Text input updates immediately; only filtering is debounced.
        pageLimit.value = 100
    }
    fun onStatusFilterChange(status: TrackStatus?) {
        _statusFilter.value = status
        pageLimit.value = 100
    }
    /** Full catalogue snapshot, independent of the current search, filter and page. */
    fun playbackCatalogue(): List<Track> = allTracks.value.orEmpty()

    fun loadMore() {
        val count = filterTracks(allTracks.value.orEmpty(), _searchQuery.value, _statusFilter.value).size
        if (pageLimit.value < count) pageLimit.value = (pageLimit.value + 100).coerceAtMost(count)
    }
    fun refresh() = requestLoad(0)
    fun retry() = requestLoad(operation.value.error?.nextRetryDelayMs ?: 0)

    private fun requestLoad(delayMs: Long) {
        if (requestActive) return
        requestActive = true // Guard before launch/delay: repeated taps cannot schedule duplicates.
        operation.value = Operation(refreshing = true)
        requests.value = LoadRequest(requests.value.sequence + 1, delayMs)
    }

    fun importAudio(uris: List<Uri>) {
        if (_localImport.value.busy || uris.isEmpty()) return
        _localImport.value = LocalImportUiState(busy = true)
        viewModelScope.launch {
            try {
                val report = repo.importLocalFiles(uris)
                _localImport.value = LocalImportUiState(message = report.message +
                    " Неоднозначные совпадения выберите вручную в деталях трека.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _localImport.value = LocalImportUiState(message = e.message ?: "Ошибка импорта аудио")
            } finally {
                _localImport.value = _localImport.value.copy(busy = false)
            }
        }
    }

    // --- Добавление треков ---

    private val _addTrackState = MutableStateFlow(AddTrackUiState())
    val addTrackState: StateFlow<AddTrackUiState> = _addTrackState.asStateFlow()

    fun addTrack(artist: String, title: String) {
        if (artist.isBlank() || title.isBlank()) {
            _addTrackState.value = AddTrackUiState(message = "Заполните исполнителя и название")
            return
        }
        _addTrackState.value = AddTrackUiState(busy = true)
        viewModelScope.launch {
            try {
                val id = repo.addTrack(artist.trim(), title.trim())
                _addTrackState.value = AddTrackUiState(
                    message = "Добавлен: ${artist.trim()} — ${title.trim()}", trackId = id
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _addTrackState.value = AddTrackUiState(message = e.message ?: "Ошибка добавления")
            }
        }
    }

    fun importTextFile(uri: Uri) {
        _addTrackState.value = AddTrackUiState(busy = true)
        viewModelScope.launch {
            try {
                val added = repo.importTextFile(uri)
                _addTrackState.value = AddTrackUiState(
                    message = if (added > 0) "Импортировано треков: $added" else "Новых треков не найдено"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _addTrackState.value = AddTrackUiState(message = e.message ?: "Ошибка импорта файла")
            }
        }
    }

    fun clearAddTrackMessage() {
        _addTrackState.value = AddTrackUiState()
    }

    // --- Очистка базы (отвязка текстовика) ---

    private val _clearState = MutableStateFlow<ClearResult?>(null)
    val clearState: StateFlow<ClearResult?> = _clearState.asStateFlow()

    /** Очищает все треки из базы — отвязывает старый текстовик. */
    fun clearAllTracks() {
        viewModelScope.launch {
            try {
                repo.clearAllTracks()
                _clearState.value = ClearResult(success = true, message = "База очищена. Все треки удалены.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _clearState.value = ClearResult(success = false, message = e.message ?: "Ошибка очистки базы")
            }
        }
    }

    fun clearClearMessage() {
        _clearState.value = null
    }

    data class ClearResult(val success: Boolean, val message: String)

    // --- Пакетный поиск и скачивание ---

    /**
     * Поиск всех треков в статусе PENDING на YouTube/SoundCloud.
     * Для каждого найденного — автовыбор лучшего кандидата.
     */
    fun searchAllPending(source: String = "both") {
        if (_batchState.value.active) return
        val pending = allTracks.value.orEmpty().filter { it.status == TrackStatus.PENDING }
        if (pending.isEmpty()) {
            _batchState.value = BatchOperationState(message = "Нет треков для поиска")
            return
        }

        batchJob = viewModelScope.launch {
            _batchState.value = BatchOperationState(
                active = true, operation = "search", total = pending.size
            )
            var done = 0
            var failed = 0
            for (track in pending) {
                ensureBatchActive()
                _batchState.value = _batchState.value.copy(
                    current = done + failed + 1,
                    currentTrackName = track.displayName
                )
                try {
                    val candidates = repo.searchTrack(track.id, source)
                    if (candidates.isNotEmpty()) {
                        repo.autoSelectCandidate(track.id)
                        done++
                    } else {
                        failed++
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failed++
                }
                delay(500) // Пауза между запросами
            }
            _batchState.value = BatchOperationState(
                message = "Поиск завершён: найдено $done, не найдено $failed из ${pending.size}"
            )
        }
    }

    /**
     * Скачивание всех треков в статусе READY (кандидат выбран).
     */
    fun downloadAllReady() {
        if (_batchState.value.active) return
        val ready = allTracks.value.orEmpty().filter { it.status == TrackStatus.READY }
        if (ready.isEmpty()) {
            _batchState.value = BatchOperationState(message = "Нет треков для скачивания. Сначала выполните поиск.")
            return
        }

        batchJob = viewModelScope.launch {
            _batchState.value = BatchOperationState(
                active = true, operation = "download", total = ready.size
            )
            var done = 0
            var failed = 0
            for (track in ready) {
                ensureBatchActive()
                _batchState.value = _batchState.value.copy(
                    current = done + failed + 1,
                    currentTrackName = track.displayName
                )
                try {
                    repo.downloadTrack(track.id) { }
                    done++
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failed++
                }
                delay(300)
            }
            _batchState.value = BatchOperationState(
                message = "Скачивание завершено: успешно $done, ошибок $failed из ${ready.size}"
            )
        }
    }

    /**
     * Полный цикл: поиск + скачивание всех треков в статусе PENDING и READY.
     */
    fun searchAndDownloadAll(source: String = "both") {
        if (_batchState.value.active) return
        val toProcess = allTracks.value.orEmpty().filter {
            it.status == TrackStatus.PENDING || it.status == TrackStatus.READY ||
            it.status == TrackStatus.ERROR || it.status == TrackStatus.NOT_FOUND
        }
        if (toProcess.isEmpty()) {
            _batchState.value = BatchOperationState(message = "Все треки уже скачаны")
            return
        }

        batchJob = viewModelScope.launch {
            _batchState.value = BatchOperationState(
                active = true, operation = "search+download", total = toProcess.size
            )
            var done = 0
            var failed = 0
            for (track in toProcess) {
                ensureBatchActive()
                _batchState.value = _batchState.value.copy(
                    current = done + failed + 1,
                    currentTrackName = track.displayName,
                    operation = "search"
                )
                try {
                    // Search if not already ready
                    if (track.status != TrackStatus.READY) {
                        val candidates = repo.searchTrack(track.id, source)
                        if (candidates.isNotEmpty()) {
                            repo.autoSelectCandidate(track.id)
                        } else {
                            failed++
                            continue
                        }
                    }

                    // Download
                    _batchState.value = _batchState.value.copy(operation = "download")
                    repo.downloadTrack(track.id) { }
                    done++
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failed++
                }
                delay(500)
            }
            _batchState.value = BatchOperationState(
                message = "Готово: скачано $done, ошибок $failed из ${toProcess.size}"
            )
        }
    }

    fun cancelBatch() {
        batchJob?.cancel()
        batchJob = null
        _batchState.value = BatchOperationState(message = "Операция отменена")
    }

    fun clearBatchMessage() {
        if (!_batchState.value.active) {
            _batchState.value = BatchOperationState()
        }
    }

    fun resetErrors() {
        viewModelScope.launch {
            try {
                repo.resetErrorsToPending()
            } catch (_: Exception) {
                // Silent — Room flow will reflect the change
            }
        }
    }

    private fun ensureBatchActive() {
        if (_batchState.value.active == false) throw CancellationException("Batch cancelled")
    }

    class Factory(private val repo: TrackLibrary) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = TrackListViewModel(repo) as T

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            TrackListViewModel(repo, runCatching { extras.createSavedStateHandle() }.getOrElse { SavedStateHandle() }) as T
    }

    companion object {
        private const val DRAFT_ARTIST_KEY = "lab9.trackDraft.artist"
        private const val DRAFT_TITLE_KEY = "lab9.trackDraft.title"
        private const val MAX_DRAFT_CHARS = 200

        fun filterTracks(tracks: List<Track>, query: String, status: TrackStatus?): List<Track> {
            val text = query.trim()
            return tracks.filter { track ->
                (status == null || track.status == status) &&
                    (text.isEmpty() || track.displayName.contains(text, ignoreCase = true) ||
                        track.searchQuery.contains(text, ignoreCase = true))
            }
        }
        fun retryDelay(attempt: Int): Long = if (attempt <= 1) 0 else
            (1000L shl (attempt - 2).coerceAtMost(3)).coerceAtMost(8000)

        /** Maps repository exceptions to a user-facing [ErrorType] for differentiated display. */
        private fun localErrorType(e: Exception): ErrorType = when (e) {
            is java.io.IOException -> ErrorType.STORAGE_ERROR
            is SecurityException -> ErrorType.ACCESS_DENIED
            is IllegalArgumentException -> ErrorType.INVALID_DATA
            else -> ErrorType.UNKNOWN
        }
    }
}
