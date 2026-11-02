package com.phonoteka.security

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SocketUserBindingTest {
    private val master = ByteArray(32) { (it * 7).toByte() }
    private val keys = UserBoundSocketKeys(master)
    private var now = 1_000L
    private val codec = SocketTicketCodec(keys, { now })
    private val alice = SocketPrincipal("alice", 3)
    private val bob = SocketPrincipal("bob", 1)

    @Test fun validTicketIsAcceptedOnceForItsChannel() {
        val channel = UserBoundSocketKeys.channelFor(alice)
        val ticket = codec.issue(alice, channel)
        val guard = FrameReplayGuard()
        val check = codec.verify(ticket, channel, guard)
        assertEquals(TicketVerification.ACCEPTED, check.result)
        assertEquals(alice, check.principal)
        assertEquals(TicketVerification.REPLAY, codec.verify(ticket, channel, guard).result)
    }

    @Test fun ticketForAnotherChannelIsRejected() {
        val ticket = codec.issue(alice, UserBoundSocketKeys.channelFor(alice))
        assertEquals(TicketVerification.BAD_SIGNATURE, codec.verify(ticket, UserBoundSocketKeys.channelFor(alice), FrameReplayGuard()).result)
    }

    @Test fun ticketCannotBeUsedOnAnotherUsersChannel() {
        val bobChannel = UserBoundSocketKeys.channelFor(bob)
        val ticket = codec.issue(alice, bobChannel)
        assertEquals(TicketVerification.CHANNEL_MISMATCH, codec.verify(ticket, bobChannel, FrameReplayGuard()).result)
    }

    @Test fun tamperedUserOrSessionVersionIsRejected() {
        val channel = UserBoundSocketKeys.channelFor(alice)
        val parts = codec.issue(alice, channel).split('.').toMutableList()
        parts[2] = "4"
        assertEquals(TicketVerification.BAD_SIGNATURE, codec.verify(parts.joinToString("."), channel, FrameReplayGuard()).result)
        parts[2] = "3"; parts[1] = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("bob".toByteArray())
        assertEquals(TicketVerification.BAD_SIGNATURE, codec.verify(parts.joinToString("."), channel, FrameReplayGuard()).result)
    }

    @Test fun expiredTicketIsRejected() {
        val channel = UserBoundSocketKeys.channelFor(alice)
        val ticket = codec.issue(alice, channel)
        now += 31
        assertEquals(TicketVerification.EXPIRED, codec.verify(ticket, channel, FrameReplayGuard()).result)
    }

    @Test fun ticketFromAnotherMasterKeyIsRejected() {
        val channel = UserBoundSocketKeys.channelFor(alice)
        val foreign = SocketTicketCodec(UserBoundSocketKeys(ByteArray(32) { 1 }), { now }).issue(alice, channel)
        assertEquals(TicketVerification.BAD_SIGNATURE, codec.verify(foreign, channel, FrameReplayGuard()).result)
    }

    @Test fun malformedTicketsAreRejected() {
        val channel = UserBoundSocketKeys.channelFor(alice)
        listOf("", "v1", "v2.a.1.2.nonce_nonce_nonce_1." + "0".repeat(64), "v1.!!.1.2.nonce_nonce_nonce_1." + "0".repeat(64),
            "v1.YWxpY2U.-1.2.nonce_nonce_nonce_1." + "0".repeat(64), "x".repeat(600)).forEach {
            assertEquals(it, TicketVerification.MALFORMED, codec.verify(it, channel, FrameReplayGuard()).result)
        }
    }

    @Test fun frameSignedForAliceIsRejectedWithBobsKey() {
        val channel = UserBoundSocketKeys.channelFor(alice) + "/response"
        val frame = keys.frameSigner("alice", { now }).sign(channel, "secret metric")
        assertEquals(FrameVerificationResult.ACCEPTED, keys.frameSigner("alice", { now }).verify(frame, channel, FrameReplayGuard(), now))
        assertNotEquals(FrameVerificationResult.ACCEPTED, keys.frameSigner("bob", { now }).verify(frame, channel, FrameReplayGuard(), now))
    }

    @Test fun channelHidesUserIdAndDiffersPerUser() {
        val a = UserBoundSocketKeys.channelFor(alice); val b = UserBoundSocketKeys.channelFor(bob)
        assertFalse(a.contains("alice"))
        assertNotEquals(a.substringBeforeLast('/'), b.substringBeforeLast('/'))
        assertTrue(a.matches(Regex("/lab15/u/[a-f0-9]{16}/[a-f0-9-]{36}")))
    }

    @Test fun invalidPrincipalAndShortMasterAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { SocketPrincipal("../admin", 1) }
        assertThrows(IllegalArgumentException::class.java) { SocketPrincipal("alice", -1) }
        assertThrows(IllegalArgumentException::class.java) { UserBoundSocketKeys(ByteArray(8)) }
    }

    // ---- интеграция с LocalLiveSocket через MockWebServer (сервер проверяет билет) ----

    private class Fixture(val server: MockWebServer, val client: OkHttpClient)
    private fun fixture(): Fixture {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val server = MockWebServer(); server.useHttps(serverTls.sslSocketFactory(), false)
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .certificatePinner(CertificatePinner.Builder().add("localhost", CertificatePinner.pin(cert.certificate)).build())
            .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS).build()
        return Fixture(server, client)
    }
    private fun Fixture.url() = server.url("/ws").newBuilder().host("localhost").build().toString()
    private fun Fixture.shutdown() { server.shutdown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    private fun realCodec() = SocketTicketCodec(keys)

    @Test fun boundSocketSendsVerifiableTicketAndUsesPerUserFrameKey() = runTest {
        val f = fixture()
        val serverGuard = FrameReplayGuard()
        f.server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(socket: WebSocket, code: Int, reason: String) { socket.close(code, reason) }
            override fun onMessage(socket: WebSocket, text: String) {
                val frame = SignedTextFrame.parse(text)!!
                val channel = frame.channel.removeSuffix("/request")
                val principal = "alice" // сервер считает нас alice только если билет ниже пройдёт проверку
                val signer = keys.frameSigner(principal)
                if (signer.verify(frame, "$channel/request", FrameReplayGuard()) != FrameVerificationResult.ACCEPTED) { socket.cancel(); return }
                socket.send(signer.sign("$channel/response", "metric for $principal").toWire())
            }
        }))
        f.server.start()
        val binding = SocketUserBinding(keys, { alice })
        val live = LocalLiveSocket(f.client, keys.frameSigner("unused"), backgroundScope, backoffMillis = 1, maxReconnects = 0, userBinding = binding)
        // Серверная проверка билета выполняется по записанному запросу (MockWebServer не даёт хук до upgrade).
        try {
            live.connect(f.url())
            runCurrent() // запустить корутину подключения до блокирующего takeRequest
            val request = f.server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
                ?: error("WebSocket upgrade request was not sent")
            val channel = request.getHeader("X-Lab-Channel")!!
            val check = realCodec().verify(request.getHeader(SocketTicketCodec.HEADER)!!, channel, serverGuard)
            assertEquals(TicketVerification.ACCEPTED, check.result)
            assertEquals(alice, check.principal)
            assertNull(request.requestUrl!!.query) // билет не в URL
            val metrics = live.metrics.first { it.received == 1 }
            assertEquals(UserBoundSocketKeys.userTag("alice"), metrics.boundUserTag)
        } finally { live.close(); runCurrent(); f.shutdown() }
    }

    @Test fun noActiveUserMeansNoConnectionAttempt() = runTest {
        val f = fixture(); f.server.start()
        val live = LocalLiveSocket(f.client, keys.frameSigner("unused"), backgroundScope, userBinding = SocketUserBinding(keys, { null }))
        try {
            live.connect(f.url())
            val metrics = live.metrics.first { it.terminalError != null }
            assertEquals(LocalLiveSocket.USER_REQUIRED, metrics.terminalError)
            assertEquals(0, metrics.attempts)
            assertEquals(0, f.server.requestCount)
        } finally { live.close(); runCurrent(); f.shutdown() }
    }

    @Test fun userChangeClosesSocketAndDoesNotReconnectAsNewUser() = runTest {
        val f = fixture()
        val current = AtomicReference<SocketPrincipal?>(alice)
        repeat(2) {
            f.server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onClosing(socket: WebSocket, code: Int, reason: String) { socket.close(code, reason) }
                override fun onMessage(socket: WebSocket, text: String) {
                    val channel = SignedTextFrame.parse(text)!!.channel.removeSuffix("/request")
                    // Пока ответ летит, пользователь сменился на bob.
                    current.set(bob)
                    socket.send(keys.frameSigner("alice").sign("$channel/response", "alice data").toWire())
                }
            }))
        }
        f.server.start()
        val live = LocalLiveSocket(f.client, keys.frameSigner("unused"), backgroundScope, backoffMillis = 1, maxReconnects = 3,
            userBinding = SocketUserBinding(keys, { current.get() }))
        try {
            live.connect(f.url())
            val metrics = live.metrics.first { it.terminalError == LocalLiveSocket.USER_CHANGED }
            assertEquals(0, metrics.received) // данные alice не показаны после смены пользователя
            assertEquals(1, metrics.attempts)
            assertFalse(metrics.connected)
            assertEquals(1, f.server.requestCount)
        } finally { live.close(); runCurrent(); f.shutdown() }
    }

    @Test fun explicitPrincipalChangeResetsBoundUser() = runTest {
        val f = fixture(); f.server.start()
        val live = LocalLiveSocket(f.client, keys.frameSigner("unused"), backgroundScope, userBinding = SocketUserBinding(keys, { alice }))
        try {
            live.onPrincipalChanged()
            val m = live.metrics.value
            assertEquals(LocalLiveSocket.USER_CHANGED, m.terminalError)
            assertNull(m.boundUserTag); assertFalse(m.connected)
        } finally { live.close(); runCurrent(); f.shutdown() }
    }
}
