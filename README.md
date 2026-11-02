# ЛР15 — Безопасная передача

Версия v15: Фонотека + только требования ЛР15. Экран лабы открывается из меню **⋮ → «Экран ЛР15»**, список требований — **⋮ → «О версии»**.

## Как запустить

Debug-сборка, учебный стенд только на loopback:

```bash
export PHONOTEKA_MOCK_HMAC_KEY=<учебный ключ ≥ 16 символов>
./gradlew assembleDebug
```

Для ручных сценариев со стендом дополнительно нужен PKCS12-ключ:

```bash
java tools/LocalLab15MockServer.java     # HTTPS 8443, читает PHONOTEKA_MOCK_KEYSTORE* из env
adb reverse tcp:8443 tcp:8443
```

Приватные ключи и пароли в репозитории не хранятся: `tools/run_owned_tls_contract.py` генерирует их во временной папке и удаляет. В release `LOCAL_FIXTURE_KEY` пустая, поэтому локальные демонстрации в релиз не попадают.

## Требования ТЗ и где они в коде

| Требование | Где |
|---|---|
| HTTPS мок-сервер с самоподписанным сертификатом | `tools/LocalLab15MockServer.java`, `tools/run_owned_tls_contract.py` |
| NSC: cleartext запрещён в release, отдельный debug-профиль | `app/src/main/res/xml/network_security_config.xml`, `app/src/debug/res/xml/network_security_config.xml` |
| Пиннинг: два SPKI-пина с expiration, fail closed | debug NSC (`pin-set`, срок 2036-01-01) + `security/LocalMockTlsClient.kt`, `security/Lab15PinScenarios.kt` |
| HMAC-подпись запроса | `security/RequestSigning.kt` |
| Replay-защита: окно ±30 с, уникальность nonce | `HmacRequestSigner.verify`, `ReplayGuard` |
| Понятное сообщение при рассинхроне часов | `security/ClockSkewNotice.kt`, строка в блоке «1. Подпись запросов» |
| WebSocket wss:// с backoff и подписью кадров | `security/LocalLiveSocket.kt`, `LocalReconnectPolicy.kt`, `SignedWebSocketFrames.kt`, `SocketUserBinding.kt` |
| Таблица 8 проверок | `LAB15_MITM.md` |

Усложнения (TrustKit, mTLS, ротация, E2EE, отказ TLS 1.0) — в `LAB_15.md` и `LAB15_TRUSTKIT.md`.

## Canonical-строка подписи

UTF-8, ровно пять строк, разделитель LF, завершающего LF нет:

```text
METHOD_UPPERCASE
RAW_PATH_WITH_OPTIONAL_RAW_QUERY
TIMESTAMP_DECIMAL_SECONDS
NONCE
SHA256_HEX_OF_BODY
```

Подпись — HMAC-SHA256 по этим байтам, hex. Порядок и разделители — часть контракта: их изменение ломает совместимость с сервером. Подробнее — `LAB15_PROTOCOL.md`.

## План ротации пинов

1. В `pin-set` всегда два пина: текущий сертификат и запасной, выпущенный заранее.
2. Новый сертификат вводится в эксплуатацию, пока запасной пин ещё не истёк: приложение продолжает работать на старом.
3. После того как все клиенты обновлены, старый пин убирается, а на его место ставится следующий запасной.
4. `expiration` в `pin-set` ограничивает срок жизни пинов: если пины не обновить, проверка перестаёт применяться, и приложение не «залипает» навсегда на мёртвом сертификате.

Запасной пин проверяется на устройстве: `Lab15PinScenariosDeviceTest.backupCertificateIsAcceptedAfterRotation`.

## Почему в release не доверяем user-CA

С Android 7 приложения не доверяют пользовательским сертификатам автоматически, и это правильно: иначе любой, кто поставил свой CA (или убедил пользователя его поставить), читал бы трафик приложения. Поэтому:

- в release `trust-anchors` — только `system`, cleartext запрещён;
- CA mitmproxy допускается исключительно в `debug-overrides` debug-сборки, где `android:debuggable="true"`;
- пиннинг работает поверх доверия: даже «легальный» CA не поможет, если SPKI не совпал с `pin-set`.

Проверка release-сборки: `usesCleartextTraffic=false`, в NSC только `system`.

## Что осталось руками

Три доказательства MITM-эксперимента (без пиннинга читается, с пиннингом рвётся, `http://` отклоняется) — пошагово в `LAB15_MITM.md`.
