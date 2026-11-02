package com.phonoteka.security

import javax.net.ssl.SSLPeerUnverifiedException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.spec.SecretKeySpec
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercises an owned local TLS fixture, request signing, query binding, and pin rejection. */
class LocalHttpsMockServerTest {
    @Test fun pinnedHttpsAcceptsSignedQueryAndRejectsWrongPin() {
        val now = AtomicLong(1_800_000_000L)
        val key = SecretKeySpec(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }, "HmacSHA256")
        val verifier = HmacRequestSigner(key) { now.get() }
        val clientSigner = HmacRequestSigner(key) { now.get() }
        val replayGuard = ReplayGuard()
        val result = AtomicReference<VerificationResult>()
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readByteArray()
                val signed = SignedRequest(
                    request.getHeader("X-Timestamp")?.toLongOrNull() ?: -1L,
                    request.getHeader("X-Nonce").orEmpty(),
                    request.getHeader("X-Signature").orEmpty()
                )
                val target = request.requestUrl!!.encodedPath + request.requestUrl!!.encodedQuery?.let { "?$it" }.orEmpty()
                val verified = verifier.verify(request.method!!, target, body, signed, replayGuard, now.get())
                result.set(verified)
                return MockResponse().setResponseCode(if (verified == VerificationResult.ACCEPTED) 200 else 401)
                    .setBody(if (verified == VerificationResult.ACCEPTED) "accepted" else "rejected")
            }
        }
        server.start()
        val pinnedClient = OkHttpClient.Builder()
            .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .certificatePinner(CertificatePinner.Builder().add("localhost", CertificatePinner.pin(certificate.certificate)).build())
            .addInterceptor(HmacSigningInterceptor(clientSigner))
            .build()
        val wrongPinClient = OkHttpClient.Builder()
            .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .certificatePinner(CertificatePinner.Builder().add("localhost", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=").build())
            .build()
        try {
            val url = server.url("/api/test?item=one").newBuilder().scheme("https").host("localhost").build()
            pinnedClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("accepted", response.body!!.string())
            }
            assertEquals(VerificationResult.ACCEPTED, result.get())
            val rawClient = pinnedClient.newBuilder().apply { interceptors().clear() }.build()
            val future = HmacRequestSigner(key) { now.get() + 30 }.sign("GET", "/api/test?item=future")
            fun send(target: String, signed: SignedRequest, expected: Int) {
                val request = Request.Builder().url(server.url(target).newBuilder().host("localhost").build())
                    .header("X-Timestamp", signed.timestampSeconds.toString())
                    .header("X-Nonce", signed.nonce).header("X-Signature", signed.signatureHex).build()
                rawClient.newCall(request).execute().use { assertEquals(expected, it.code) }
            }
            val issuedAt = now.get()
            send("/api/test?item=future", future, 200)
            now.set(issuedAt + 31)
            send("/api/test?item=future", future, 401)
            assertEquals(VerificationResult.REPLAY, result.get())
            now.set(issuedAt + 60)
            send("/api/test?item=future", future, 401)
            assertEquals(VerificationResult.REPLAY, result.get())
            now.set(issuedAt + 61)
            send("/api/test?item=future", future, 401)
            assertEquals(VerificationResult.EXPIRED, result.get())
            val queryBound = clientSigner.sign("GET", "/api/test?item=original")
            send("/api/test?item=changed", queryBound, 401)
            assertEquals(VerificationResult.INVALID_SIGNATURE, result.get())
            val invalid = clientSigner.sign("GET", "/api/test?item=invalid").copy(signatureHex = "0".repeat(64))
            send("/api/test?item=invalid", invalid, 401)
            assertEquals(VerificationResult.INVALID_SIGNATURE, result.get())
            val countBeforeWrongPin = server.requestCount
            try {
                wrongPinClient.newCall(Request.Builder().url(url).build()).execute().use { error("wrong certificate pin unexpectedly succeeded") }
            } catch (failure: SSLPeerUnverifiedException) {
                assertTrue("Must fail specifically at SPKI pinning", failure.message.orEmpty().startsWith("Certificate pinning failure!"))
            }
            assertEquals(countBeforeWrongPin, server.requestCount)
        } finally {
            pinnedClient.dispatcher.executorService.shutdown()
            wrongPinClient.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }
}
