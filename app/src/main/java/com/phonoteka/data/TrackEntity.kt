package com.phonoteka.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Сущность Room — соответствует строке из Python library.sqlite3.
 */
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val artist: String,
    val title: String,
    val status: String = "pending",
    val filePath: String? = null,
    val source: String? = null,
    val lastError: String? = null,
    val sha256: String? = null,
    val searchQuery: String = "",
    val durationSec: Int? = null,
    val candidateArtist: String? = null,
    val candidateTitle: String? = null,
    val selectedCandidateId: String? = null,  // выбранный кандидат
    val manual: Boolean = false,              // ручной выбор кандидата
    val audioValidationJson: String? = null,  // JSON с результатом проверки аудио
    val orderIndex: Int = 0          // Порядок из musik.txt
)

/** Мапперы Entity ↔ Domain */
fun TrackEntity.toDomain(): Track = Track(
    id = id,
    artist = artist,
    title = title,
    status = TrackStatus.from(status),
    filePath = filePath,
    source = source,
    lastError = lastError,
    sha256 = sha256,
    searchQuery = searchQuery,
    durationSec = durationSec,
    candidateArtist = candidateArtist,
    candidateTitle = candidateTitle,
    selectedCandidateId = selectedCandidateId,
    manual = manual,
    audioValidationJson = audioValidationJson
)

fun Track.toEntity(orderIdx: Int = 0): TrackEntity = TrackEntity(
    id = id,
    artist = artist,
    title = title,
    status = status.storageKey,
    filePath = filePath,
    source = source,
    lastError = lastError,
    sha256 = sha256,
    searchQuery = searchQuery,
    durationSec = durationSec,
    candidateArtist = candidateArtist,
    candidateTitle = candidateTitle,
    selectedCandidateId = selectedCandidateId,
    manual = manual,
    audioValidationJson = audioValidationJson,
    orderIndex = orderIdx
)
