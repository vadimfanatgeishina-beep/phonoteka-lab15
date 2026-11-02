package com.phonoteka.viewmodel

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.phonoteka.data.Track
import com.phonoteka.data.TrackStatus
import com.phonoteka.player.PlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerUiState(
    val track: Track? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val hasNext: Boolean = false,
    val isConnected: Boolean = false,
    val error: String? = null
)

/** A track is playable when it has a valid local file: either a SAF content URI
 * (manually bound) or a downloaded file path (from YouTube/SoundCloud download). */
fun Track.isLocallyPlayable(): Boolean {
    if (status != TrackStatus.DONE) return false
    val path = filePath ?: return false
    // SAF content URI (local file picked through system picker)
    if (path.startsWith("content://") && path.length > "content://".length) return true
    // Downloaded file on disk (from YouTube/SoundCloud via AudioDownloader)
    if (path.startsWith("/")) return true
    return false
}

/** Stable catalogue order; the selected entry always uses its latest binding. */
internal fun localPlaybackQueue(selected: Track, catalogue: List<Track>): List<Track> {
    if (!selected.isLocallyPlayable()) return emptyList()
    val queue = catalogue.map { if (it.id == selected.id) selected else it }
        .filter { it.isLocallyPlayable() }.distinctBy { it.id }
    return if (queue.any { it.id == selected.id }) queue else queue + selected
}

/** Rebuild display state without pretending that missing session URI data is a file binding. */
internal fun sessionTrack(mediaId: String, artist: String?, title: String?, uri: String?): Track? {
    val id = mediaId.toLongOrNull()?.takeIf { it > 0 } ?: return null
    val validUri = uri?.takeIf { path ->
        (path.startsWith("content://") && path.length > "content://".length) ||
        path.startsWith("/")
    }
    return Track(
        id = id, artist = artist.orEmpty(), title = title.orEmpty(),
        status = if (validUri != null) TrackStatus.DONE else TrackStatus.PENDING,
        filePath = validUri, source = if (validUri != null) "local" else null
    )
}

/** Activity-scoped connection to the foreground MediaSession; no activity-owned ExoPlayer. */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.applicationContext
    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState = _uiState.asStateFlow()
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var pollingJob: Job? = null
    private var released = false

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncState(player)
            if (player.currentMediaItem != null) startPolling()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Never carry the previous title, duration or seek position into the next item.
            _uiState.update {
                it.copy(track = mediaItem?.toTrack(), positionMs = 0, durationMs = 0, error = null)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            _uiState.update { it.copy(error = error.message ?: error.errorCodeName, isPlaying = false) }
        }
    }

    init { safeConnect() }

    /**
     * Wraps [connect] in try-catch so the ViewModel constructor never crashes.
     * Media3 SessionToken validation can throw on some devices/Android versions
     * because Class.forName uses the wrong class loader to verify the service type.
     * The rest of the app (track list, details, settings) stays fully functional.
     */
    private fun safeConnect() {
        try {
            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            buildController(token)
        } catch (e: Exception) {
            _uiState.update { it.copy(error = "Сервис воспроизведения недоступен. Нажмите «повторить» в плеере.") }
        }
    }

    /** Retry connection after a failure — called from the mini-player error UI. */
    fun retryConnect() {
        if (controller != null || controllerFuture != null) return
        _uiState.update { it.copy(error = null) }
        safeConnect()
    }

    private fun buildController(token: SessionToken) {
        val future = MediaController.Builder(app, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    if (released) return
                    pollingJob?.cancel()
                    _uiState.update {
                        it.copy(isConnected = false, isPlaying = false,
                            error = "Связь с плеером потеряна. Откройте приложение заново.")
                    }
                }
            }).buildAsync()
        controllerFuture = future
        future.addListener({
            if (!released) {
                try {
                    val connected = future.get()
                    controller = connected
                    connected.addListener(playerListener)
                    syncState(connected)
                    startPolling()
                } catch (e: Exception) {
                    _uiState.update { it.copy(error = e.message ?: "Не удалось подключиться к плееру") }
                }
            }
        }, ContextCompat.getMainExecutor(app))
    }

    private fun syncState(player: Player) {
        _uiState.update {
            it.copy(
                track = player.currentMediaItem?.toTrack(),
                isConnected = controller?.isConnected == true,
                isPlaying = player.isPlaying,
                positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = player.duration.coerceAtLeast(0),
                hasNext = player.hasNextMediaItem()
            )
        }
    }

    private fun startPolling() {
        if (pollingJob?.isActive == true || released) return
        pollingJob = viewModelScope.launch {
            while (isActive) {
                val current = controller ?: break
                if (!current.isConnected || current.currentMediaItem == null) break
                syncState(current)
                delay(if (current.isPlaying) 250L else 500L)
            }
        }
    }

    fun play(track: Track, catalogue: List<Track> = listOf(track)) {
        val queue = localPlaybackQueue(track, catalogue)
        if (queue.isEmpty()) {
            _uiState.update { it.copy(error = "У трека нет локального аудиофайла — выберите файл в деталях") }
            return
        }
        withController { current ->
            _uiState.update { it.copy(error = null) }
            current.setMediaItems(queue.map { it.toMediaItem() }, queue.indexOfFirst { it.id == track.id }, 0L)
            current.prepare()
            current.play()
            syncState(current) // Buffering is not yet actual playback.
            startPolling()
        }
    }

    fun togglePlayPause() = withController { current ->
        if (current.isPlaying) current.pause() else if (current.currentMediaItem != null) {
            _uiState.update { it.copy(error = null) }
            if (current.playbackState == Player.STATE_ENDED) current.seekTo(0L)
            if (current.playbackState == Player.STATE_IDLE) current.prepare()
            current.play()
        }
    }

    fun seekTo(positionMs: Long) = withController { current ->
        if (current.isCurrentMediaItemSeekable && current.duration > 0) {
            current.seekTo(positionMs.coerceIn(0, current.duration))
            syncState(current)
        }
    }

    fun next() = withController { current ->
        if (current.hasNextMediaItem()) current.seekToNextMediaItem()
    }

    fun clearError() { _uiState.update { it.copy(error = null) } }

    private inline fun withController(action: (MediaController) -> Unit) {
        val current = controller
        if (current == null || !current.isConnected) {
            _uiState.update { it.copy(error = "Плеер ещё не подключён, попробуйте ещё раз") }
            return
        }
        try { action(current) } catch (e: Exception) {
            _uiState.update { it.copy(error = e.message ?: "Ошибка воспроизведения") }
        }
    }

    /** Idempotent: release the future once, including connections completing after onCleared. */
    fun clear() {
        if (released) return
        released = true
        pollingJob?.cancel()
        pollingJob = null
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        _uiState.value = PlayerUiState()
    }

    override fun onCleared() {
        clear()
        super.onCleared()
    }

    private fun Track.toMediaItem(): MediaItem {
        val uri = Uri.parse(requireNotNull(filePath))
        return MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(uri)
            // LocalConfiguration may be stripped across the session IPC boundary.
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(title.ifBlank { searchQuery })
                .setArtist(artist).build())
            .build()
    }

    private fun MediaItem.toTrack(): Track? = sessionTrack(
        mediaId, mediaMetadata.artist?.toString(), mediaMetadata.title?.toString(),
        (localConfiguration?.uri ?: requestMetadata.mediaUri)?.toString()
    )
}
