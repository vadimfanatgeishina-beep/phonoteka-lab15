package com.phonoteka

/**
 * Описание текущей версии приложения. Показывается в меню ⋮ → «О версии».
 */
object VersionInfo {
    data class Item(val requirement: String, val whatDone: String, val where: String)

    const val TITLE = "Версия 15 — ЛР15: безопасная передача"
    const val SUMMARY = "HTTPS к своему стенду с самоподписанным сертификатом, NSC (release без http), 2 SPKI-пина в NSC и OkHttp, HMAC-подпись запросов с защитой от повтора, wss:// с подписью кадров и переподключением. Усложнения: TrustKit-сравнение, mTLS, ротация пинов, E2EE истории, отказ от TLS 1.0."
    const val HAS_DEMO = true
    val items: List<Item> = listOf(
        Item("Основа: HTTPS мок-сервер с самоподписанным сертификатом", "свой стенд на localhost:8443, сертификат генерируется скриптом", "tools/LocalLab15MockServer.java, tools/run_owned_tls_contract.py"),
        Item("Основа: NSC — release без cleartext, отдельный debug-профиль", "release: только системные CA; debug: свой сертификат localhost", "res/xml/network_security_config.xml (main и debug), AndroidManifest.xml"),
        Item("Основа: SPKI-пиннинг на 2 пина с expiration, fail closed", "основной + запасной пин в NSC (срок 2036-01-01) и в OkHttp CertificatePinner", "debug NSC, debug security/LocalMockTlsClient.kt"),
        Item("Основа: HMAC-подпись запроса", "method + path + timestamp + nonce + sha256(body), окно ±30 с, nonce-кэш; повтор = 409, подмена/время = 401", "security/RequestSigning.kt, Lab15Screen (кнопка «4 случая»)"),
        Item("Основа: WebSocket wss:// с reconnect/backoff и подписью кадров", "backoff 1/2/4/8/16/30 с, каждый кадр подписан, повтор/отражение отклоняются; сокет привязан к пользователю", "LocalLiveSocket.kt, SignedWebSocketFrames.kt, LocalReconnectPolicy.kt, SocketUserBinding.kt, debug Lab15LiveScreen.kt"),
        Item("Основа: таблица 8 проверок", "8 строк: ожидание + какой тест проверяет", "LAB15_MITM.md"),
        Item("Ручное: mitmproxy — 3 доказательства", "без пина виден трафик, но подмена = 401; с пином обрыв; http:// запрещён NSC — инструкция по шагам", "LAB15_MITM.md"),
        Item("Документация: canonical-строка, секретов в репо нет", "формат 5 строк; ключ только из переменной PHONOTEKA_MOCK_HMAC_KEY, в release пусто", "LAB15_PROTOCOL.md, app/build.gradle.kts"),
        Item("Усложнение: сравнение с TrustKit", "таблица NSC / OkHttp / TrustKit и обоснование выбора", "LAB15_TRUSTKIT.md"),
        Item("Усложнение: mTLS", "сервер требует клиентский сертификат: с ним 200, без него отказ", "test/security/MtlsAndTlsVersionTest.kt"),
        Item("Усложнение: ротация pin-set", "переход на запасной сертификат без обновления приложения; порядок ротации описан", "OwnedBackupPinRotationTest.kt, LAB15_TRUSTKIT.md"),
        Item("Усложнение: E2EE истории", "ECDH P-256 + HKDF + AES-GCM: сервер видит только шифротекст, чужой ключ не расшифрует", "security/E2eeHistory.kt, E2eeHistoryTest.kt, Lab15Screen"),
        Item("Усложнение: отказ от TLS 1.0", "клиент предлагает только TLS 1.2/1.3; стенд не принимает TLS 1.0", "LocalMockTlsClient.kt, tools/LocalLab15TlsDowngradeTest.java, MtlsAndTlsVersionTest.kt")
    )
}
