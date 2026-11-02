package com.phonoteka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.phonoteka.data.Track
import com.phonoteka.data.TrackCandidate
import com.phonoteka.data.TrackStatus
import com.phonoteka.ui.components.MiniPlayer
import com.phonoteka.ui.components.StatusBadge
import com.phonoteka.ui.components.TrackCard
import com.phonoteka.ui.screens.DetailContent
import com.phonoteka.ui.screens.HomeScreen
import com.phonoteka.ui.theme.PhonotekaTheme
import com.phonoteka.viewmodel.OperationProgress
import com.phonoteka.viewmodel.PlayerUiState
import com.phonoteka.viewmodel.SettingsState

// ─── Helpers ────────────────────────────────────────────────────────────

private fun previewTrack(
    id: Long = 1L,
    artist: String = "Pink Floyd",
    title: String = "Wish You Were Here",
    status: TrackStatus = TrackStatus.DONE,
    filePath: String? = "content://media/audio/1",
    source: String? = "local",
    selectedCandidateId: String? = null
) = Track(id = id, artist = artist, title = title, status = status,
    filePath = filePath, source = source, selectedCandidateId = selectedCandidateId)

@Composable
private fun PreviewWrapper(dark: Boolean = false, content: @Composable () -> Unit) {
    PhonotekaTheme(darkTheme = dark) {
        CompositionLocalProvider(LocalSettings provides SettingsState()) {
            Surface(modifier = Modifier.fillMaxSize()) { content() }
        }
    }
}

// ─── HomeScreen ─────────────────────────────────────────────────────────

@Preview(name = "Home — Light", showBackground = true)
@Composable
fun HomeScreenPreview() = PreviewWrapper {
    HomeScreen(
        doneCount = 42, totalCount = 100, errorCount = 3, reviewCount = 5,
        onNavigateToList = {}, onNavigateToSettings = {}, onAboutVersion = {}
    )
}

@Preview(name = "Home — Dark", showBackground = true)
@Composable
fun HomeScreenDarkPreview() = PreviewWrapper(dark = true) {
    HomeScreen(
        doneCount = 42, totalCount = 100, errorCount = 3, reviewCount = 5,
        onNavigateToList = {}, onNavigateToSettings = {}, onAboutVersion = {}
    )
}

@Preview(name = "Home — Empty", showBackground = true)
@Composable
fun HomeScreenEmptyPreview() = PreviewWrapper {
    HomeScreen(
        doneCount = 0, totalCount = 0, errorCount = 0, reviewCount = 0,
        onNavigateToList = {}, onNavigateToSettings = {}, onAboutVersion = {}
    )
}

// ─── TrackCard ──────────────────────────────────────────────────────────

@Preview(name = "TrackCard — Done", showBackground = true)
@Composable
fun TrackCardDonePreview() = PreviewWrapper {
    TrackCard(track = previewTrack(status = TrackStatus.DONE), index = 0, onClick = {})
}

@Preview(name = "TrackCard — Pending", showBackground = true)
@Composable
fun TrackCardPendingPreview() = PreviewWrapper {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TrackCard(track = previewTrack(id = 1, status = TrackStatus.PENDING,
            filePath = null, source = null), index = 0, onClick = {})
        TrackCard(track = previewTrack(id = 2, artist = "Led Zeppelin", title = "Stairway to Heaven",
            status = TrackStatus.DONE), index = 1, onClick = {})
        TrackCard(track = previewTrack(id = 3, artist = "Queen", title = "Bohemian Rhapsody",
            status = TrackStatus.ERROR, filePath = null, source = null), index = 2, onClick = {})
    }
}

// ─── StatusBadge ────────────────────────────────────────────────────────

@Preview(name = "StatusBadge — All Statuses", showBackground = true)
@Composable
fun StatusBadgeAllPreview() = PreviewWrapper {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TrackStatus.entries.forEach { StatusBadge(status = it) }
    }
}

// ─── MiniPlayer ─────────────────────────────────────────────────────────

@Preview(name = "MiniPlayer — Playing", showBackground = true)
@Composable
fun MiniPlayerPlayingPreview() = PreviewWrapper {
    MiniPlayer(
        state = PlayerUiState(
            track = previewTrack(),
            isPlaying = true,
            positionMs = 45_000L,
            durationMs = 180_000L,
            hasNext = true,
            isConnected = true
        ),
        onPlayPause = {}, onSeek = {}, onNext = {}, onDismissError = {}
    )
}

@Preview(name = "MiniPlayer — Paused No Duration", showBackground = true)
@Composable
fun MiniPlayerPausedPreview() = PreviewWrapper {
    MiniPlayer(
        state = PlayerUiState(
            track = previewTrack(),
            isPlaying = false,
            positionMs = 0L,
            durationMs = 0L,
            hasNext = false,
            isConnected = true
        ),
        onPlayPause = {}, onSeek = {}, onNext = {}, onDismissError = {}
    )
}

