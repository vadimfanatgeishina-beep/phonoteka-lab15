package com.phonoteka.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Найденный на источнике кандидат для скачивания.
 * Связан с треком через внешний ключ.
 */
@Entity(
    tableName = "candidates",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("trackId")]
)
data class CandidateEntity(
    @PrimaryKey val id: String,          // videoId / streamId
    val trackId: Long,
    val artist: String,
    val title: String,
    val source: String,                  // "youtube" / "soundcloud"
    val durationSec: Int,
    val score: Int,                      // 0-100
    val isExplicit: Boolean,
    val url: String,                     // публичная страница (не прямой поток)
    val channelName: String = "",
    val blocked: Boolean = false,
    val version: String = "unknown",     // explicit / clean / unknown
    val thumbnailUrl: String? = null,
    val audioStreamUrl: String? = null   // прямая ссылка на аудиопоток (может протухать)
)

fun CandidateEntity.toDomain(): TrackCandidate = TrackCandidate(
    videoId = id,
    artist = artist,
    title = title,
    source = source,
    durationSec = durationSec,
    score = score,
    isExplicit = isExplicit,
    url = url,
    channelName = channelName
)

fun TrackCandidate.toEntity(trackId: Long): CandidateEntity = CandidateEntity(
    id = videoId,
    trackId = trackId,
    artist = artist,
    title = title,
    source = source,
    durationSec = durationSec,
    score = score,
    isExplicit = isExplicit,
    url = url,
    channelName = channelName
)
