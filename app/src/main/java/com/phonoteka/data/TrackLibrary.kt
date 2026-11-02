package com.phonoteka.data

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import java.io.File

/** Boundary for ViewModel tests (in-memory) and the real Room-backed repository. */
interface TrackLibrary {
    fun getAllTracks(): Flow<List<Track>>
    fun totalCount(): Flow<Int>
    fun doneCount(): Flow<Int>
    fun errorCount(): Flow<Int>
    fun reviewCount(): Flow<Int>
    suspend fun importFromAssets(): Int
    suspend fun importLocalFiles(uris: List<Uri>): LocalImportReport

    // --- Search & download from YouTube/SoundCloud ---

    /** Наблюдение за кандидатами конкретного трека. */
    fun getCandidates(trackId: Long): Flow<List<TrackCandidate>>

    /** Поиск трека на YouTube/SoundCloud, сохранение кандидатов в БД. */
    suspend fun searchTrack(trackId: Long, source: String = "both"): List<TrackCandidate>

    /** Ручной выбор кандидата для скачивания. */
    suspend fun selectCandidate(trackId: Long, candidateId: String)

    /** Автовыбор лучшего кандидата. */
    suspend fun autoSelectCandidate(trackId: Long)

    /** Скачивание выбранного кандидата с прогрессом. */
    suspend fun downloadTrack(trackId: Long, onProgress: (Long) -> Unit): File

    /** Сброс ошибочных треков обратно в pending. */
    suspend fun resetErrorsToPending()

    /** Добавление одного трека вручную. Возвращает id нового трека. */
    suspend fun addTrack(artist: String, title: String): Long

    /** Импорт треков из .txt файла (по одной строке "Исполнитель — Название"). Возвращает количество добавленных. */
    suspend fun importTextFile(uri: Uri): Int

    /** Очищает все треки из базы (отвязывает старый текстовик). */
    suspend fun clearAllTracks()
}

data class LocalImportReport(val linked: Int, val skipped: Int, val failed: Int) {
    val message: String
        get() = "Привязано: $linked. Пропущено: $skipped. Ошибок чтения/доступа: $failed."
}
