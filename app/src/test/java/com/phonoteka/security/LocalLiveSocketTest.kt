package com.phonoteka.security

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LocalLiveSocketTest {
    @Test fun signedResponseIsReceivedAndReconnectUsesFreshConnectionChannel() = runTest {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val signer = HmacTextFrameSigner(SecretKeySpec(ByteArray(32) { it.toByte() }, "HmacSHA256"))
        val channels = java.util.concurrent.CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        repeat(2) {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onClosing(socket: WebSocket, code: Int, reason: String) { socket.close(code, reason) }
                override fun onMessage(socket: WebSocket, text: String) {
                    val frame = SignedTextFrame.parse(text)!!
                    val channel = frame.channel.removeSuffix("/request")
                    channels += channel
                    socket.send(signer.sign("$channel/response", "local response").toWire())
                    if (channels.size == 1) socket.close(1000, "fixture reconnect")
                }
            }))
        }
        server.start()
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .certificatePinner(CertificatePinner.Builder().add("localhost", CertificatePinner.pin(cert.certificate)).build())
            .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS).build()
        val live = LocalLiveSocket(client, signer, backgroundScope, backoffMillis = 1, maxReconnects = 1)
        try {
            live.connect(server.url("/ws").newBuilder().host("localhost").build().toString())
            val result = live.metrics.first { it.received >= 2 }
            assertEquals(2, channels.size)
            assertNotEquals(channels[0], channels[1])
            assertEquals(1, result.reconnects)
        } finally {
            live.close()
            runCurrent() // Unwind the cancelled background job before blocking server shutdown.
            server.shutdown() // A cleanup failure remains a test failure, never swallowed.
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
    @Test fun reflectedRequestIsNotAcceptedAsSignedResponse() = runTest {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val server = MockWebServer(); server.useHttps(serverTls.sslSocketFactory(), false)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(socket: WebSocket, code: Int, reason: String) { socket.close(code, reason) }
            override fun onMessage(socket: WebSocket, text: String) { socket.send(text) }
        }))
        server.start()
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .certificatePinner(CertificatePinner.Builder().add("localhost", CertificatePinner.pin(cert.certificate)).build()).build()
        val signer = HmacTextFrameSigner(SecretKeySpec(ByteArray(32) { it.toByte() }, "HmacSHA256"))
        val live = LocalLiveSocket(client, signer, backgroundScope, maxReconnects = 0)
        try {
            live.connect(server.url("/ws").newBuilder().host("localhost").build().toString())
            val result = live.metrics.first { it.rejected == 1 && !it.connected }
            assertEquals(0, result.received)
        } finally {
            live.close()
            runCurrent() // Unwind the cancelled background job before blocking server shutdown.
            server.shutdown() // A cleanup failure remains a test failure, never swallowed.
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
