# Фонотека — ЛР15. Безопасная передача данных

Приложение прослушивания музыки, к которому добавлен защищённый канал: TLS с пиннингом,
подпись запросов, защита от replay и WebSocket с теми же гарантиями, что и REST.

## Что где лежит

| Требование | Где смотреть |
|---|---|
| Network Security Config, cleartext запрещён в release | `app/src/main/res/xml/network_security_config.xml` (release) и `app/src/debug/res/xml/network_security_config.xml` (debug) |
| Два SPKI-пина + `expiration` | `app/src/debug/res/xml/network_security_config.xml`, блок `pin-set` |
| Пиннинг в коде, fail closed | `app/src/debug/java/com/phonoteka/security/LocalMockTlsClient.kt` (`CertificatePinner`, два пина) |
| HMAC-подпись запроса | `app/src/main/java/com/phonoteka/security/RequestSigning.kt` (`HmacRequestSigner`, `HmacSigningInterceptor`) |
| Окно ±30 с и уникальность nonce | `RequestSigning.kt` (`verify(...)`, `ReplayGuard`) |
| Понятный текст про рассинхрон часов | `app/src/main/java/com/phonoteka/security/ClockSkewNotice.kt` |
| WebSocket wss://, backoff, подпись кадров | `security/LocalLiveSocket.kt`, `security/LocalReconnectPolicy.kt`, `security/SignedWebSocketFrames.kt` |
| E2EE истории (ECDH P-256 + AES-GCM) | `security/E2eeHistory.kt` |
| Мок-сервер (HTTPS, самоподписанный сертификат) | JVM-тесты на MockWebServer + стенды `tools/LocalLab15MockServer.java`, `tools/LocalLab15WssServer.java` |
| Таблица ошибок канала, MITM-эксперимент | `LAB15_MITM.md` |
| Сравнение с TrustKit | `LAB15_TRUSTKIT.md` |
| Протокол подписи целиком | `LAB15_PROTOCOL.md` |

## Canonical-строка подписи

Контракт зафиксирован: пять строк, разделитель — `\n` (LF), завершающего перевода строки нет.

```
METHOD\n
path\n
timestamp\n
nonce\n
sha256hex(body)
```

Пример для `POST /api/history`, тела `{"trackId":42}`, `ts=1730000000`, `nonce=6f1c…`:

```
POST
/api/history
1730000000
6f1c9d2a4b8e5f70
9f2c1c0b7a5d4e3f2b1a0c9d8e7f6a5b4c3d2e1f0a9b8c7d6e5f4a3b2c1d0e9f
```

Правила, которые нельзя менять местами с сервером:

- метод — в верхнем регистре;
- `path` — только `encodedPath`, query-строка добавляется к нему как есть (`?a=b`), если есть;
- тело подписывается **хешем** SHA-256, а не байтами: большие файлы не ломают подпись;
- `timestamp` — секунды Unix, десятичное число без ведущих нулей;
- `nonce` — 16–64 символа `[A-Za-z0-9_-]`, генерируется `SecureRandom` на каждый запрос;
- подпись — HMAC-SHA256 в hex нижнем регистре, заголовки `X-Timestamp`, `X-Nonce`, `X-Signature`.

Сервер отклоняет: `401 Signature` (подпись не сошлась), `401 Clock` (вне окна ±30 с),
`409 Replay` (nonce уже видели), `401 Nonce` (формат не подходит), `503 ReplayCacheFull`.

## Что произойдёт при сбитых часах и как это лечат

Если на устройстве вручную выставить время на 5 минут вперёд или назад, `timestamp` уедет за
окно ±30 с и сервер ответит `401`. Клиент не может отличить «часы врут» от «подпись неверна»
по коду ответа, поэтому `ClockSkewNotice` разворачивает `EXPIRED` в текст: на сколько секунд
устройство отстаёт или спешит, почему запрос отклонён и что делать (включить автосинхронизацию
времени и повторить запрос заново — старую подпись переиспользовать нельзя, у неё уже истёк nonce).