@Preview(name = "MiniPlayer — Error", showBackground = true)
@Composable
fun MiniPlayerErrorPreview() = PreviewWrapper {
    MiniPlayer(
        state = PlayerUiState(
            track = previewTrack(),
            isPlaying = false,
            error = "Файл недоступен. Выберите файл заново.",
            isConnected = true
        ),
        onPlayPause = {}, onSeek = {}, onNext = {}, onDismissError = {}
    )
}

// ─── TrackDetailContent ─────────────────────────────────────────────────

private val previewCandidates = listOf(
    TrackCandidate(videoId = "abc1", artist = "Pink Floyd - Topic", title = "Wish You Were Here (Official Audio)",
        source = "youtube", durationSec = 341, score = 96, isExplicit = false,
        url = "https://youtube.com/watch?v=abc1", channelName = "Pink Floyd - Topic"),
    TrackCandidate(videoId = "abc2", artist = "Pink Floyd", title = "Wish You Were Here (Remastered)",
        source = "youtube", durationSec = 338, score = 92, isExplicit = false,
        url = "https://youtube.com/watch?v=abc2", channelName = "Pink Floyd"),
    TrackCandidate(videoId = "abc3", artist = "SoundCloud Artist", title = "Wish You Were Here (Cover)",
        source = "soundcloud", durationSec = 300, score = 45, isExplicit = false,
        url = "https://soundcloud.com/test/abc3", channelName = "Cover Artist")
)

@Preview(name = "TrackDetail — With Local File", showBackground = true)
@Composable
fun TrackDetailWithFilePreview() = PreviewWrapper {
    DetailContent(
        track = previewTrack(status = TrackStatus.DONE),
        candidates = emptyList(),
        isAttaching = false, error = null,
        operation = OperationProgress.Idle,
        onSearch = {}, onSearchAndDownload = {}, onDownload = {}, onCancelDownload = {},
        onSelectCandidate = {}, onAutoSelect = {},
        onPickAudio = {}, onPlay = {}, onDismissError = {}
    )
}

@Preview(name = "TrackDetail — Pending", showBackground = true)
@Composable
fun TrackDetailWithoutFilePreview() = PreviewWrapper {
    DetailContent(
        track = previewTrack(status = TrackStatus.PENDING, filePath = null, source = null),
        candidates = emptyList(),
        isAttaching = false, error = null,
        operation = OperationProgress.Idle,
        onSearch = {}, onSearchAndDownload = {}, onDownload = {}, onCancelDownload = {},
        onSelectCandidate = {}, onAutoSelect = {},
        onPickAudio = {}, onPlay = {}, onDismissError = {}
    )
}

@Preview(name = "TrackDetail — Ready + Candidates", showBackground = true)
@Composable
fun TrackDetailReadyPreview() = PreviewWrapper {
    DetailContent(
        track = previewTrack(status = TrackStatus.READY, filePath = null, source = null,
            selectedCandidateId = "abc1"),
        candidates = previewCandidates,
        isAttaching = false, error = null,
        operation = OperationProgress.Idle,
        onSearch = {}, onSearchAndDownload = {}, onDownload = {}, onCancelDownload = {},
        onSelectCandidate = {}, onAutoSelect = {},
        onPickAudio = {}, onPlay = {}, onDismissError = {}
    )
}

@Preview(name = "TrackDetail — Downloading", showBackground = true)
@Composable
fun TrackDetailDownloadingPreview() = PreviewWrapper {
    DetailContent(
        track = previewTrack(status = TrackStatus.DOWNLOADING, filePath = null, source = null),
        candidates = previewCandidates,
        isAttaching = false, error = null,
        operation = OperationProgress.Downloading(downloadedBytes = 1_500_000, totalBytes = null),
        onSearch = {}, onSearchAndDownload = {}, onDownload = {}, onCancelDownload = {},
        onSelectCandidate = {}, onAutoSelect = {},
        onPickAudio = {}, onPlay = {}, onDismissError = {}
    )
}

@Preview(name = "TrackDetail — Attaching + Error", showBackground = true)
@Composable
fun TrackDetailAttachingErrorPreview() = PreviewWrapper {
    DetailContent(
        track = previewTrack(status = TrackStatus.PENDING, filePath = null, source = null),
        candidates = emptyList(),
        isAttaching = true, error = "Не удалось прочитать аудиодорожку",
        operation = OperationProgress.Idle,
        onSearch = {}, onSearchAndDownload = {}, onDownload = {}, onCancelDownload = {},
        onSelectCandidate = {}, onAutoSelect = {},
        onPickAudio = {}, onPlay = {}, onDismissError = {}
    )
}
