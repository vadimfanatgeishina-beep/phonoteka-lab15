package com.phonoteka.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phonoteka.data.Track
import com.phonoteka.data.TrackCandidate
import com.phonoteka.data.TrackStatus
import com.phonoteka.ui.components.StatusBadge
import com.phonoteka.ui.components.StatusText
import com.phonoteka.viewmodel.DetailUiState
import com.phonoteka.viewmodel.OperationProgress
import com.phonoteka.viewmodel.TrackDetailViewModel
import com.phonoteka.viewmodel.isLocallyPlayable

/**
 * Экран деталей трека — поиск, скачивание и воспроизведение.
 *
 *  - Показывает исполнителя, название, статус.
 *  - Кнопка «Найти» ищет трек на YouTube/SoundCloud через NewPipeExtractor.
 *  - Список найденных кандидатов с оценкой совпадения; выбор лучшего.
 *  - Кнопка «Скачать» скачивает выбранный кандидат в локальное хранилище.
 *  - Кнопка «Найти и скачать» — комбинированная операция.
 *  - Ручной выбор локального файла через SAF остаётся как альтернатива.
 *  - Воспроизведение доступно для скачанных и привязанных файлов.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackDetailScreen(
    viewModel: TrackDetailViewModel,
    onBack: () -> Unit,
    onPlay: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val isAttaching by viewModel.isAttaching.collectAsState()
    val error by viewModel.error.collectAsState()
    val operation by viewModel.operation.collectAsState()
    val candidates by viewModel.candidates.collectAsState()

    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.attachAudio(uri)
    }
    val pickAudio: () -> Unit = { audioPicker.launch(arrayOf("audio/*")) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Детали трека") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { innerPadding ->
        when (val state = uiState) {
            is DetailUiState.Loading -> CenteredBox(innerPadding) {
                CircularProgressIndicator()
            }

            is DetailUiState.NotFound -> CenteredBox(innerPadding) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Элемент не найден", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onBack) { Text("Вернуться к списку") }
                }
            }

            is DetailUiState.Error -> CenteredBox(innerPadding) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onBack) { Text("Вернуться к списку") }
                }
            }

            is DetailUiState.Success -> DetailContent(
                track = state.track,
                candidates = candidates,
                isAttaching = isAttaching,
                operation = operation,
                error = error,
                onSearch = { viewModel.searchTrack("both") },
                onSearchAndDownload = { viewModel.searchAndDownload("both") },
                onDownload = { viewModel.downloadTrack() },
                onCancelDownload = { viewModel.cancelDownload() },
                onSelectCandidate = { viewModel.selectCandidate(it) },
                onAutoSelect = { viewModel.autoSelectCandidate() },
                onPickAudio = pickAudio,
                onPlay = { onPlay(state.track) },
                onDismissError = { viewModel.clearError() },
                modifier = modifier.padding(innerPadding)
            )
        }
    }
}

@Composable
private fun CenteredBox(
    innerPadding: androidx.compose.foundation.layout.PaddingValues,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
internal fun DetailContent(
    track: Track,
    candidates: List<TrackCandidate>,
    isAttaching: Boolean,
    operation: OperationProgress,
    error: String?,
    onSearch: () -> Unit,
    onSearchAndDownload: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onSelectCandidate: (String) -> Unit,
    onAutoSelect: () -> Unit,
    onPickAudio: () -> Unit,
    onPlay: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playable = track.isLocallyPlayable()
    val isSearching = operation is OperationProgress.Searching
    val isDownloading = operation is OperationProgress.Downloading
    val isBusy = isSearching || isDownloading

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Заголовок
        Text(
            text = track.artist.ifBlank { "Без исполнителя" },
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = track.title.ifBlank { track.searchQuery },
            style = MaterialTheme.typography.titleLarge
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusBadge(status = track.status)
            Text(StatusText(track.status), style = MaterialTheme.typography.bodyMedium)
        }

        HorizontalDivider()

        // Ошибка
        error?.let { message ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Ошибка",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        IconButton(onClick = onDismissError) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть сообщение об ошибке")
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // --- Кнопки поиска и скачивания ---
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Поиск и скачивание",
                    style = MaterialTheme.typography.titleSmall
                )

                if (isSearching) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Text("Идёт поиск на YouTube и SoundCloud…", style = MaterialTheme.typography.bodySmall)
                    }
                } else if (isDownloading) {
                    val dl = operation as OperationProgress.Downloading
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Text("Скачивание…", style = MaterialTheme.typography.bodySmall)
                        }
                        if (dl.totalBytes != null && dl.totalBytes > 0) {
                            LinearProgressIndicator(
                                progress = { (dl.downloadedBytes.toFloat() / dl.totalBytes).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "${formatBytes(dl.downloadedBytes)} / ${formatBytes(dl.totalBytes)}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                "${formatBytes(dl.downloadedBytes)}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        TextButton(onClick = onCancelDownload) { Text("Отменить") }
                    }
                } else {
                    // Кнопки
                    Button(
                        onClick = onSearchAndDownload,
                        enabled = !isBusy,
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Найти и скачать")
                    }

                    OutlinedButton(
                        onClick = onSearch,
                        enabled = !isBusy && track.status != TrackStatus.DONE,
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Только поиск кандидатов")
                    }

                    if (track.status == TrackStatus.READY || candidates.isNotEmpty()) {
                        OutlinedButton(
                            onClick = onDownload,
                            enabled = !isBusy,
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Скачать выбранный")
                        }
                    }
                }

                if (operation is OperationProgress.Done) {
                    Text(
                        "Скачано успешно!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // --- Список кандидатов ---
        if (candidates.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Найденные кандидаты (${candidates.size})",
                            style = MaterialTheme.typography.titleSmall
                        )
                        TextButton(onClick = onAutoSelect) { Text("Автовыбор") }
                    }
                    candidates.forEach { candidate ->
                        CandidateRow(
                            candidate = candidate,
                            isSelected = track.selectedCandidateId == candidate.videoId,
                            onClick = { onSelectCandidate(candidate.videoId) }
                        )
                    }
                }
            }
        }

        // --- Воспроизведение и локальный файл ---
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (playable) "Файл доступен" else "Файл не выбран",
                    style = MaterialTheme.typography.titleSmall
                )

                if (playable) {
                    Text(
                        text = "Источник: ${track.source ?: "локальный файл"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    track.durationSec?.let { seconds ->
                        Text(
                            text = "Длительность: ${formatDuration(seconds)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Button(
                    onClick = onPlay,
                    enabled = playable && !isAttaching,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Воспроизвести")
                }

                OutlinedButton(
                    onClick = onPickAudio,
                    enabled = !isAttaching,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Icon(Icons.Default.AudioFile, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (playable) "Выбрать другой файл" else "Привязать локальный файл вручную")
                }

                if (isAttaching) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Text("Проверка файла…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        track.source?.let {
            Text(
                text = "Источник: $it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: TrackCandidate,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RadioButton(selected = isSelected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${candidate.channelName.ifBlank { candidate.artist }} · ${candidate.source} · ${formatDuration(candidate.durationSec)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = "${candidate.score}%",
            style = MaterialTheme.typography.labelLarge,
            color = if (candidate.score >= 90) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatDuration(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val minutes = safe / 60
    val seconds = safe % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1 -> String.format("%.1f MB", mb)
        kb >= 1 -> String.format("%.0f KB", kb)
        else -> "$bytes B"
    }
}
