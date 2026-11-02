package com.phonoteka.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import javax.net.ssl.SSLPeerUnverifiedException

/** Requires the owned localhost fixture and `adb reverse tcp:8443 tcp:8443`. */
@RunWith(AndroidJUnit4::class)
class LocalLab15TlsClientTest {
    @Test fun localPinnedClientAcceptsSignedRequestAndRejectsWrongPin() {
        val client = LocalMockTlsClient.create(TEST_HMAC_KEY.toByteArray()).newBuilder()
            .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build()
        val url = "https://localhost:8443/api/test?item=instrumented"
        try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("accepted", response.body!!.string())
            }

            val replayPath = "/api/test?item=replay"
            val signer = HmacRequestSigner.forMockServer(TEST_HMAC_KEY.toByteArray())
            val replaySignature = signer.sign("GET", replayPath) // fresh nonce for every test run
            val replayClient = OkHttpClient.Builder()
            .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS).certificatePinner(
                CertificatePinner.Builder().add("localhost", LocalMockTlsClient.PRIMARY_PIN, LocalMockTlsClient.BACKUP_PIN).build()
            ).build()
            try {
                val replayUrl = "https://localhost:8443$replayPath"
                repeat(2) { attempt ->
                    val replayRequest = Request.Builder().url(replayUrl)
                        .header("X-Timestamp", replaySignature.timestampSeconds.toString())
                        .header("X-Nonce", replaySignature.nonce)
                        .header("X-Signature", replaySignature.signatureHex)
                        .get().build()
                    replayClient.newCall(replayRequest).execute().use { response ->
                        assertEquals(if (attempt == 0) 200 else 409, response.code)
                        assertEquals(if (attempt == 0) "accepted" else "Replay", response.body!!.string())
                    }
                }
                val valid = signer.sign("GET", replayPath)
                fun expectUnauthorized(signed: SignedRequest, target: String = replayPath, reason: String = "Signature") {
                    val request = Request.Builder().url("https://localhost:8443$target")
                        .header("X-Timestamp", signed.timestampSeconds.toString())
                        .header("X-Nonce", signed.nonce).header("X-Signature", signed.signatureHex).get().build()
                    replayClient.newCall(request).execute().use {
                        assertEquals(401, it.code); assertEquals(reason, it.body!!.string())
                    }
                }
                expectUnauthorized(valid.copy(signatureHex = "0".repeat(64)))
                expectUnauthorized(valid, "/api/test?item=changed")
                val expired = HmacRequestSigner.forMockServer(TEST_HMAC_KEY.toByteArray()) {
                    System.currentTimeMillis() / 1000 - 60
                }.sign("GET", replayPath)
                expectUnauthorized(expired, reason = "Clock")
            } finally {
                replayClient.dispatcher.executorService.shutdown()
            }

            val wrongPinClient = OkHttpClient.Builder()
            .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS).certificatePinner(
                CertificatePinner.Builder()
                    .add("localhost", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
                    .build()
            ).build()
            try {
                var rejected = false
                try {
                    wrongPinClient.newCall(Request.Builder().url(url).get().build()).execute().use { }
                } catch (failure: SSLPeerUnverifiedException) {
                    assertTrue("Must fail specifically at SPKI pinning",
                        failure.message.orEmpty().startsWith("Certificate pinning failure!"))
                    rejected = true
                }
                assertTrue("A wrong localhost certificate pin must fail the TLS request", rejected)
            } finally {
                wrongPinClient.dispatcher.executorService.shutdown()
            }
        } finally {
            client.dispatcher.executorService.shutdown()
        }
    }

    companion object {
        private const val TEST_HMAC_KEY = "local-fixture-only-change-me"
    }
}
