package com.phonoteka.network

import com.phonoteka.data.Track
import com.phonoteka.data.TrackCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * Поиск треков на YouTube и SoundCloud через NewPipeExtractor.
 * Возвращает список кандидатов с метаданными и оценкой совпадения
 * на основе консервативного матчинга (порт matching.py).
 */
class StreamSearcher {

    init {
        try {
            NewPipe.init(OkHttpDownloader())
        } catch (_: IllegalStateException) {
            // already initialized
        }
    }

    /**
     * Ищет кандидатов для одного трека.
     * Блокированные (remix/cover/live/mashup и т.д.) помечаются score=0,
     * но остаются в списке для ручного выбора.
     * @param source "youtube", "soundcloud" или "both"
     */
    suspend fun search(track: Track, source: String = "both"): List<TrackCandidate> = withContext(Dispatchers.IO) {
        val query = buildQuery(track)
        val results = mutableListOf<TrackCandidate>()

        val sources = when (source) {
            "youtube" -> listOf(ServiceList.YouTube)
            "soundcloud" -> listOf(ServiceList.SoundCloud)
            else -> listOf(ServiceList.YouTube, ServiceList.SoundCloud)
        }

        for (svc in sources) {
            try {
                val queryHandler = svc.searchQHFactory.fromQuery(query)
                val info = SearchInfo.getInfo(svc, queryHandler)
                for (item in info.relatedItems) {
                    if (item !is StreamInfoItem || item.streamType == StreamType.LIVE_STREAM) continue
                    val candidate = item.toCandidate(track, svc.serviceInfo.name.lowercase())
                    if (candidate != null) results.add(candidate)
                }
            } catch (_: Exception) {
                // Один источник может упасть — продолжаем с другим.
            }
        }
        results.distinctBy { it.videoId }.sortedByDescending { it.score }
    }

    /**
     * Достаёт прямую ссылку на лучший аудиопоток для кандидата.
     * Сортирует потоки по битрейту (наивысший первым).
     * Логирует ошибки в logcat для отладки.
     */
    suspend fun resolveAudioUrl(candidate: TrackCandidate): String? = withContext(Dispatchers.IO) {
        try {
            val service = when (candidate.source) {
                "soundcloud" -> ServiceList.SoundCloud
                else -> ServiceList.YouTube
            }
            val info = StreamInfo.getInfo(service, candidate.url)
            // Сортируем по битрейту — берём наилучшее качество
            val audioUrl = info.audioStreams
                .sortedByDescending { it.bitrate }
                .firstOrNull()?.url
                ?: info.videoOnlyStreams
                    .sortedByDescending { it.bitrate }
                    .firstOrNull()?.url
                ?: info.videoStreams
                    .sortedByDescending { it.bitrate }
                    .firstOrNull()?.url
            if (audioUrl == null) {
                android.util.Log.w("StreamSearcher",
                    "No playable streams from ${candidate.source} — audio=${info.audioStreams.size}, " +
                    "videoOnly=${info.videoOnlyStreams.size}, " +
                    "video=${info.videoStreams.size}")
            }
            audioUrl
        } catch (e: Exception) {
            android.util.Log.e("StreamSearcher",
                "resolveAudioUrl failed for ${candidate.source}: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun buildQuery(track: Track): String {
        val artist = track.artist.trim()
        val title = Matching.originalTitle(track.title).ifEmpty { track.title.trim() }
        return if (artist.isNotBlank() && title.isNotBlank()) "$artist $title" else track.searchQuery
    }

    @Suppress("DEPRECATION")
    private fun StreamInfoItem.toCandidate(track: Track, source: String): TrackCandidate? {
        val url = url ?: return null
        val videoId = url.substringAfterLast("/", "").substringAfter("?v=", "").takeIf { it.isNotBlank() } ?: url
        val candArtist = uploaderName.orEmpty()
        val candTitle = name.orEmpty()

        val eval = Matching.evaluateCandidate(
            trackArtist = track.artist,
            trackTitle = track.title,
            candidateTitle = candTitle,
            candidateUploader = candArtist,
            candidateArtist = null, // NewPipeExtractor не всегда даёт отдельного artist
            durationSec = if (duration > 0) duration else null
        )

        return TrackCandidate(
            videoId = videoId,
            artist = candArtist,
            title = candTitle,
            source = source,
            durationSec = if (duration > 0) duration.toInt() else 0,
            score = if (eval.blocked) 0 else eval.score,
            isExplicit = eval.version == "explicit",
            url = url,
            channelName = uploaderName.orEmpty()
        )
    }
}
