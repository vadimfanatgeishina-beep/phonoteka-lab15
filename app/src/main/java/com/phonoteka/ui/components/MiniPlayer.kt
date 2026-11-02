package com.phonoteka.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phonoteka.viewmodel.PlayerUiState

/**
 * Мини-плеер — отображается один раз поверх всех экранов.
 *
 * Показывает название трека, текущее время / длительность, полосу перемотки
 * и управление play/pause. Кнопка «следующий» показывается только если
 * очередь реально поддерживает переход вперёд. Ошибки плеера показываются
 * текстом, а не скрываются.
 */
@Composable
fun MiniPlayer(
    state: PlayerUiState,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onNext: () -> Unit,
    onDismissError: () -> Unit,
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val track = state.track
    if (track == null && state.error == null) return

    val duration = state.durationMs
    val canSeek = duration > 0L

    // Пока пользователь тянет ползунок, не даём опросу «дёргать» позицию.
    var draggingFraction by remember(track?.id) { mutableStateOf<Float?>(null) }
    val positionFraction = if (canSeek) {
        (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    } else {
        0f
    }
    val sliderValue = draggingFraction ?: positionFraction

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track?.displayName ?: "Плеер",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${formatTime(state.positionMs)} / ${formatTime(duration)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.width(8.dp))

                IconButton(onClick = onPlayPause, enabled = track != null && state.isConnected) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Пауза" else "Играть"
                    )
                }

                // Переход вперёд — только если очередь его поддерживает.
                if (state.hasNext) {
                    IconButton(onClick = onNext) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Следующий")
                    }
                }
            }

            Slider(
                value = sliderValue,
                onValueChange = { draggingFraction = it },
                onValueChangeFinished = {
                    val fraction = draggingFraction
                    if (fraction != null && canSeek) {
                        onSeek((fraction * duration).toLong())
                    }
                    draggingFraction = null
                },
                enabled = canSeek,
                modifier = Modifier.fillMaxWidth()
            )

            state.error?.let { message ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = "Повторить подключение")
                    }
                    IconButton(onClick = onDismissError) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть сообщение об ошибке")
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val safe = (ms.coerceAtLeast(0L)) / 1000L
    val minutes = safe / 60
    val seconds = safe % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
