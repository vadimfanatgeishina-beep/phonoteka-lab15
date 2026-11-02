package com.phonoteka.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonoteka.ui.components.VersionMenu

/** Главный экран: название, описание, статистика, переходы и меню ⋮. */
@Composable
fun HomeScreen(
    doneCount: Int,
    totalCount: Int,
    errorCount: Int,
    reviewCount: Int,
    onNavigateToList: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onAboutVersion: () -> Unit,
    onOpenDemo: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxSize().statusBarsPadding()) {
        VersionMenu(onAboutVersion, Modifier.align(Alignment.TopEnd).padding(4.dp), onOpenDemo)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)
        ) {
            Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                Text("Ф", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimary)
            }
            Text("Фонотека", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "Поиск и скачивание музыки с YouTube и SoundCloud. Скачано $doneCount из $totalCount треков.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Готово", doneCount, MaterialTheme.colorScheme.primaryContainer)
                StatCard("Ошибки", errorCount, MaterialTheme.colorScheme.errorContainer)
                StatCard("Проверка", reviewCount, MaterialTheme.colorScheme.tertiaryContainer)
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onNavigateToList, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null)
                Spacer(Modifier.width(8.dp)); Text("Перейти к списку треков")
            }
            OutlinedButton(onClick = onNavigateToSettings, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp)); Text("Настройки")
            }
        }
    }
}

@Composable
private fun StatCard(label: String, count: Int, bgColor: Color) {
    Column(
        modifier = Modifier.width(88.dp).background(bgColor, MaterialTheme.shapes.medium).padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(count.toString(), style = MaterialTheme.typography.headlineSmall, fontSize = 24.sp)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
