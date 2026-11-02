package com.phonoteka.security

import android.content.Context
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * ЛР15, debug-only: три сценария pinning на собственном loopback-стенде.
 *
 * Стенд поднимает HTTPS-сервер на 127.0.0.1 и предъявляет один из трёх сертификатов.
 * Клиент доверяет всем трём (как «система доверяет»), поэтому в третьем сценарии
 * отказ возникает именно из-за pin-set, а не из-за цепочки сертификатов.
 *
 * Сертификаты и пины создаются на устройстве для каждого запуска: закрытые ключи
 * не покидают процесс и не попадают в APK. Структура pin-set та же, что в
 * debug network_security_config.xml и LocalMockTlsClient: основной + запасной.
 */
enum class Lab15PinCase(val title: String) {
    PRIMARY("1. Основной сертификат"),
    BACKUP_AFTER_ROTATION("2. Запасной сертификат после ротации"),
    TRUSTED_BUT_UNPINNED("3. Доверенный системой, но не в pin-set")
}

data class Lab15PinResult(
    val case: Lab15PinCase,
    val pinningEnabled: Boolean,
    val expected: String,
    val accepted: Boolean,
    val actual: String,
    val reason: String,
    val httpCode: Int?
)

object Lab15PinScenarios {
    private const val HOST = "localhost"
    private const val REQUEST_PATH = "/api/pin-demo"

    fun expected(case: Lab15PinCase, pinningEnabled: Boolean): String = when {
        !pinningEnabled && case == Lab15PinCase.TRUSTED_BUT_UNPINNED ->
            "Соединение принято: pinning отключён в учебном режиме"
        case == Lab15PinCase.TRUSTED_BUT_UNPINNED ->
            "Отклонено pinning-проверкой (сертификат доверенный, но его пина нет в pin-set)"
        else -> "Соединение принято (HTTP 200)"
    }

    fun run(case: Lab15PinCase, pinningEnabled: Boolean = true): Lab15PinResult {
        val primary = certificate("phonoteka-lab15-primary")
        val backup = certificate("phonoteka-lab15-backup")
        val third = certificate("phonoteka-lab15-third")
        val served = when (case) {
            Lab15PinCase.PRIMARY -> primary
            Lab15PinCase.BACKUP_AFTER_ROTATION -> backup
            Lab15PinCase.TRUSTED_BUT_UNPINNED -> third
        }

        // Trust anchors: все три сертификата. Это моделирует «системе доверяют»,
        // чтобы третий сценарий падал именно на пине.
        val trust = HandshakeCertificates.Builder()
            .addTrustedCertificate(primary.certificate)
            .addTrustedCertificate(backup.certificate)
            .addTrustedCertificate(third.certificate)
            .build()

        val pinner = CertificatePinner.Builder()
            .add(HOST, CertificatePinner.pin(primary.certificate))
            .add(HOST, CertificatePinner.pin(backup.certificate))
            .build()

        val builder = OkHttpClient.Builder()
            .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
        if (pinningEnabled) builder.certificatePinner(pinner)
        val client = builder.build()

        val server = MockWebServer()
        server.useHttps(
            HandshakeCertificates.Builder().heldCertificate(served).build().sslSocketFactory(),
            false
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("lab15-pin-ok"))

        return try {
            server.start()
            val url = server.url(REQUEST_PATH).newBuilder().host(HOST).build()
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                Lab15PinResult(
                    case = case,
                    pinningEnabled = pinningEnabled,
                    expected = expected(case, pinningEnabled),
                    accepted = response.isSuccessful,
                    actual = "HTTP ${response.code}: $body",
                    reason = if (pinningEnabled) {
                        "TLS-рукопожатие прошло, предъявленный сертификат совпал с одним из пинов"
                    } else {
                        "Pinning отключён учебным режимом: проверялась только цепочка доверия"
                    },
                    httpCode = response.code
                )
            }
        } catch (failure: SSLPeerUnverifiedException) {
            Lab15PinResult(
                case = case,
                pinningEnabled = pinningEnabled,
                expected = expected(case, pinningEnabled),
                accepted = false,
                actual = "Соединение отклонено: Certificate pinning failure",
                reason = firstLine(failure.message).ifBlank { "Сертификат не совпал ни с одним пином" },
                httpCode = null
            )
        } catch (failure: IOException) {
            Lab15PinResult(
                case = case,
                pinningEnabled = pinningEnabled,
                expected = expected(case, pinningEnabled),
                accepted = false,
                actual = "Соединение не установлено: ${failure.javaClass.simpleName}",
                reason = firstLine(failure.message).ifBlank { "Локальный стенд недоступен" },
                httpCode = null
            )
        } finally {
            runCatching { server.shutdown() }
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun certificate(name: String): HeldCertificate =
        HeldCertificate.Builder()
            .commonName(name)
            .addSubjectAlternativeName(HOST)
            .addSubjectAlternativeName("127.0.0.1")
            .build()

    private fun firstLine(message: String?): String =
        message.orEmpty().lineSequence().firstOrNull().orEmpty()
}

/** Простой журнал лабораторной: файл в filesDir, только результаты сценариев. */
object Lab15PinJournal {
    private const val FILE_NAME = "lab15_pin_journal.log"
    private const val MAX_LINES = 200
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun append(context: Context, result: Lab15PinResult) {
        val line = buildString {
            append(stamp.format(Date()))
            append(" | ").append(result.case.name)
            append(" | pinning=").append(if (result.pinningEnabled) "on" else "off")
            append(" | ").append(if (result.accepted) "ACCEPTED" else "REJECTED")
            append(" | ").append(result.actual)
            append(" | ").append(result.reason)
        }
        val file = File(context.filesDir, FILE_NAME)
        runCatching {
            file.appendText(line + "\n")
            val lines = file.readLines()
            if (lines.size > MAX_LINES) file.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
        }
    }

    fun read(context: Context): List<String> =
        runCatching { File(context.filesDir, FILE_NAME).readLines().takeLast(60) }.getOrDefault(emptyList())

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}