Лечение в проде: NTP-синхронизация на устройстве, а на сервере — маркер рассинхрона в ответе
(например, заголовок `X-Server-Time`), чтобы клиент сам поправил смещение и не показывал
пользователю техническую ошибку.

## План ротации пинов

В `pin-set` два пина: основной и backup. Оба посчитаны от SPKI сертификатов
`app/src/debug/res/raw/local_mock_cert.cer` и `local_mock_backup_cert.cer`, `expiration="2036-01-01"`.

Порядок плановой ротации:

1. Сгенерировать новую пару ключей и сертификат, посчитать SPKI-SHA-256:
   `openssl x509 -in new.crt -pubkey -noout | openssl pkey -pubin -outform DER | openssl dgst -sha256 -binary | openssl base64`
2. Выпустить сборку, где новый пин добавлен **третьим** (старый основной остаётся): приложение
   продолжает работать со старым сертификатом.
3. Дождаться, пока обновится достаточная доля установок.
4. Переключить сервер на новый сертификат — старые клиенты живут на backup-пине.
5. Следующим релизом убрать отживший пин.

`expiration` нужен как страховка: если ротацию забыли, пиннинг перестаёт действовать сам,
и приложение не превращается в кирпич. Именно поэтому дата стоит далеко (2036), а не «через месяц».

## Почему release не доверяет пользовательским CA

С Android 7 приложения по умолчанию доверяют только системным корневым сертификатам. Это
защита от главного сценария MITM: пользовательский CA в хранилище может поставить кто угодно
с доступом к устройству (или злоумышленник, уговоривший поставить «корпоративный профиль»),
и тогда любой прокси читает трафик приложения.

Поэтому:

- в `release` — `<certificates src="system" />`, cleartext запрещён, лабораторный сертификат
  и пины для `localhost` в прод не едут;
- в `debug` — блок `<debug-overrides>` с `src="user"`: это единственное законное место для
  CA mitmproxy, и Android сам вырезает этот блок из release-сборки.

Плата за это — неудобство отладки: чтобы читать трафик прокси, нужна именно debug-сборка.
Это правильный размен: «неудобно отлаживать» лучше, чем «любой установленный CA читает
историю прослушиваний».

## Мок-сервер: две реализации

- **MockWebServer** (зависимость `com.squareup.okhttp3:mockwebserver` + `okhttp-tls`) — внутри
  JVM-тестов: `LocalHttpsMockServerTest`, `LocalWssMockServerTest`, `MtlsAndTlsVersionTest`,
  `OwnedBackupPinRotationTest`, `OwnedCertificateSubstitutionTest`. Сертификат создаётся
  в коде через `HeldCertificate`, HTTPS — через `server.useHttps(...)`.
- **`tools/LocalLab15MockServer.java`** — отдельный стенд для проверок на устройстве
  (`adb reverse tcp:8443`). Предъявляет тот же сертификат из `res/raw`, приватный ключ
  приходит из переменной окружения `PHONOTEKA_MOCK_KEYSTORE` и в репозитории отсутствует.
  Он же выполняет серверную часть проверки подписи: HMAC, окно ±30 с, уникальность nonce.
  Намеренно без внешних зависимостей — собирается одной командой `javac`, без Gradle.

Прогон стенда и контрактных проверок:

```bash
JAVA_HOME=... python3 tools/run_owned_tls_contract.py
```

Скрипт генерирует одноразовый keystore во временной папке, компилирует стенд, прогоняет
четыре контрактных теста и удаляет приватный материал. Поэтому тест
`LocalLab15TlsClientTest` не воспроизводится в чистом клоне: он требует внешний стенд и
`adb reverse`, а новый сертификат не совпадёт с закоммиченными пинами.

## Что остаётся ручным

1. MITM-эксперимент: три доказательства (без пиннинга читается, с пиннингом рвётся,
   `http://` отклоняется NSC) — `LAB15_MITM.md`.
2. Прогон `tools/LocalLab15MockServer.java` на машине с `adb reverse` для проверки
   серверной части подписи.
