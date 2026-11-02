package com.phonoteka.security

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import javax.net.ssl.SSLPeerUnverifiedException

/** Real JVM TLS policy experiment with ephemeral OWNED certificates, not Android NSC evidence. */
class OwnedBackupPinRotationTest {
    private fun certificate() = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    @Test fun sameClientAcceptsPrimaryAndBackupCertificatesAfterRotation() {
        val primary = certificate(); val backup = certificate()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(primary.certificate).addTrustedCertificate(backup.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .certificatePinner(CertificatePinner.Builder().add("localhost", CertificatePinner.pin(primary.certificate), CertificatePinner.pin(backup.certificate)).build())
            .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build()
        try {
            for (held in listOf(primary, backup)) {
                val server = MockWebServer(); server.useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory(), false)
                server.enqueue(MockResponse().setBody("owned")); server.start()
                try {
                    client.newCall(Request.Builder().url(server.url("/api/test").newBuilder().host("localhost").build()).build()).execute().use { assertEquals(200, it.code); assertEquals("owned", it.body!!.string()) }
                } finally { server.shutdown() }
            }
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
    @Test fun trustedButUnpinnedThirdCertificateStillFailsClosed() {
        val primary = certificate(); val backup = certificate(); val third = certificate()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(primary.certificate).addTrustedCertificate(backup.certificate).addTrustedCertificate(third.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .certificatePinner(CertificatePinner.Builder().add("localhost", CertificatePinner.pin(primary.certificate), CertificatePinner.pin(backup.certificate)).build())
            .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build()
        val server = MockWebServer(); server.useHttps(HandshakeCertificates.Builder().heldCertificate(third).build().sslSocketFactory(), false)
        server.enqueue(MockResponse().setBody("must not reach app")); server.start()
        try {
            try { client.newCall(Request.Builder().url(server.url("/api/test").newBuilder().host("localhost").build()).build()).execute().use { }; fail("Third pin accepted") }
            catch (failure: SSLPeerUnverifiedException) { assertTrue(failure.message.orEmpty().startsWith("Certificate pinning failure!")) }
        } finally { server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
}
