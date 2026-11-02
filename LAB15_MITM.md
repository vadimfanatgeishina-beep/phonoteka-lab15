# ЛР15 — проверка mitmproxy (делается руками, только свой трафик)

Подготовка: `mitmproxy --listen-port 8080`, на телефоне Wi-Fi → прокси = IP компьютера:8080, сертификат mitm.it установить как пользовательский CA.
Стенд: `java tools/LocalLab15MockServer.java` (HTTPS 8443) и `java tools/LocalLab15WssServer.java` (WSS 8444), `adb reverse tcp:8443 tcp:8443`, `adb reverse tcp:8444 tcp:8444`.

## 3 доказательства (скриншоты в отчёт)
1. **Без пиннинга** (временно убрать блок `pin-set` из debug NSC; доверие пользовательскому CA уже включено блоком `<debug-overrides>`, отдельно добавлять `<certificates src="user"/>` не нужно) — mitmproxy видит запрос. Но подмена тела в mitmproxy → сервер отвечает 401 (HMAC), повтор (replay) → 409.
2. **С пиннингом** (как в проекте) — соединение обрывается, в logcat `SSLPeerUnverifiedException: Certificate pinning failure`, в mitmproxy «client disconnected / TLS handshake failed».
3. **HTTP-stripping** — запрос на `http://` отклоняется самим Android: `CLEARTEXT communication not permitted by network security policy`.

## Таблица 8 проверок

| # | Проверка | Ожидание | Чем проверяется |
|---|---|---|---|
| 1 | Верный подписанный запрос | 200 | RequestSigningTest, экран ЛР15 |
| 2 | Повтор того же nonce | 409 Replay | RequestSigningTest, LocalHttpsMockServerTest |
| 3 | Изменённое тело/путь | 401 Signature | RequestSigningTest |
| 4 | Время вне ±30 с | 401 Clock | RequestSigningTest |
| 5 | Чужой сертификат (подмена) | обрыв TLS | OwnedCertificateSubstitutionTest, mitmproxy №2 |
| 6 | Запасной пин после ротации | успех | OwnedBackupPinRotationTest |
| 7 | WSS: поддельный/повторённый/отражённый кадр | отклонён (rejected++) | SignedWebSocketFrameTest, LocalWssMockServerTest |
| 8 | WSS: обрыв → reconnect 1/2/4/8/16/30 с, смена пользователя → закрытие | новый handshake, USER_CHANGED | LocalReconnectPolicyTest, LocalLiveSocketTest, SocketUserBindingTest |

Дополнительно: TLS 1.0 → отказ (`tools/LocalLab15TlsDowngradeTest.java`, MtlsAndTlsVersionTest); mTLS без клиентского сертификата → отказ (MtlsAndTlsVersionTest); E2EE — E2eeHistoryTest.

## Секреты
В репозитории нет ключей: HMAC-ключ стенда берётся из переменной окружения `PHONOTEKA_MOCK_HMAC_KEY` только в debug, в release пустая строка. Приватные ключи сертификатов генерирует `tools/run_owned_tls_contract.py` и сразу удаляет; в проекте только публичные .cer. Canonical-строка подписи описана в LAB15_PROTOCOL.md.
