package com.phonoteka.security

import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.tls.*
import org.junit.Assert.*
import org.junit.Test
import javax.net.ssl.SSLPeerUnverifiedException

/** MITM-equivalent certificate substitution only against an owned localhost endpoint.
 * No global proxy, traffic interception, user CA installation or real account is used.
 */
class OwnedCertificateSubstitutionTest {
    @Test fun trustedSubstituteCertificateCannotBypassOriginalSpkiPin() {
        val original = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val substitute = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val substitutionServer = MockWebServer()
        substitutionServer.useHttps(HandshakeCertificates.Builder().heldCertificate(substitute).build().sslSocketFactory(), false)
        substitutionServer.enqueue(MockResponse().setBody("must not be delivered")); substitutionServer.start()
        // Both certificates intentionally trusted by this ONE fixture client; hostname validation stays on.
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(original.certificate).addTrustedCertificate(substitute.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .certificatePinner(CertificatePinner.Builder().add("localhost", CertificatePinner.pin(original.certificate)).build())
            .callTimeout(3, java.util.concurrent.TimeUnit.SECONDS).build()
        try {
            var rejected = false
            try { client.newCall(Request.Builder().url(substitutionServer.url("/api/test").newBuilder().host("localhost").build()).build()).execute().use { } }
            catch (e: SSLPeerUnverifiedException) {
                assertTrue(e.message.orEmpty().startsWith("Certificate pinning failure!")); rejected = true
            }
            assertTrue(rejected); assertEquals(0, substitutionServer.requestCount)
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll(); substitutionServer.shutdown() }
    }
}
