# ЛР15 — конкретный контракт собственного mock-канала

Только loopback/localhost и собственные временные сертификаты/фиктивные данные. Никаких настоящих аккаунтов, ключей сервисов и чужого трафика. Приватные fixture-файлы не входят в source ZIP. Этот документ не утверждает, что Android MITM/NSC tests уже прошли.

## HTTP canonical

UTF-8, ровно пять строк, LF между строками, без завершающего LF:

```text
METHOD_UPPERCASE
RAW_PATH_WITH_OPTIONAL_RAW_QUERY
TIMESTAMP_DECIMAL_SECONDS
NONCE
BODY_SHA256_LOWERCASE_HEX
```

- Метод upper-case с Locale.ROOT. Path — encoded/raw path, query сохраняется буквально после `?`; не декодировать, не переставлять параметры, не удалять query из подписанного target.
- Тело — фактические bytes, не повторно сериализованный JSON; для GET это пустое тело. SHA-256 hex — lowercase.
- X-Timestamp, X-Nonce, X-Signature. Signature — HMAC-SHA256 canonical bytes, hex. В логах нет ключа, тела, signature, nonce или query/ticket.
- Окно включительно ±30 секунд. Nonce сохраняется до `timestamp+30`, удаляется только при `now > validUntil`. Future timestamp +30 требует retention до now+60, не просто30 секунд после первого запроса.
- Сначала подпись, затем consume nonce: изменённое тело не «сжигает» nonce валидного запроса. Cache capacity fail-closed, без вытеснения ещё действующих записей. Cache живёт в процессе fixture; restart replay-persistence не реализована.

| Ответ | Значение |
|---|---|
| 200 `accepted` | Подпись и время валидны; новый nonce |
| 409 `Replay` | Валидный подписанный nonce уже использован |
| 401 `Signature` | Неверная/повреждённая подпись, тело, target или заголовки |
| 401 `Clock` | Timestamp вне ±30 секунд |
| 503 `ReplayCacheFull` | Нет безопасного места в replay-cache |
| 413 | Тело >64 KiB |

Clock: синхронизировать системные часы своего клиента/сервера, сформировать новый запрос с новым timestamp/nonce. Не расширять окно до5 минут, не отключать проверку и не повторять старый подписанный запрос. Отдельный UI-текст Clock ещё нужно связать с экраном; общая транспортная ошибка не считается понятной диагностикой рассинхрона.

## WSS

- Только `wss://localhost:<owned-port>/ws`, без query/секретов в URL.
- HTTP handshake подписывается HTTP interceptor; X-Lab-Channel содержит новый UUID для каждого соединения. Это fixture authentication общей HMAC-подписью, НЕ user identity JWT/ticket.
- Для sensitive frame canonical: `WS`, channel, timestamp, nonce, SHA-256 decoded payload, разделены LF. Payload в wire передаётся Base64. Request/response channels различаются суффиксами `/request` и `/response`.
- После reconnect: новый handshake/signature/nonce/channel; старые frames не принимаются. Reflection request→response отклоняется.
- Backoff1/2/4/8/16/30 секунд; последующие ожидания ограничены30. Число reconnect пока ограничено5 по исходной политике; это не бесконечный background service.
- На закрытии экрана coroutine/socket закрываются. Не создавать GlobalScope и не продолжать работу экрана в фоне.
- User-bound short-lived ticket/backend identity для handshake остаётся отдельным незакрытым пунктом финального приложения; долгоживущий refresh нельзя добавлять в URL.

## Pins/ротация/CA

Debug NSC и LocalMockTlsClient содержат primary+backup SPKI pins для собственных публичных fixture certificates. Значения в этом документе не дублируются. Release NSC доверяет system CA, cleartext=false; debug local CA не переносится в release.

Ротация: заранее добавить backup public certificate/SPKI; проверить позитивный запрос primary; заменить OWNED server certificate на backup; тем же клиентом проверить успех; третьим trusted-but-unpinned сертификатом проверить именно pinning refusal. JVM OwnedBackupPinRotationTest выполняет этот policy experiment на временных сертификатах; он НЕ доказывает совпадение текущих Android NSC/публичных CER/pins и actual Android rotation.

NSC pin expiration отключает NSC pin enforcement после срока; это не автоматическое fail-closed. OkHttp CertificatePinner продолжает проверку своего списка. Нужно обновлять конфигурацию до expiration и отдельно тестировать сроки сертификатов/пинов. Debug разрешение proxy CA само по себе не доказывает pinning; сейчас user proxy CA автоматически не доверен.

## Локальный воспроизводимый эксперимент

```bash
# JAVA_HOME — JDK17. Генерирует/удаляет собственный ephemeral PKCS12, секреты не выводятся.
python tools/run_owned_tls_contract.py

./gradlew :app:testDebugUnitTest \
  --tests 'com.phonoteka.security.RequestSigningTest' \
  --tests 'com.phonoteka.security.SignedWebSocketFrameTest' \
  --tests 'com.phonoteka.security.LocalReconnectPolicyTest' \
  --tests 'com.phonoteka.security.OwnedBackupPinRotationTest'
```

Для Android: собственный fixture и adb reverse по RUN_MATRIX.md. Нужны реальные отдельные доказательства: без pinning proxy читает только OWNED traffic; с pinning блокируется; HTTP stripping запрещён NSC; release user-CA не принимает; actual backup certificate проходит. Нельзя выдавать JVM certificate substitution или этот Java loopback experiment за все три MITM-доказательства.
