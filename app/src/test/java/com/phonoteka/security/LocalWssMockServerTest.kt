package com.phonoteka.security

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.spec.SecretKeySpec
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Local-only TLS/WebSocket fixture; no external host, account, or network is used. */
class LocalWssMockServerTest {
    @Test fun pinnedLocalWssAcceptsSignedFrame() {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        val verification = AtomicReference<FrameVerificationResult>()
        val socketFailure = AtomicReference<Throwable>()
        val frameReceived = CountDownLatch(1)
        val clientClosed = CountDownLatch(1)
        val serverClosed = CountDownLatch(1)
        var now = 1_800_000_000L
        val signer = HmacTextFrameSigner(SecretKeySpec("test-only-wss-key".toByteArray(), "HmacSHA256")) { now }
        val replayGuard = FrameReplayGuard()
        val server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason) // Reply to the client close; do not leave the reader waiting.
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { serverClosed.countDown() }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = SignedTextFrame.parse(text)
                verification.set(if (frame == null) FrameVerificationResult.INVALID_FRAME
                    else signer.verify(frame, "/lab15/events", replayGuard, now))
                frameReceived.countDown()
            }
        }))
        server.start()
        val client = OkHttpClient.Builder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .certificatePinner(CertificatePinner.Builder()
                .add("localhost", CertificatePinner.pin(certificate.certificate)).build())
            .build()
        var socket: WebSocket? = null
        try {
            val opened = CountDownLatch(1)
            // OkHttp's HttpUrl models the secure WebSocket handshake as HTTPS.
            val url = server.url("/lab15/events").newBuilder()
                .scheme("https")
                .host("localhost")
                .build()
            socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { opened.countDown() }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { clientClosed.countDown() }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    socketFailure.set(t)
                    opened.countDown()
                    clientClosed.countDown()
                }
            })
            assertTrue("WSS handshake did not finish", opened.await(5, TimeUnit.SECONDS))
            check(socketFailure.get() == null) { "WSS handshake failed: ${socketFailure.get()}" }
            assertTrue(socket.send(signer.sign("/lab15/events", "local test event").toWire()))
            assertTrue("signed frame not received", frameReceived.await(5, TimeUnit.SECONDS))
            assertEquals(FrameVerificationResult.ACCEPTED, verification.get())
            assertTrue("Close frame was not queued", socket.close(1000, "test complete"))
            assertTrue("Client did not finish close handshake", clientClosed.await(5, TimeUnit.SECONDS))
            assertTrue("Server did not finish close handshake", serverClosed.await(5, TimeUnit.SECONDS))
            assertTrue("Unexpected WSS transport failure", socketFailure.get() == null)
        } finally {
            socket?.cancel()
            try { server.shutdown() } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            } // Shutdown failures remain test failures, never swallowed.
        }
    }
}
