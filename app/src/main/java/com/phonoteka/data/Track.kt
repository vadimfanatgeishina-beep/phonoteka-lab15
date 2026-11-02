package com.phonoteka.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Статус трека в очереди загрузки.
 * Соответствует статусам из Python-версии (core.py).
 */
enum class TrackStatus(val storageKey: String) {
    PENDING("pending"),       // Ожидает поиска
    SEARCHING("searching"),    // Идёт поиск
    READY("ready"),            // Кандидат найден, готов к скачиванию
    DOWNLOADING("downloading"),// Идёт скачивание
    DONE("done"),              // Локальное аудио доступно при привязке; не полная проверка целостности
    ERROR("error"),            // Ошибка
    REVIEW("review"),          // Требует ручного выбора
    NOT_FOUND("not_found");    // Не найден на источниках

    companion object {
        fun from(key: String): TrackStatus =
            entries.find { it.storageKey == key } ?: PENDING
    }
}

/**
 * Модель трека — соответствует записи из musik.txt и строке в базе.
 */
@Parcelize
data class Track(
    val id: Long,
    val artist: String,
    val title: String,
    val status: TrackStatus = TrackStatus.PENDING,
    val filePath: String? = null,
    val source: String? = null,        // "local" for a validated SAF content URI
    val lastError: String? = null,
    val sha256: String? = null,
    val searchQuery: String = "",
    val durationSec: Int? = null,
    val candidateArtist: String? = null,
    val candidateTitle: String? = null,
    val selectedCandidateId: String? = null,
    val manual: Boolean = false,
    val audioValidationJson: String? = null
) : Parcelable {

    /** Полное название «Исполнитель — Название» для отображения. */
    val displayName: String
        get() = if (artist.isNotBlank() && title.isNotBlank())
            "$artist — $title"
        else if (artist.isNotBlank()) artist
        else if (title.isNotBlank()) title
        else searchQuery.ifBlank { "Без названия" }
}

/**
 * Кандидат на скачивание — найденный на источнике трек.
 */
@Parcelize
data class TrackCandidate(
    val videoId: String,
    val artist: String,
    val title: String,
    val source: String,           // "youtube" / "soundcloud"
    val durationSec: Int,
    val score: Int,               // 0-100, quality of match
    val isExplicit: Boolean,
    val url: String,
    val channelName: String = ""
) : Parcelable

/**
 * Тип ошибки сети — для дифференцированного отображения (Лаба 7).
 */
enum class ErrorType(val message: String) {
    STORAGE_ERROR("Не удалось прочитать локальные данные"),
    ACCESS_DENIED("Нет доступа к выбранному файлу"),
    INVALID_DATA("Некорректные данные фонотеки"),
    NO_NETWORK("Нет соединения с интернетом"),
    TIMEOUT("Время ожидания истекло"),
    SERVER_ERROR("Ошибка сервера"),
    AUTH_REQUIRED("Требуется вход в аккаунт"),
    RATE_LIMIT("Слишком много запросов, подождите"),
    UNKNOWN("Неизвестная ошибка")
}
