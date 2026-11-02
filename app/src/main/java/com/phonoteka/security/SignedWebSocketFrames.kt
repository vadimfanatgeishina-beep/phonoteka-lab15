package com.phonoteka.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.LinkedHashMap
import javax.crypto.Mac
import javax.crypto.SecretKey
import okio.ByteString
import okio.ByteString.Companion.decodeBase64

data class SignedTextFrame(
    val channel: String,
    val timestampSeconds: Long,
    val nonce: String,
    val signatureHex: String,
    val payloadBase64: String
) {
    fun toWire(): String = "$channel\n$timestampSeconds\n$nonce\n$signatureHex\n$payloadBase64"

    companion object {
        fun parse(wire: String): SignedTextFrame? {
            if (wire.length > MAX_WIRE_CHARS) return null
            val fields = wire.split('\n', limit = 5)
            if (fields.size != 5) return null
            val timestamp = fields[1].toLongOrNull() ?: return null
            if (decodePayload(fields[4]) == null) return null
            return SignedTextFrame(fields[0], timestamp, fields[2], fields[3], fields[4])
        }

        private fun decodePayload(value: String): ByteArray? = value.decodeBase64()?.toByteArray()

        private const val MAX_WIRE_CHARS = 90_000
    }
}

enum class FrameVerificationResult {
    ACCEPTED, EXPIRED, REPLAY, INVALID_SIGNATURE, INVALID_NONCE, INVALID_CHANNEL, INVALID_FRAME
}

/** HMAC protocol for text frames in the local WebSocket lab. This is not a production key scheme. */
class HmacTextFrameSigner(
    private val key: SecretKey,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    fun sign(channel: String, payload: String, nonce: String = newNonce()): SignedTextFrame {
        require(validChannel(channel))
        require(nonce.matches(NONCE_PATTERN))
        val bytes = payload.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_PAYLOAD_BYTES)
        val encoded = ByteString.of(*bytes).base64()
        val timestamp = clockSeconds()
        return SignedTextFrame(channel, timestamp, nonce, hmac(canonical(channel, timestamp, nonce, bytes)), encoded)
    }

    fun verify(
        frame: SignedTextFrame,
        expectedChannel: String,
        replayGuard: FrameReplayGuard,
        nowSeconds: Long = clockSeconds(),
        windowSeconds: Long = 30
    ): FrameVerificationResult {
        if (!validChannel(frame.channel) || frame.channel != expectedChannel) return FrameVerificationResult.INVALID_CHANNEL
        if (!frame.nonce.matches(NONCE_PATTERN)) return FrameVerificationResult.INVALID_NONCE
        val payload = frame.payloadBase64.decodeBase64()?.toByteArray()
            ?: return FrameVerificationResult.INVALID_FRAME
        if (payload.size > MAX_PAYLOAD_BYTES || !HEX_SIGNATURE.matches(frame.signatureHex)) return FrameVerificationResult.INVALID_FRAME
        require(windowSeconds >= 0)
        val offset = runCatching { Math.subtractExact(nowSeconds, frame.timestampSeconds) }.getOrNull()
            ?: return FrameVerificationResult.EXPIRED
        if (offset < -windowSeconds || offset > windowSeconds) return FrameVerificationResult.EXPIRED
        val expected = hmac(canonical(frame.channel, frame.timestampSeconds, frame.nonce, payload))
        if (!MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), frame.signatureHex.lowercase(java.util.Locale.ROOT).toByteArray(Charsets.US_ASCII))) {
            return FrameVerificationResult.INVALID_SIGNATURE
        }
        if (!replayGuard.consume(frame.nonce, nowSeconds,
                runCatching { Math.addExact(frame.timestampSeconds, windowSeconds) }.getOrDefault(Long.MAX_VALUE))) return FrameVerificationResult.REPLAY
        return FrameVerificationResult.ACCEPTED
    }

    private fun canonical(channel: String, timestamp: Long, nonce: String, payload: ByteArray): String =
        listOf("WS", channel, timestamp.toString(), nonce, sha256(payload)).joinToString("\n")

    private fun hmac(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return mac.doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun newNonce(): String = ByteArray(18).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    private fun validChannel(value: String): Boolean = value.matches(Regex("/[A-Za-z0-9/_-]{1,127}"))

    companion object {
        private val NONCE_PATTERN = Regex("[A-Za-z0-9_-]{16,64}")
        private val HEX_SIGNATURE = Regex("[0-9a-fA-F]{64}")
        private const val MAX_PAYLOAD_BYTES = 64 * 1024
    }
}

/** Retains a nonce through the inclusive signature expiry, not a TTL from first receipt. */
class FrameReplayGuard(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {
    private val consumed = LinkedHashMap<String, Long>()

    @Synchronized fun consume(nonce: String, nowSeconds: Long, validUntilSeconds: Long): Boolean {
        consumed.entries.removeIf { nowSeconds > it.value }
        if (validUntilSeconds < nowSeconds) return false
        if (consumed.containsKey(nonce) || consumed.size >= maxEntries) return false
        consumed[nonce] = validUntilSeconds
        return true
    }

    companion object { private const val DEFAULT_MAX_ENTRIES = 50_000 }
}
