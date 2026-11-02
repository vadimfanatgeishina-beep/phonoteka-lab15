package com.phonoteka.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.LinkedHashMap
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import okio.ForwardingSink
import okio.buffer
import okhttp3.RequestBody.Companion.toRequestBody

data class SignedRequest(val timestampSeconds: Long, val nonce: String, val signatureHex: String)
enum class VerificationResult { ACCEPTED, EXPIRED, REPLAY, INVALID_SIGNATURE, INVALID_NONCE }

class HmacRequestSigner(private val key: SecretKey, private val clock: () -> Long = { System.currentTimeMillis() / 1000 }) {
    fun sign(method: String, path: String, body: ByteArray = byteArrayOf(), nonce: String = newNonce()): SignedRequest {
        val timestamp = clock()
        return SignedRequest(timestamp, nonce, hmac(canonical(method, path, timestamp, nonce, body)))
    }

    fun verify(
        method: String,
        path: String,
        body: ByteArray,
        signed: SignedRequest,
        replayGuard: ReplayGuard,
        nowSeconds: Long = clock(),
        windowSeconds: Long = 30
    ): VerificationResult {
        if (!signed.nonce.matches(Regex("[A-Za-z0-9_-]{16,64}"))) return VerificationResult.INVALID_NONCE
        require(windowSeconds >= 0)
        val offset = runCatching { Math.subtractExact(nowSeconds, signed.timestampSeconds) }.getOrNull()
            ?: return VerificationResult.EXPIRED
        if (offset < -windowSeconds || offset > windowSeconds) return VerificationResult.EXPIRED
        if (!signed.signatureHex.matches(Regex("[0-9a-fA-F]{64}"))) return VerificationResult.INVALID_SIGNATURE
        val expected = hmac(canonical(method, path, signed.timestampSeconds, signed.nonce, body))
        if (!MessageDigest.isEqual(expected.toByteArray(), signed.signatureHex.lowercase().toByteArray())) {
            return VerificationResult.INVALID_SIGNATURE
        }
        if (!replayGuard.consume(signed.nonce, nowSeconds,
                runCatching { Math.addExact(signed.timestampSeconds, windowSeconds) }.getOrDefault(Long.MAX_VALUE))) return VerificationResult.REPLAY
        return VerificationResult.ACCEPTED
    }

    private fun canonical(method: String, path: String, timestamp: Long, nonce: String, body: ByteArray): String =
        listOf(method.uppercase(java.util.Locale.ROOT), path, timestamp.toString(), nonce, sha256(body)).joinToString("\n")

    private fun hmac(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return mac.doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun newNonce(): String = ByteArray(18).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    companion object {
        /** Test/mock-server key only. Production callers must inject a provisioned Keystore-backed key. */
        fun forMockServer(keyBytes: ByteArray, clock: () -> Long = { System.currentTimeMillis() / 1000 }) =
            HmacRequestSigner(SecretKeySpec(keyBytes.copyOf(), "HmacSHA256"), clock)
    }
}

/** Retains a nonce through the inclusive signature expiry, not a TTL from first receipt. */
class ReplayGuard(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {
    private val used = LinkedHashMap<String, Long>()
    @Synchronized fun consume(nonce: String, nowSeconds: Long, validUntilSeconds: Long): Boolean {
        used.entries.removeIf { nowSeconds > it.value }
        if (validUntilSeconds < nowSeconds) return false
        if (used.containsKey(nonce) || used.size >= maxEntries) return false
        used[nonce] = validUntilSeconds
        return true
    }

    companion object { private const val DEFAULT_MAX_ENTRIES = 50_000 }
}

class HmacSigningInterceptor(private val signer: HmacRequestSigner) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        require(request.url.host == "localhost" && request.url.scheme == "https") { "Signing is restricted to the owned local TLS fixture" }
        require(request.body?.isDuplex() != true) { "Duplex signing is not supported by the local fixture" }
        val buffer = Buffer()
        var written = 0L
        val limited = object : ForwardingSink(buffer) {
            override fun write(source: Buffer, byteCount: Long) {
                if (byteCount > 65536L - written) throw java.io.IOException("Local signed body exceeds 64 KiB")
                written += byteCount
                super.write(source, byteCount)
            }
        }.buffer()
        limited.use { request.body?.writeTo(it) }
        val body = buffer.readByteArray()
        val target = request.url.encodedPath + request.url.encodedQuery?.let { "?$it" }.orEmpty()
        val signed = signer.sign(request.method, target, body)
        val signedRequest = request.newBuilder()
            .method(request.method, request.body?.let { body.toRequestBody(it.contentType()) })
            .header("X-Timestamp", signed.timestampSeconds.toString())
            .header("X-Nonce", signed.nonce)
            .header("X-Signature", signed.signatureHex)
            .build()
        return chain.proceed(signedRequest)
    }
}
