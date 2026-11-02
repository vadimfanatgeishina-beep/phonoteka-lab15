package com.phonoteka.network

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * NewPipeExtractor требует реализации [Downloader].
 * Используем OkHttp для запросов к YouTube/SoundCloud.
 */
class OkHttpDownloader : Downloader() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), request.dataToSend()?.toRequestBody())

        request.headers().forEach { (key, values) ->
            values.forEach { value -> builder.addHeader(key, value) }
        }

        val okRequest = builder.build()
        val call = client.newCall(okRequest)
        val response = try {
            call.execute()
        } catch (e: IOException) {
            throw IOException("Network request failed for ${okRequest.url.host}", e)
        }

        val body = response.body?.string().orEmpty()
        val responseHeaders = response.headers.toMultimap()
            .mapValues { it.value.toMutableList() }
            .toMutableMap()

        val latestUrl = response.request.url.toString()
        if (response.code == 429) {
            throw ReCaptchaException("rate limited", latestUrl)
        }

        return Response(
            response.code,
            response.message,
            responseHeaders,
            body,
            latestUrl
        )
    }
}
