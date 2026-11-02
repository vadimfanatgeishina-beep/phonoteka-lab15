# ЛР15 — Безопасная передача

Версия v15 = чистая Фонотека (v0) + только требования ЛР15.
В приложении: меню **⋮ → «О версии»** (список требований простым языком) и **⋮ → «Экран ЛР15»**.

| Требование | Что сделано | Где в коде / на экране |
|---|---|---|
| Основа: HTTPS мок-сервер с самоподписанным сертификатом | свой стенд на localhost:8443, сертификат генерируется скриптом | tools/LocalLab15MockServer.java, tools/run_owned_tls_contract.py |
| Основа: NSC — release без cleartext, отдельный debug-профиль | release: только системные CA; debug: свой сертификат localhost | res/xml/network_security_config.xml (main и debug), AndroidManifest.xml |
| Основа: SPKI-пиннинг на 2 пина с expiration, fail closed | основной + запасной пин в NSC (срок 2036-01-01) и в OkHttp CertificatePinner | debug NSC, debug security/LocalMockTlsClient.kt |
| Основа: HMAC-подпись запроса | method + path + timestamp + nonce + sha256(body), окно ±30 с, nonce-кэш; повтор = 409, подмена/время = 401 | security/RequestSigning.kt, Lab15Screen (кнопка «4 случая») |
| Основа: WebSocket wss:// с reconnect/backoff и подписью кадров | backoff 1/2/4/8/16/30 с, каждый кадр подписан, повтор/отражение отклоняются; сокет привязан к пользователю | LocalLiveSocket.kt, SignedWebSocketFrames.kt, LocalReconnectPolicy.kt, SocketUserBinding.kt, debug Lab15LiveScreen.kt |
| Основа: таблица 8 проверок | 8 строк: ожидание + какой тест проверяет | LAB15_MITM.md |
| Ручное: mitmproxy — 3 доказательства | без пина виден трафик, но подмена = 401; с пином обрыв; http:// запрещён NSC — инструкция по шагам | LAB15_MITM.md |
| Документация: canonical-строка, секретов в репо нет | формат 5 строк; ключ только из переменной PHONOTEKA_MOCK_HMAC_KEY, в release пусто | LAB15_PROTOCOL.md, app/build.gradle.kts |
| Усложнение: сравнение с TrustKit | таблица NSC / OkHttp / TrustKit и обоснование выбора | LAB15_TRUSTKIT.md |
| Усложнение: mTLS | сервер требует клиентский сертификат: с ним 200, без него отказ | test/security/MtlsAndTlsVersionTest.kt |
| Усложнение: ротация pin-set | переход на запасной сертификат без обновления приложения; порядок ротации описан | OwnedBackupPinRotationTest.kt, LAB15_TRUSTKIT.md |
| Усложнение: E2EE истории | ECDH P-256 + HKDF + AES-GCM: сервер видит только шифротекст, чужой ключ не расшифрует | security/E2eeHistory.kt, E2eeHistoryTest.kt, Lab15Screen |
| Усложнение: отказ от TLS 1.0 | клиент предлагает только TLS 1.2/1.3; стенд не принимает TLS 1.0 | LocalMockTlsClient.kt, tools/LocalLab15TlsDowngradeTest.java, MtlsAndTlsVersionTest.kt |

## Как показать преподавателю

1. ⋮ → «Экран ЛР15».
2. «Проверить 4 случая» — ACCEPTED / REPLAY / INVALID_SIGNATURE / EXPIRED (это 200 / 409 / 401 / 401).
3. «Зашифровать и передать» — видно, что хранит сервер (шифротекст), второе устройство расшифровало, чужой ключ — нет.
4. Живой WSS (debug): `PHONOTEKA_MOCK_HMAC_KEY=lab15-local-fixture-key ./gradlew installDebug`, потом `python tools/run_owned_tls_contract.py` или запуск стендов из tools, `adb reverse tcp:8444 tcp:8444` → «Открыть живой WSS» → «Подключиться». Остановить стенд — пойдут reconnect 1/2/4/8/16/30 с. «Сменить пользователя» — сокет сразу закрывается.
5. mitmproxy (3 доказательства) и таблица 8 проверок — LAB15_MITM.md; сравнение с TrustKit и ротация — LAB15_TRUSTKIT.md.
6. Тесты: `./gradlew testDebugUnitTest` (RequestSigning, SignedWebSocketFrame, LocalReconnectPolicy, OwnedBackupPinRotation, MtlsAndTlsVersion, E2eeHistory и др.).

## Pinning на устройстве: три сценария (debug)

Экран: ⋮ → «Экран ЛР15» → «Открыть сценарии pinning». Внешняя сеть не используется:
HTTPS-сервер поднимается на 127.0.0.1 внутри приложения, сертификаты эфемерные (создаются
на запуск, закрытые ключи не попадают в APK), данные фиктивные.

| # | Сценарий | Ожидание | Как проверяется |
|---|---|---|---|
| 1 | Основной сертификат | соединение принято, HTTP 200 | пин основного сертификата в pin-set |
| 2 | Запасной сертификат после ротации | соединение принято без обновления приложения | второй пин в том же pin-set |
| 3 | Сертификат доверенный, но не в pin-set | отказ `Certificate pinning failure` | pin-set не содержит его пин |

Экран показывает ожидаемое и фактическое значение, причину и пишет строку в журнал
`filesDir/lab15_pin_journal.log`.

Переключатель «Учебный режим: pinning выключен» существует только в debug и только для
локального стенда: он нужен, чтобы показать, что в сценарии 3 отказ даёт именно pin-set.
В release этого кода нет (release-заглушка экрана), обычный сетевой клиент приложения
pinning не теряет.

Ограничение: стенд проверяет pinning в OkHttp-клиенте и не изображает MITM. Прокси не
используется, поэтому текст «трафик перехвачен» не показывается: сценарий 3 фиксирует
отказ клиента до отправки данных.

Тесты: `./gradlew testDebugUnitTest --tests "com.phonoteka.security.Lab15PinScenariosTest"`
(3 сценария + режим без pinning + сверка debug/release NSC).

На устройстве: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.phonoteka.security.Lab15PinScenariosDeviceTest`
— тот же код сценариев, но через TLS-стек Android (5 тестов: три сценария, режим без pinning, запись в журнал).
Класс `LocalLab15TlsClientTest` требует внешнего стенда на `localhost:8443` (`adb reverse`) и в этот прогон не входит.
