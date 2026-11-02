package com.phonoteka.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Скачивание аудиопотока по прямой ссылке в файл.
 * Поддерживает:
 * - Progressive HTTP (прямая ссылка на аудио)
 * - HLS (M3U8 плейлист — скачивает сегменты и склеивает их)
 * Поддерживает отмену и прогресс через колбэк.
 */
class AudioDownloader(
    injectedClient: OkHttpClient? = null,
    private val byteLimit: Long = 200L * 1024 * 1024,
    private val playlistLimit: Int = 2 * 1024 * 1024,
    private val segmentLimit: Int = 2000
) {

    private val client = (injectedClient ?: OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()).newBuilder().followSslRedirects(false).build()

    data class Result(
        val file: File,
        val bytes: Long,
        val contentType: String?
    )

    /**
     * Скачивает [audioUrl] в [destination].
     * Автоматически определяет HLS-плейлист и обрабатывает его.
     * @param onProgress вызывается примерно каждые 250 мс с текущим количеством скачанных байт.
     */
    suspend fun download(
        audioUrl: String,
        destination: File,
        onProgress: (bytes: Long) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        val parsed = runCatching { URL(audioUrl) }.getOrElse { throw IOException("Некорректная ссылка") }
        if (parsed.protocol != "https" || parsed.host.isBlank()) throw IOException("Разрешены только HTTPS-ссылки")
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, destination.name + ".part")
        partial.delete()
        try {
            val result = downloadToFile(audioUrl, partial, onProgress)
            val backup = File(destination.parentFile, destination.name + ".previous")
            backup.delete()
            val hadDestination = destination.exists()
            if (hadDestination && !destination.renameTo(backup)) throw IOException("Не удалось подготовить замену файла")
            if (!partial.renameTo(destination)) {
                if (hadDestination) backup.renameTo(destination)
                throw IOException("Не удалось завершить сохранение файла")
            }
            backup.delete()
            result.copy(file = destination)
        } catch (e: Exception) {
            partial.delete()
            throw e
        }
    }

    private suspend fun downloadToFile(
        audioUrl: String,
        destination: File,
        onProgress: (bytes: Long) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        destination.parentFile?.mkdirs()
        val request = Request.Builder().url(audioUrl).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("HTTP ${response.code} при скачивании аудио")
        }
        val body = response.body ?: throw IOException("Пустой ответ от источника")
        if (body.contentLength() > byteLimit) {
            response.close()
            throw IOException("Файл превышает лимит 200 МБ")
        }
        val contentType = body.contentType()?.toString() ?: ""

        // Быстрая проверка: это HLS-плейлист?
        // Content-type может быть "application/vnd.apple.mpegurl", "audio/mpegurl" и т.п.
        val isHlsByType = contentType.contains("mpegurl") || contentType.contains("m3u8")

        if (isHlsByType) {
            val playlistText = readLimitedText(body, response)
            return@withContext downloadHls(playlistText, audioUrl, destination, onProgress)
        }

        // Peek первых байт — может быть HLS без правильного content-type
        val source = body.source()
        if (source.request(7)) {
            val prefix = source.peek().readUtf8(7)
            if (prefix == "#EXTM3U") {
                val playlistText = readLimitedText(body, response)
                return@withContext downloadHls(playlistText, audioUrl, destination, onProgress)
            }
        }

        // Обычное скачивание (progressive stream)
        val total = body.contentLength()
        destination.outputStream().use { output ->
            body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var downloaded = 0L
                var lastEmit = System.currentTimeMillis()
                while (coroutineContext.isActive) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (downloaded > byteLimit) throw IOException("Файл превышает лимит 200 МБ")
                    val now = System.currentTimeMillis()
                    if (now - lastEmit > 250) {
                        onProgress(downloaded)
                        lastEmit = now
                    }
                }
                onProgress(downloaded)
                if (!coroutineContext.isActive) {
                    throw CancellationException("Скачивание отменено")
                }
                return@withContext Result(destination, downloaded, contentType)
            }
        }
    }

    /**
     * Скачивает HLS-плейлист: парсит M3U8, скачивает сегменты и склеивает их в один файл.
     * Поддерживает master playlist (с вариантами) и media playlist (со сегментами).
     */
    private suspend fun downloadHls(
        playlistText: String,
        playlistUrl: String,
        destination: File,
        onProgress: (bytes: Long) -> Unit,
        depth: Int = 0
    ): Result {
        if (depth >= 4) throw IOException("Слишком много уровней HLS playlist")
        if (playlistText.length > playlistLimit) throw IOException("HLS playlist превышает допустимый размер")

        val lines = playlistText.lines()

        // Проверка шифрования
        if (lines.any { it.startsWith("#EXT-X-KEY") }) {
            throw IOException("HLS playlist использует шифрование — не поддерживается")
        }

        // Master playlist? (содержит #EXT-X-STREAM-INF → указывает на variant playlist)
        val variantIndex = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
        if (variantIndex >= 0) {
            val variantUrl = lines.getOrNull(variantIndex + 1)
                ?.takeIf { it.isNotBlank() && !it.startsWith("#") }
                ?: throw IOException("Master HLS playlist не содержит variant URL")

            val absoluteUrl = resolveUrl(variantUrl, playlistUrl)
            if (!absoluteUrl.startsWith("https://")) throw IOException("HLS playlist содержит небезопасную ссылку")

            val variantResponse = client.newCall(Request.Builder().url(absoluteUrl).build()).execute()
            if (!variantResponse.isSuccessful) {
                variantResponse.close()
                throw IOException("HTTP ${variantResponse.code} для variant playlist")
            }
            val variantBody = variantResponse.body ?: throw IOException("Пустой variant playlist")
            val variantText = readLimitedText(variantBody, variantResponse)

            return downloadHls(variantText, absoluteUrl, destination, onProgress, depth + 1)
        }

        // Media playlist — извлекаем URL сегментов
        val segmentUrls = lines
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { resolveUrl(it, playlistUrl) }

        if (segmentUrls.any { !it.startsWith("https://") }) throw IOException("HLS playlist содержит небезопасный сегмент")

        if (segmentUrls.isEmpty()) {
            throw IOException("HLS playlist не содержит сегментов")
        }
        if (segmentUrls.size > segmentLimit) throw IOException("Слишком много HLS-сегментов")

        // Парсим #EXT-X-MAP — init-сегмент (содержит ftyp+moov для fMP4).
        // БЕЗ него склеенный файл непроигрываем: есть только moof/mdat, нет заголовка.
        val mapLine = lines.firstOrNull { it.startsWith("#EXT-X-MAP") }
        val initUrl = mapLine
            ?.let { Regex("""URI="([^"]+)"""").find(it)?.groupValues?.get(1) }
            ?.let { resolveUrl(it, playlistUrl) }
        if (initUrl != null && !initUrl.startsWith("https://")) throw IOException("HLS init-сегмент использует небезопасную ссылку")
        val initByteRange = mapLine
            ?.let { Regex("""BYTERANGE="(\d+)(?:@(\d+))?"""").find(it) }

        android.util.Log.i("AudioDownloader",
            "Found ${segmentUrls.size} segments" +
            (if (initUrl != null) " + init segment" else " (NO init segment!)") +
            ", downloading...")

        // Скачиваем и склеиваем: init-сегмент ПЕРВЫМ, затем media-сегменты
        var downloaded = 0L
        var lastEmit = System.currentTimeMillis()
        destination.outputStream().use { output ->
            // Init-сегмент (ftyp+moov) — обязателен для проигрывания fMP4
            if (initUrl != null) {
                if (!coroutineContext.isActive) throw CancellationException("Скачивание отменено")
                val reqBuilder = Request.Builder().url(initUrl)
                initByteRange?.let { br ->
                    val len = br.groupValues[1].toInt()
                    val start = if (br.groupValues[2].isNotEmpty()) br.groupValues[2].toLong() else 0
                    reqBuilder.header("Range", "bytes=$start-${start + len - 1}")
                }
                val initResp = client.newCall(reqBuilder.build()).execute()
                if (!initResp.isSuccessful && initResp.code != 206) {
                    initResp.close()
                    throw IOException("HTTP ${initResp.code} для init-сегмента")
                }
                initResp.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read: Int
                    while (coroutineContext.isActive) {
                        read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded > byteLimit) throw IOException("Загрузка превышает лимит 200 МБ")
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 250) { onProgress(downloaded); lastEmit = now }
                    }
                }
                initResp.close()
                android.util.Log.i("AudioDownloader", "Init segment downloaded (${downloaded} bytes)")
            }
            for ((index, segUrl) in segmentUrls.withIndex()) {
                if (!coroutineContext.isActive) {
                    throw CancellationException("Скачивание отменено")
                }
                val segResponse = client.newCall(Request.Builder().url(segUrl).build()).execute()
                if (!segResponse.isSuccessful) {
                    segResponse.close()
                    throw IOException("HTTP ${segResponse.code} для сегмента ${index + 1}/${segmentUrls.size}")
                }
                segResponse.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read: Int
                    while (coroutineContext.isActive) {
                        read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded > byteLimit) throw IOException("Загрузка превышает лимит 200 МБ")
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 250) {
                            onProgress(downloaded)
                            lastEmit = now
                        }
                    }
                }
                segResponse.close()
            }
        }

        onProgress(downloaded)
        if (!coroutineContext.isActive) {
            throw CancellationException("Скачивание отменено")
        }

        // HLS аудио-сегменты YouTube — это AAC в fMP4/MPEG-TS контейнере
        return Result(destination, downloaded, "audio/mp4")
    }

    /** Разрешает относительный URL относительно базового URL плейлиста. */
    private fun resolveUrl(maybeRelative: String, baseUrl: String): String {
        if (maybeRelative.startsWith("http")) return maybeRelative
        val base = baseUrl.substringBeforeLast("/", "")
        return if (maybeRelative.startsWith("/")) {
            val protocol = baseUrl.substringBefore("://")
            val host = baseUrl.substringAfter("://").substringBefore("/")
            "$protocol://$host$maybeRelative"
        } else {
            "$base/$maybeRelative"
        }
    }

    /** Определяет расширение файла из contentType или URL. */
    fun guessExtension(contentType: String?, url: String): String {
        // HLS-плейлисты — результат это AAC в fMP4
        if (url.contains(".m3u8") || (contentType?.contains("mpegurl") == true)) {
            return "m4a"
        }
        val fromType = when {
            contentType == null -> null
            "audio/mpeg" in contentType || "mp3" in contentType -> "mp3"
            "audio/mp4" in contentType || "m4a" in contentType -> "m4a"
            "audio/webm" in contentType || "webm" in contentType -> "webm"
            "audio/ogg" in contentType || "opus" in contentType -> "ogg"
            "audio/flac" in contentType -> "flac"
            else -> null
        }
        return fromType ?: run {
            val path = url.substringBefore("?").substringBefore("&")
            when (path.substringAfterLast(".", "").lowercase()) {
                "mp3" -> "mp3"
                "m4a", "mp4" -> "m4a"
                "webm" -> "webm"
                "ogg", "opus" -> "ogg"
                "flac" -> "flac"
                "m3u8" -> "m4a"
                else -> "m4a" // по умолчанию для аудио
            }
        }
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 8192
        private const val MAX_DOWNLOAD_BYTES = 200L * 1024 * 1024
        private const val MAX_PLAYLIST_BYTES = 2 * 1024 * 1024
        private const val MAX_HLS_SEGMENTS = 2_000
    }

    private fun readLimitedText(body: okhttp3.ResponseBody, response: okhttp3.Response): String {
        try {
            if (body.contentLength() > playlistLimit) throw IOException("HLS playlist превышает допустимый размер")
            val output = java.io.ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > playlistLimit) throw IOException("HLS playlist превышает допустимый размер")
                    output.write(buffer, 0, count)
                }
            }
            return output.toString(Charsets.UTF_8.name())
        } finally {
            response.close()
        }
    }
}
