package com.phonoteka.security

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ЛР15 (усложнения): mTLS — сервер требует клиентский сертификат; отказ от TLS 1.0/1.1. Всё на своих временных сертификатах. */
class MtlsAndTlsVersionTest {
    private val ca = HeldCertificate.Builder().certificateAuthority(0).commonName("lab15-ca").build()
    private val serverCert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").signedBy(ca).build()
    private val clientCert = HeldCertificate.Builder().commonName("phonoteka-device").signedBy(ca).build()

    private fun server(): MockWebServer {
        val certs = HandshakeCertificates.Builder().heldCertificate(serverCert).addTrustedCertificate(ca.certificate).build()
        return MockWebServer().apply { useHttps(certs.sslSocketFactory(), false); requireClientAuth(); enqueue(MockResponse().setBody("ok")) }
    }

    private fun client(withClientCert: Boolean): OkHttpClient {
        val b = HandshakeCertificates.Builder().addTrustedCertificate(ca.certificate)
        if (withClientCert) b.heldCertificate(clientCert)
        val certs = b.build()
        return OkHttpClient.Builder()
            .sslSocketFactory(certs.sslSocketFactory(), certs.trustManager)
            .connectionSpecs(listOf(STRICT_TLS))
            .build()
    }

    @Test fun mtlsWithClientCertificateSucceeds() {
        server().use { s ->
            client(true).newCall(Request.Builder().url(s.url("/").newBuilder().host("localhost").build()).build()).execute().use { assertEquals(200, it.code) }
            assertEquals("phonoteka-device", (s.takeRequest().handshake!!.peerPrincipal!!.name).substringAfter("CN=").substringBefore(","))
        }
    }

    @Test fun mtlsWithoutClientCertificateIsRejected() {
        server().use { s ->
            val failed = runCatching { client(false).newCall(Request.Builder().url(s.url("/").newBuilder().host("localhost").build()).build()).execute().close() }.isFailure
            assertTrue(failed)
        }
    }

    @Test fun strictSpecHasNoLegacyTls() {
        val versions = STRICT_TLS.tlsVersions!!
        assertTrue(TlsVersion.TLS_1_0 !in versions && TlsVersion.TLS_1_1 !in versions)
        assertTrue(TlsVersion.TLS_1_2 in versions)
    }

    companion object {
        val STRICT_TLS: ConnectionSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2).build()
    }
}
