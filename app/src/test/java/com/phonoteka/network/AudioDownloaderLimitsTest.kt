package com.phonoteka.network
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AudioDownloaderLimitsTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun oversizeKnownAndChunkedBodiesPreserveOldDestinationAndRemovePartial() = runTest {
        for (chunked in listOf(false, true)) {
            fixture { server, client ->
                val response = MockResponse().setHeader("Content-Type", "audio/mpeg")
                if (chunked) response.setChunkedBody("x".repeat(64), 8) else response.setBody("x".repeat(64))
                server.enqueue(response)
                val destination = File(temporary.newFolder(), "audio.mp3").apply { writeText("original") }
                var rejected = false
                try { AudioDownloader(client, byteLimit = 32).download(server.url("/audio").toString(), destination) }
                catch (_: java.io.IOException) { rejected = true }
                assertTrue(rejected); assertEquals("original", destination.readText())
                assertFalse(File(destination.parentFile, destination.name + ".part").exists())
            }
        }
    }
    @Test fun oversizedPlaylistAndTlsToHttpRedirectAreRejected() = runTest {
        fixture { server, client ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody("#EXTM3U\n" + "x".repeat(64)))
            val target = File(temporary.newFolder(), "audio.mp3")
            var rejected = false
            try { AudioDownloader(client, playlistLimit = 32).download(server.url("/playlist").toString(), target) }
            catch (_: java.io.IOException) { rejected = true }
            assertTrue(rejected); assertFalse(target.exists())
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://localhost:1/not-contacted"))
            rejected = false
            try { AudioDownloader(client).download(server.url("/redirect").toString(), target) }
            catch (_: java.io.IOException) { rejected = true }
            assertTrue(rejected); assertFalse(target.exists())
        }
    }
    private suspend fun fixture(block: suspend (MockWebServer, OkHttpClient) -> Unit) {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val server = MockWebServer(); server.useHttps(serverTls.sslSocketFactory(), false); server.start()
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        try { block(server, client) }
        finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll(); server.shutdown() }
    }
}
