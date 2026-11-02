package com.phonoteka.security

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Debug-only client for the owned localhost TLS lab fixture; never used by production sources. */
object LocalMockTlsClient {
    const val PRIMARY_PIN = "sha256/1fqCAYJX7VPXlLJ1ATLQRlxexbVwFpvLtZadUQxYwwU="
    const val BACKUP_PIN = "sha256/E8OZRSghu3t1p/Z5p2yP7Oa6gMgV7U8EVBGIyiXVWQw="

    fun create(hmacKey: ByteArray): OkHttpClient = OkHttpClient.Builder()
        .certificatePinner(CertificatePinner.Builder().add("localhost", PRIMARY_PIN, BACKUP_PIN).build())
        .addInterceptor(HmacSigningInterceptor(HmacRequestSigner.forMockServer(hmacKey)))
        // ЛР15 (усложнение): TLS 1.0/1.1 не предлагаются вообще
        .connectionSpecs(listOf(okhttp3.ConnectionSpec.Builder(okhttp3.ConnectionSpec.MODERN_TLS)
            .tlsVersions(okhttp3.TlsVersion.TLS_1_3, okhttp3.TlsVersion.TLS_1_2).build()))
        .build()

    /** The URL must be a wss:// endpoint of the locally owned lab fixture. */
    fun openWebSocket(client: OkHttpClient, url: String, listener: WebSocketListener): WebSocket {
        require(url.startsWith("wss://localhost:")) {
            "Lab WebSocket is restricted to a local TLS fixture"
        }
        return client.newWebSocket(Request.Builder().url(url).build(), listener)
    }
}
