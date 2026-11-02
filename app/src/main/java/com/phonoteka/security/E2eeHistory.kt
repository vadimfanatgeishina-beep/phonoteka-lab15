package com.phonoteka.security

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/**
 * ЛР15 (усложнение): E2EE истории прослушиваний между устройствами одного пользователя.
 * ECDH P-256 → HKDF-SHA256 → AES-256-GCM. Сервер (и mitmproxy) видит только шифротекст.
 */
class E2eeParty(private val keyPair: KeyPair = generate()) {
    /** Публичный ключ X.509 — его можно отдать серверу. Закрытый никогда не покидает устройство. */
    val publicKey: ByteArray get() = keyPair.public.encoded

    fun sessionKey(peerPublicKey: ByteArray): SecretKey {
        val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPublicKey))
        val shared = KeyAgreement.getInstance("ECDH").run { init(keyPair.private); doPhase(peer, true); generateSecret() }
        return SecretKeySpec(hkdf(shared, "phonoteka/lab15/history/v1".toByteArray()), "AES")
    }

    companion object {
        fun generate(): KeyPair = KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }

        /** HKDF (RFC 5869) с нулевой солью, длина 32 байта (один блок). */
        fun hkdf(ikm: ByteArray, info: ByteArray): ByteArray {
            val prk = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(ByteArray(32), "HmacSHA256")); doFinal(ikm) }
            return Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(prk, "HmacSHA256")); update(info); update(1.toByte()); doFinal() }
        }
    }
}

object E2eeHistory {
    private const val IV_BYTES = 12
    private val random = SecureRandom()

    /** Результат: Base64(iv || ciphertext+tag). aad привязывает запись к типу данных/версии. */
    fun seal(key: SecretKey, plaintext: String, aad: String): String {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad.toByteArray())
        return (iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))).toByteString().base64()
    }

    /** null = чужой ключ, испорченные данные или другой aad (fail closed). */
    fun open(key: SecretKey, sealed: String, aad: String): String? = runCatching {
        val bytes = sealed.decodeBase64()!!.toByteArray()
        require(bytes.size > IV_BYTES + 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES)))
        cipher.updateAAD(aad.toByteArray())
        String(cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)), Charsets.UTF_8)
    }.getOrNull()
}
