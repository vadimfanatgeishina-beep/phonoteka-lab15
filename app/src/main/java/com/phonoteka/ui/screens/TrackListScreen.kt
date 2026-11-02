package com.phonoteka.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import com.phonoteka.data.TrackStatus
import com.phonoteka.ui.components.TrackCard
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.testTag
import com.phonoteka.viewmodel.BatchOperationState
import com.phonoteka.viewmodel.ListUiState
import com.phonoteka.viewmodel.TrackListViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TrackListScreen(
    viewModel: TrackListViewModel,
    onTrackClick: (Long) -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
    // Injectable suspend gate: Compose's frame clock does not advance ordinary coroutine delay.
    awaitDraftDebounce: suspend () -> Unit = { delay(400) }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val statusFilter by viewModel.statusFilter.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle(initialValue = 0)
    val doneCount by viewModel.doneCount.collectAsStateWithLifecycle(initialValue = 0)
    val importing by viewModel.localImport.collectAsStateWithLifecycle()
    val batchState by viewModel.batchState.collectAsStateWithLifecycle()
    val addTrackState by viewModel.addTrackState.collectAsStateWithLifecycle()
    val savedAddTrackDraft by viewModel.addTrackDraft.collectAsStateWithLifecycle()
    val clearState by viewModel.clearState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        viewModel.importAudio(it)
    }
    // Text file picker — loads .txt file with tracks (user's main feature)
    val txtFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importTextFile(uri)
    }
    var showAddDialog by remember { mutableStateOf(false) }
    var artistInput by remember { mutableStateOf(savedAddTrackDraft.artist) }
    var titleInput by remember { mutableStateOf(savedAddTrackDraft.title) }
    var draftAutosaved by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(showAddDialog, artistInput, titleInput) {
        draftAutosaved = false
        if (showAddDialog && (artistInput.isNotBlank() || titleInput.isNotBlank())) {
            awaitDraftDebounce()
            viewModel.saveAddTrackDraft(artistInput, titleInput)
            draftAutosaved = true
        }
    }
    LaunchedEffect(savedAddTrackDraft) {
        if (artistInput.isBlank() && titleInput.isBlank() &&
            (savedAddTrackDraft.artist.isNotBlank() || savedAddTrackDraft.title.isNotBlank())) {
            artistInput = savedAddTrackDraft.artist
            titleInput = savedAddTrackDraft.title
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Треки · $doneCount/$totalCount") },
                actions = {
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, "Обновить") }
                    IconButton(onClick = onNavigateToSettings) { Icon(Icons.Default.Settings, "Настройки") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }, modifier = Modifier.testTag("add-track-open")) {
                Icon(Icons.Default.Add, contentDescription = "Добавить трек")
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).semantics { testTagsAsResourceId = true }) {
            // Поиск
            OutlinedTextField(
                value = searchQuery, onValueChange = viewModel::onSearchQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("track-search"),
                label = { Text("Исполнитель или песня") }, singleLine = true
            )
            // Фильтры
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "Все" to null,
                    "На устройстве" to TrackStatus.DONE,
                    "Ожидают" to TrackStatus.PENDING,
                    "Готовы к скачиванию" to TrackStatus.READY,
                    "Ошибки" to TrackStatus.ERROR
                ).forEach { (label, status) ->
                    FilterChip(
                        selected = statusFilter == status,
                        onClick = { viewModel.onStatusFilterChange(status) },
                        label = { Text(label) }
                    )
                }
            }

            // --- Кнопки пакетного поиска и скачивания ---
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.searchAndDownloadAll("both") },
                    enabled = !batchState.active,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Найти и скачать все", maxLines = 1)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { viewModel.searchAllPending("both") },
                    enabled = !batchState.active,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Поиск", maxLines = 1)
                }
                OutlinedButton(
                    onClick = { viewModel.downloadAllReady() },
                    enabled = !batchState.active,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Скачать", maxLines = 1)
                }
            }

            // Прогресс пакетной операции
            AnimatedVisibility(
                visible = batchState.active,
                enter = fadeIn(), exit = fadeOut()
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    if (batchState.total > 0) {
                        LinearProgressIndicator(
                            progress = { batchState.current.toFloat() / batchState.total },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = when (batchState.operation) {
                                "search" -> "Поиск: ${batchState.current}/${batchState.total}"
                                "download" -> "Скачивание: ${batchState.current}/${batchState.total}"
                                "search+download" -> "Обработка: ${batchState.current}/${batchState.total}"
                                else -> "${batchState.current}/${batchState.total}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                        TextButton(onClick = viewModel::cancelBatch) { Text("Отменить") }
                    }
                    if (batchState.currentTrackName.isNotBlank()) {
                        Text(
                            batchState.currentTrackName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }

            // Сообщение о результате пакетной операции
            batchState.message?.let { msg ->
                AnimatedVisibility(visible = !batchState.active, enter = fadeIn(), exit = fadeOut()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(msg, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = viewModel::clearBatchMessage) { Text("×") }
                    }
                }
            }

            // Локальный импорт
            OutlinedButton(
                onClick = { picker.launch(arrayOf("audio/*")) }, enabled = !importing.busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) { Text(if (importing.busy) "Читаю выбранные аудиофайлы…" else "Привязать локальные файлы") }
            if (importing.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            importing.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp, 8.dp))
            }

            // === Управление текстовиком (Лаба: загрузка/отвязка) ===
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Текстовик с треками",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            "Всего: $totalCount",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Загрузить новый текстовик
                        OutlinedButton(
                            onClick = { txtFilePicker.launch(arrayOf("text/plain")) },
                            modifier = Modifier.weight(1f),
                            enabled = !addTrackState.busy
                        ) {
                            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Загрузить .txt", maxLines = 1)
                        }
                        // Очистить базу (отвязать старый текстовик)
                        OutlinedButton(
                            onClick = { showClearConfirm = true }, 
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Очистить", maxLines = 1)
                        }
                    }
                    // Сообщение о результате импорта/очистки
                    addTrackState.message?.let { msg ->
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (addTrackState.trackId != null || msg.contains("Импортировано") || msg.contains("очищен"))
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                    clearState?.let { result ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                result.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (result.success) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                            )
                            TextButton(onClick = viewModel::clearClearMessage) { Text("×") }
                        }
                    }
                }
            }

            // Список
            val refreshing = (uiState as? ListUiState.Success)?.isRefreshing == true
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = viewModel::refresh,
                modifier = Modifier.weight(1f)) {
                when (val state = uiState) {
                    ListUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    is ListUiState.Empty -> LazyColumn(Modifier.fillMaxSize()) {
                        item { Text(state.message, modifier = Modifier.padding(24.dp)) }
                    }
                    is ListUiState.Error -> Column(Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        // Дифференцированная ошибка (Лаба 7): иконка + сообщение по типу ошибки
                        when (state.errorType) {
                            com.phonoteka.data.ErrorType.NO_NETWORK ->
                                Icon(Icons.Default.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            com.phonoteka.data.ErrorType.STORAGE_ERROR ->
                                Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            else ->
                                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                        if (state.retryCount > 0) {
                            Text(
                                "Попытка №${state.retryCount}. Следующая через ${state.nextRetryDelayMs / 1000}с",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = viewModel::retry) { Text("Повторить") }
                    }
                    is ListUiState.Success -> Box(Modifier.fillMaxSize()) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("track-list").semantics {
                            collectionInfo = CollectionInfo(state.totalMatches, 1)
                        }) {
                            stickyHeader {
                                Surface(color = MaterialTheme.colorScheme.surface) {
                                    Text("Найдено: ${state.totalMatches}", modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp))
                                }
                            }
                            itemsIndexed(state.tracks, key = { _, track -> track.id }) { index, track ->
                                TrackCard(track = track, index = index, onClick = { onTrackClick(track.id) },
                                    modifier = Modifier
                                        .semantics { collectionItemInfo = CollectionItemInfo(index, 0, 0, 1) }
                                        .testTag("track-row-${track.id}")
                                        .animateItem())
                                // Разделитель между элементами (Лаба 5)
                                if (index < state.tracks.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                        thickness = 0.5.dp
                                    )
                                }
                            }
                            if (state.tracks.size < state.totalMatches) {
                                item {
                                    // Индикатор загрузки и кнопка "показать ещё" (Лаба 5)
                                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                        TextButton(onClick = viewModel::loadMore) {
                                            Text("Показать ещё 100")
                                        }
                                    }
                                }
                            } else {
                                // "Больше нет данных" (Лаба 5)
                                item {
                                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                        Text(
                                            "— Больше нет данных —",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            item { Spacer(Modifier.height(16.dp)) }
                        }
                        // Overlay-обновление: полупрозрачный индикатор поверх списка (Лаба 7)
                        if (state.isRefreshing) {
                            Box(
                                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Spacer(Modifier.height(8.dp))
                                    Text("Обновление…", color = Color.White)
                                }
                            }
                        }
                    }
                }
            }

            // Сообщение о добавлении трека
            addTrackState.message?.let { msg ->
                if (!addTrackState.busy) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(msg, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = viewModel::clearAddTrackMessage) { Text("×") }
                    }
                }
            }
        }
    }

    // Диалог добавления трека
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                viewModel.clearAddTrackMessage()
            },
            title = { Text("Добавить трек") },
            text = {
                Column {
                    OutlinedTextField(
                        value = artistInput,
                        onValueChange = { artistInput = it },
                        label = { Text("Исполнитель") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("add-track-artist")
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { titleInput = it },
                        label = { Text("Название") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("add-track-title")
                    )
                    if (draftAutosaved) {
                        Text("Черновик сохранён", modifier = Modifier.testTag("add-track-draft-saved"),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    if (addTrackState.busy) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    addTrackState.message?.let { msg ->
                        Spacer(Modifier.height(8.dp))
                        Text(msg, style = MaterialTheme.typography.bodySmall,
                            color = if (addTrackState.trackId != null)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            showAddDialog = false
                            txtFilePicker.launch(arrayOf("text/plain"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Импорт из .txt файла")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.addTrack(artistInput, titleInput)
                        if (artistInput.isNotBlank() && titleInput.isNotBlank()) {
                            artistInput = ""
                            titleInput = ""
                            viewModel.clearAddTrackDraft()
                            // Закрываем диалог с небольшой задержкой, чтобы показать сообщение
                            showAddDialog = false
                        }
                    },
                    enabled = !addTrackState.busy && artistInput.isNotBlank() && titleInput.isNotBlank()
                ) { Text("Добавить") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddDialog = false
                    artistInput = ""
                    titleInput = ""
                    viewModel.clearAddTrackDraft()
                    viewModel.clearAddTrackMessage()
                }) { Text("Отмена") }
            }
        )
    }

    // Диалог подтверждения очистки базы (отвязка текстовика)
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Очистить базу треков?") },
            text = {
                Text("Будут удалены все $totalCount треков из базы. " +
                    "Это отвяжет старый текстовик. После этого можно загрузить новый .txt файл. " +
                    "Скачанные аудиофайлы на устройстве не удаляются.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        viewModel.clearAllTracks()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Очистить") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Отмена") }
            }
        )
    }
}
