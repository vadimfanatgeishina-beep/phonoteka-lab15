package com.phonoteka.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * ЛР15: привязка WSS-соединения к пользователю и локальной сессии.
 * userId — идентификатор учебной фикстуры; sessionVersion — версия локального входа (LocalAuthManager).
 */
data class SocketPrincipal(val userId: String, val sessionVersion: Long) {
    init {
        require(userId.matches(USER_ID)) { "Invalid fixture user id" }
        require(sessionVersion >= 0)
    }
    companion object { val USER_ID = Regex("[A-Za-z0-9_.-]{1,64}") }
}

enum class TicketVerification { ACCEPTED, MALFORMED, BAD_SIGNATURE, EXPIRED, CHANNEL_MISMATCH, REPLAY }
data class TicketCheck(val result: TicketVerification, val principal: SocketPrincipal? = null)

/**
 * Из одного учебного мастер-ключа выводятся раздельные ключи (HMAC как KDF):
 * - ключ билетов рукопожатия;
 * - отдельный ключ подписи кадров для КАЖДОГО пользователя: кадр пользователя A не пройдёт проверку у B.
 * Формат совпадает с tools/LocalLab15WssServer.java.
 */
class UserBoundSocketKeys(private val master: ByteArray) {
    init { require(master.size >= 16) { "Fixture master key too short" } }
    fun frameKey(userId: String): SecretKey = SecretKeySpec(derive("lab15/frame-key/v1\n$userId"), "HmacSHA256")
    fun ticketKey(): SecretKey = SecretKeySpec(derive("lab15/ticket-key/v1"), "HmacSHA256")
    fun frameSigner(userId: String, clockSeconds: () -> Long = { System.currentTimeMillis() / 1000 }) =
        HmacTextFrameSigner(frameKey(userId), clockSeconds)
    private fun derive(label: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(master, "HmacSHA256")); doFinal(label.toByteArray(Charsets.UTF_8))
    }
    companion object {
        /** Канал содержит не userId, а его короткий хеш: идентификатор не светится в логах каналов. */
        fun userTag(userId: String): String = MessageDigest.getInstance("SHA-256")
            .digest(userId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(16)
        fun channelFor(principal: SocketPrincipal, connectionId: UUID = UUID.randomUUID()): String =
            "/lab15/u/${userTag(principal.userId)}/$connectionId"
    }
}

/** Короткоживущий одноразовый билет рукопожатия: пользователь + версия сессии + конкретный канал. */
class SocketTicketCodec(
    private val keys: UserBoundSocketKeys,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val ttlSeconds: Long = 30
) {
    fun issue(principal: SocketPrincipal, channel: String, nonce: String = newNonce()): String {
        require(nonce.matches(NONCE))
        val expires = Math.addExact(clockSeconds(), ttlSeconds)
        val user = B64.encodeToString(principal.userId.toByteArray(Charsets.UTF_8))
        val sig = mac(canonical(principal, channel, expires, nonce))
        return "v1.$user.${principal.sessionVersion}.$expires.$nonce.$sig"
    }

    fun verify(ticket: String, expectedChannel: String, replay: FrameReplayGuard, now: Long = clockSeconds()): TicketCheck {
        if (ticket.length > 512) return TicketCheck(TicketVerification.MALFORMED)
        val parts = ticket.split('.')
        if (parts.size != 6 || parts[0] != "v1") return TicketCheck(TicketVerification.MALFORMED)
        val userId = runCatching { String(B64D.decode(parts[1]), Charsets.UTF_8) }.getOrNull()
        val version = parts[2].toLongOrNull(); val expires = parts[3].toLongOrNull()
        val nonce = parts[4]; val sig = parts[5]
        if (userId == null || !userId.matches(SocketPrincipal.USER_ID) || version == null || version < 0 || expires == null ||
            !nonce.matches(NONCE) || !sig.matches(HEX64)) return TicketCheck(TicketVerification.MALFORMED)
        val principal = SocketPrincipal(userId, version)
        // Подпись проверяется относительно ОЖИДАЕМОГО канала: билет чужого канала не подойдёт.
        val expected = mac(canonical(principal, expectedChannel, expires, nonce))
        if (!MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), sig.lowercase().toByteArray(Charsets.US_ASCII)))
            return TicketCheck(TicketVerification.BAD_SIGNATURE)
        if (!expectedChannel.startsWith("/lab15/u/${UserBoundSocketKeys.userTag(userId)}/"))
            return TicketCheck(TicketVerification.CHANNEL_MISMATCH)
        if (now > expires || expires - now > ttlSeconds) return TicketCheck(TicketVerification.EXPIRED)
        if (!replay.consume("ticket:$nonce", now, expires)) return TicketCheck(TicketVerification.REPLAY)
        return TicketCheck(TicketVerification.ACCEPTED, principal)
    }

    private fun canonical(p: SocketPrincipal, channel: String, expires: Long, nonce: String) =
        listOf("LAB15-TICKET", "v1", p.userId, p.sessionVersion.toString(), channel, expires.toString(), nonce).joinToString("\n")
    private fun mac(value: String) = Mac.getInstance("HmacSHA256").run {
        init(keys.ticketKey()); doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    private fun newNonce(): String = ByteArray(18).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    companion object {
        const val HEADER = "X-Lab-Ticket"
        private val NONCE = Regex("[A-Za-z0-9_-]{16,64}")
        private val HEX64 = Regex("[0-9a-fA-F]{64}")
        private val B64 = Base64.getUrlEncoder().withoutPadding()
        private val B64D = Base64.getUrlDecoder()
    }
}

/** Что нужно клиенту: ключи + источник текущего пользователя (null = нет активной сессии). */
class SocketUserBinding(
    val keys: UserBoundSocketKeys,
    val currentPrincipal: () -> SocketPrincipal?,
    val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    val tickets = SocketTicketCodec(keys, clockSeconds)
}
