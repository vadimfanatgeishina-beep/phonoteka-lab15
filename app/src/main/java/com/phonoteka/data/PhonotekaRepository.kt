package com.phonoteka.data

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.phonoteka.network.AudioDownloader
import com.phonoteka.network.Id3Tagger
import com.phonoteka.network.StreamSearcher
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Room catalogue + поиск/скачивание через NewPipeExtractor. */
class PhonotekaRepository(
    private val dao: TrackDao,
    private val context: Context
) : TrackLibrary {
    private val importMutex = Mutex()
    private val searcher = StreamSearcher()
    private val downloader = AudioDownloader()

    /**
     * Флаг в SharedPreferences: если true, importFromAssets() больше не импортирует
     * встроенный musik.txt. Устанавливается после первой загрузки ИЛИ после очистки БД
     * пользователем. Это предотвращает возврат старых треков после "Очистить" → "Загрузить .txt".
     */
    private val prefs = context.getSharedPreferences("phonoteka_prefs", Context.MODE_PRIVATE)
    private val KEY_ASSETS_IMPORTED = "assets_imported"

    override suspend fun importFromAssets(): Int = withContext(Dispatchers.IO) {
        // После очистки БД или загрузки своего .txt — никогда не возвращаем старый musik.txt
        if (prefs.getBoolean(KEY_ASSETS_IMPORTED, false)) return@withContext 0
        importMutex.withLock {
            if (prefs.getBoolean(KEY_ASSETS_IMPORTED, false)) return@withLock 0
            // Only remove bindings carrying the exact legacy demo marker, never real files.
            dao.clearKnownDemoFiles()
            if (dao.countRows() != 0) {
                prefs.edit().putBoolean(KEY_ASSETS_IMPORTED, true).apply()
                return@withLock 0
            }
            val entities = context.assets.open("musik.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filter { it.isNotBlank() }.mapIndexed { index, line ->
                    val (artist, title) = LocalAudioMatcher.parseArtistTitle(line)
                    TrackEntity(artist = artist, title = title, searchQuery = line.trim(), orderIndex = index)
                }.toList()
            }
            check(entities.isNotEmpty()) { "Список musik.txt пуст" }
            val count = dao.insertInitialIfEmpty(entities)
            prefs.edit().putBoolean(KEY_ASSETS_IMPORTED, true).apply()
            count
        }
    }

    override fun getAllTracks(): Flow<List<Track>> = dao.getAllTracks().map { list -> list.map { it.toDomain() } }
    fun getTrackById(id: Long): Flow<Track?> = dao.getTrackById(id).map { it?.toDomain() }
    fun getTracksByStatus(status: TrackStatus): Flow<List<Track>> =
        dao.getByStatus(status.storageKey).map { list -> list.map { it.toDomain() } }
    fun search(query: String): Flow<List<Track>> = getAllTracks().map { tracks ->
        tracks.filter { it.displayName.contains(query.trim(), ignoreCase = true) }
    }
    override fun totalCount(): Flow<Int> = dao.totalCount()
    override fun doneCount(): Flow<Int> = dao.doneCount()
    override fun errorCount(): Flow<Int> = dao.errorCount()
    override fun reviewCount(): Flow<Int> = dao.reviewCount()

    /** Manual selection deliberately binds the selected file to this catalogue entry. */
    suspend fun attachLocalFile(trackId: Long, uri: Uri): Unit = withContext(Dispatchers.IO) {
        importFromAssets()
        importMutex.withLock {
            val audio = readAudio(uri)
            persistReadAccess(uri)
            check(dao.attachLocal(trackId, uri.toString(), audio.durationSec, audio.artist, audio.title) == 1) {
                "Запись трека больше не существует"
            }
        }
    }

    /** Match only a unique exact name, using tags or filename; do not overwrite a binding. */
    override suspend fun importLocalFiles(uris: List<Uri>): LocalImportReport = withContext(Dispatchers.IO) {
        importFromAssets()
        importMutex.withLock {
            val tracks = dao.snapshot().map { it.toDomain() }
            val index = LocalAudioMatcher.Index(tracks)
            val used = mutableSetOf<Long>()
            var linked = 0
            var skipped = 0
            var failed = 0
            for (uri in uris.distinct()) {
                coroutineContext.ensureActive()
                try {
                    val audio = readAudio(uri)
                    val track = index.match(audio.artist, audio.title, audio.name)
                    if (track == null || track.id in used ||
                        (track.source == "local" && track.filePath != uri.toString())) {
                        skipped++
                        continue
                    }
                    persistReadAccess(uri)
                    check(dao.attachLocal(track.id, uri.toString(), audio.durationSec, audio.artist, audio.title) == 1)
                    used += track.id
                    linked++
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Other successfully selected files remain usable, including after a partial failure.
                    failed++
                }
            }
            LocalImportReport(linked, skipped, failed)
        }
    }

    private data class Audio(val name: String, val artist: String?, val title: String?, val durationSec: Int)

    private fun readAudio(uri: Uri): Audio {
        require(uri.scheme == "content") { "Выберите аудио через системный выбор файлов" }
        val resolver = context.contentResolver
        resolver.openFileDescriptor(uri, "r")?.use { } ?: throw IOException("Файл недоступен")
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }.orEmpty()
        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(context, uri)
            val durationMs = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val hasAudio = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            if (durationMs <= 0 || !hasAudio) throw IOException("Не удалось прочитать аудиодорожку")
            return Audio(
                name,
                metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                (durationMs / 1000).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            )
        } finally {
            metadata.release()
        }
    }

    private fun persistReadAccess(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            throw IOException("Поставщик файлов не разрешил постоянный доступ. Выберите файл из памяти телефона.", e)
        }
    }

    override suspend fun resetErrorsToPending() = withContext(Dispatchers.IO) { dao.resetErrors() }

    override suspend fun addTrack(artist: String, title: String): Long = withContext(Dispatchers.IO) {
        importFromAssets()
        importMutex.withLock {
            val orderIdx = (dao.maxOrderIndex() ?: -1) + 1
            val entity = TrackEntity(
                artist = artist.trim(),
                title = title.trim(),
                searchQuery = "${artist.trim()} — ${title.trim()}",
                orderIndex = orderIdx
            )
            // Room возвращает rowId вставленной строки (= autoGenerate id)
            dao.insertSingle(entity)
        }
    }

    override suspend fun importTextFile(uri: Uri): Int = withContext(Dispatchers.IO) {
        // ВАЖНО: НЕ вызываем importFromAssets() здесь!
        // Если пользователь очистил БД и грузит новый .txt — он хочет только свои треки,
        // а не 1700 старых из musik.txt.
        importMutex.withLock {
            val existing = dao.snapshot().map { LocalAudioMatcher.normalize(it.artist + " " + it.title) }.toMutableSet()
            val lines = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readLines()
            } ?: return@withContext 0
            var added = 0
            var orderIdx = (dao.maxOrderIndex() ?: -1)
            for (line in lines) {
                if (line.isBlank()) continue
                val (artist, title) = LocalAudioMatcher.parseArtistTitle(line)
                if (artist.isBlank() || title.isBlank()) continue
                val key = LocalAudioMatcher.normalize("$artist $title")
                if (key in existing) continue
                orderIdx++
                dao.insertSingle(TrackEntity(
                    artist = artist, title = title,
                    searchQuery = line.trim(), orderIndex = orderIdx
                ))
                existing.add(key)
                added++
            }
            // Отмечаем, что пользователь загрузил свой файл — musik.txt больше не нужен
            prefs.edit().putBoolean(KEY_ASSETS_IMPORTED, true).apply()
            added
        }
    }

    /** Очищает все треки и кандидатов из базы (отвязывает старый текстовик). */
    override suspend fun clearAllTracks() = withContext(Dispatchers.IO) {
        importMutex.withLock {
            dao.clearAll()
            // Отмечаем, что musik.txt больше не нужен — importFromAssets() будет no-op
            // при обновлении, перезапуске приложения и т.д.
            prefs.edit().putBoolean(KEY_ASSETS_IMPORTED, true).apply()
        }
    }

    suspend fun updateStatus(trackId: Long, status: TrackStatus, error: String? = null) {
        dao.updateError(trackId, status.storageKey, error)
    }

    /** Поток кандидатов для конкретного трека. */
    override fun getCandidates(trackId: Long): Flow<List<TrackCandidate>> =
        dao.getCandidates(trackId).map { list -> list.map { it.toDomain() } }

    /**
     * Ищет кандидатов для трека и сохраняет в БД.
     * @param source "youtube", "soundcloud" или "both"
     */
    override suspend fun searchTrack(trackId: Long, source: String): List<TrackCandidate> = withContext(Dispatchers.IO) {
        val track = dao.snapshot().find { it.id == trackId }?.toDomain()
            ?: throw IllegalArgumentException("Запись не найдена")
        dao.updateError(trackId, TrackStatus.SEARCHING.storageKey, null)
        try {
            val candidates = searcher.search(track, source)
            dao.clearCandidates(trackId)
            if (candidates.isNotEmpty()) {
                dao.insertCandidates(candidates.map { it.toEntity(trackId) })
                val best = candidates.maxByOrNull { it.score }
                dao.updateCandidate(
                    trackId,
                    TrackStatus.READY.storageKey,
                    best?.artist,
                    best?.title
                )
            } else {
                dao.updateError(trackId, TrackStatus.NOT_FOUND.storageKey, "Не найдено ни одной записи")
            }
            candidates
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dao.updateError(trackId, TrackStatus.ERROR.storageKey, e.message ?: "Ошибка поиска")
            emptyList()
        }
    }

    /** Ручной выбор кандидата для скачивания. */
    override suspend fun selectCandidate(trackId: Long, candidateId: String) = withContext(Dispatchers.IO) {
        dao.selectCandidate(trackId, TrackStatus.READY.storageKey, candidateId, true)
    }

    /** Автовыбор лучшего кандидата. */
    override suspend fun autoSelectCandidate(trackId: Long) = withContext(Dispatchers.IO) {
        val candidates = dao.snapshotCandidates(trackId)
        val best = candidates.maxByOrNull { it.score }
        if (best != null) {
            dao.selectCandidate(trackId, TrackStatus.READY.storageKey, best.id, false)
        }
    }

    /**
     * Скачивает выбранного кандидата.
     * Если выбранный кандидат не отдал аудиопоток — перебирает остальных по убыванию score.
     * @param onProgress колбэк прогресса в байтах
     */
    override suspend fun downloadTrack(
        trackId: Long,
        onProgress: (bytes: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val track = dao.snapshot().find { it.id == trackId }?.toDomain()
            ?: throw IllegalArgumentException("Запись не найдена")

        // Все кандидаты: выбранный — первым, остальные по убыванию score
        val selectedId = track.selectedCandidateId
        val allCandidates = dao.snapshotCandidates(trackId).let { raw ->
            val selected = raw.find { it.id == selectedId }
            val rest = raw.filter { it.id != selectedId }.sortedByDescending { it.score }
            if (selected != null) listOf(selected) + rest else rest.sortedByDescending { it.score }
        }

        if (allCandidates.isEmpty()) {
            throw IllegalStateException("Нет кандидатов для скачивания. Сначала выполните поиск.")
        }

        dao.updateError(trackId, TrackStatus.DOWNLOADING.storageKey, null)

        var lastError: String? = null

        for (candidateEntity in allCandidates) {
            val candidate = candidateEntity.toDomain()
            try {
                val audioUrl = searcher.resolveAudioUrl(candidate)
                if (audioUrl == null) {
                    lastError = "Нет потоков: ${candidate.artist} — ${candidate.title}"
                    continue // Попробовать следующего кандидата
                }
                val extension = downloader.guessExtension(null, audioUrl)
                val safeName = "${track.artist} — ${track.title}".replace(Regex("""[^\w\s-]"""), "_").take(80)
                val dest = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads/${safeName}_$trackId.$extension")
                val result = downloader.download(audioUrl, dest, onProgress)
                // ID3-теги валидны ТОЛЬКО для MP3. Для M4A/MP4 они ломают проигрывание
                // (плеер видит "ID3" в начале и думает что это MP3).
                if (extension == "mp3") {
                    Id3Tagger.tag(result.file, track.artist, track.title)
                }
                val sha256 = result.file.inputStream().use { input ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
                dao.updateDone(
                    trackId,
                    TrackStatus.DONE.storageKey,
                    result.file.absolutePath,
                    candidate.source,
                    sha256,
                    null,
                    candidate.artist,
                    candidate.title
                )
                return@withContext result.file
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message}"
                // Продолжаем — попробуем следующего кандидата
            }
        }

        // Все кандидаты провалились
        val errorMsg = "Не удалось скачать ни от одного кандидата (${allCandidates.size} шт.). Последняя ошибка: $lastError"
        dao.updateError(trackId, TrackStatus.ERROR.storageKey, errorMsg)
        throw IOException(errorMsg)
    }
}
