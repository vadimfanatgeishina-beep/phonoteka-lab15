package com.phonoteka.ui.components

import androidx.compose.foundation.background
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.phonoteka.data.TrackStatus

/**
 * Бейдж статуса трека — цветной кружок с иконкой.
 */
@Composable
fun StatusBadge(status: TrackStatus, modifier: Modifier = Modifier) {
    val transition = updateTransition(targetState = status, label = "trackStatus")
    val color by transition.animateColor(label = "statusColor") { statusVisuals(it).first }
    val scale by transition.animateFloat(label = "statusScale") {
        if (it == TrackStatus.SEARCHING || it == TrackStatus.DOWNLOADING) 1.08f else 1f
    }
    val icon = statusVisuals(status).second
    Box(
        modifier = modifier
            .size(28.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(color.copy(alpha = 0.15f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = StatusText(status),
            tint = color,
            modifier = Modifier.size(16.dp)
        )
    }
}

fun StatusText(status: TrackStatus): String = when (status) {
    TrackStatus.DONE -> "Локальный файл"
    TrackStatus.PENDING -> "Ожидает"
    TrackStatus.SEARCHING -> "Идёт поиск"
    TrackStatus.READY -> "Готов к загрузке"
    TrackStatus.DOWNLOADING -> "Скачивается"
    TrackStatus.ERROR -> "Ошибка"
    TrackStatus.REVIEW -> "На проверке"
    TrackStatus.NOT_FOUND -> "Не найден"
}

private fun statusVisuals(status: TrackStatus): Pair<Color, ImageVector> = when (status) {
    TrackStatus.DONE -> Color(0xFF21735A) to Icons.Default.CheckCircle
    TrackStatus.PENDING -> Color(0xFF736F7F) to Icons.Default.Download
    TrackStatus.SEARCHING -> Color(0xFF6451C5) to Icons.Default.Search
    TrackStatus.READY -> Color(0xFF946320) to Icons.Default.Download
    TrackStatus.DOWNLOADING -> Color(0xFF6451C5) to Icons.Default.Download
    TrackStatus.ERROR -> Color(0xFFC0392B) to Icons.Default.Error
    TrackStatus.REVIEW -> Color(0xFF946320) to Icons.Default.Visibility
    TrackStatus.NOT_FOUND -> Color(0xFF736F7F) to Icons.Default.CloudOff
}
