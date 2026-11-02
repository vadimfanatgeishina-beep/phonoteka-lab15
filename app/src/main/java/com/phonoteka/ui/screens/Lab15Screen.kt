package com.phonoteka.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonoteka.PhonotekaApp
import com.phonoteka.security.ClockSkewNotice
import com.phonoteka.security.E2eeHistory
import com.phonoteka.security.E2eeParty
import com.phonoteka.security.HmacRequestSigner
import com.phonoteka.security.ReplayGuard
import com.phonoteka.security.SignedRequest

/**
 * ЛР15: безопасная передача. Демонстрация без сети:
 * 1) HMAC-подпись запроса (method+path+ts+nonce+sha256(body)), окно ±30 с, nonce-кэш;
 * 2) E2EE истории (ECDH P-256 + AES-GCM) — сервер видит только шифротекст;
 * 3) живой wss:// к своему локальному стенду (только debug).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Lab15Screen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as PhonotekaApp
    var live by remember { mutableStateOf(false) }
    var pinDemo by remember { mutableStateOf(false) }
    if (live) { Lab15LiveScreen(onBack = { live = false }); return }
    if (pinDemo) { Lab15PinScreen(onBack = { pinDemo = false }); return }
    val user by app.lab15User.collectAsStateWithLifecycle()
    var hmacLog by remember { mutableStateOf(listOf<String>()) }
    var e2eeLog by remember { mutableStateOf(listOf<String>()) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Безопасная передача · ЛР15") }, navigationIcon = {
            TextButton(onClick = onBack) { Text("Назад") }
        })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Release: только HTTPS, системные CA, cleartext запрещён. Debug: свой самоподписанный " +
                    "сертификат localhost + 2 SPKI-пина с expiration.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Lab15Section(
                title = "1. Подпись запросов (HMAC-SHA256)",
                description = "Каноническая строка: method, path, sha256(body), timestamp, nonce. " +
                    "Сервер проверяет подпись, окно ±30 с и уникальность nonce."
            ) {
                Button(onClick = { hmacLog = runHmacDemo() }) { Text("Проверить 4 случая") }
                if (hmacLog.isNotEmpty()) Lab15Result(hmacLog)
            }

            Lab15Section(
                title = "2. E2EE истории прослушиваний",
                description = "ECDH P-256 + AES-GCM: на сервере остаётся только шифротекст, " +
                    "чужой ключ не расшифрует запись."
            ) {
                Button(onClick = { e2eeLog = runE2eeDemo() }) { Text("Зашифровать и передать") }
                if (e2eeLog.isNotEmpty()) Lab15Result(e2eeLog)
            }

            Lab15Section(
                title = "3. WebSocket wss:// со своим стендом",
                description = "wss://localhost:8444/ws, билет на 30 с, подпись каждого кадра, " +
                    "переподключение 1/2/4/8/16/30 с. Смена пользователя закрывает сокет."
            ) {
                Text("Пользователь сокета: $user", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { app.lab15SwitchUser() }) { Text("Сменить пользователя (сокет закроется)") }
                Button(onClick = { live = true }) { Text("Открыть живой WSS") }
            }

            Lab15Section(
                title = "4. Pinning: три сценария",
                description = "Основной сертификат; запасной после ротации; доверенный системой, но не в pin-set. " +
                    "Только debug, собственный loopback-стенд, фиктивные данные."
            ) {
                Button(onClick = { pinDemo = true }) { Text("Открыть сценарии pinning") }
            }
        }
    }
}

/** Секция экрана: заголовок, пояснение и содержимое — в одной карточке, как на экранах ЛР13/ЛР14. */
@Composable
private fun Lab15Section(title: String, description: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            content()
        }
    }
}

/** Ответ на действие: отдельная карточка, чтобы результат нельзя было принять за пояснение. */
@Composable
private fun Lab15Result(lines: List<String>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Результат", style = MaterialTheme.typography.labelSmall)
            lines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

private const val DEMO_KEY = "lab15-public-demo-key-not-a-secret"

private fun runHmacDemo(): List<String> {
    val now = System.currentTimeMillis() / 1000
    val server = HmacRequestSigner.forMockServer(DEMO_KEY.toByteArray()) { now }
    val guard = ReplayGuard()
    val body = "{\"trackId\":42}".toByteArray()
    val ok = server.sign("POST", "/api/history", body)
    val r1 = server.verify("POST", "/api/history", body, ok, guard)
    val r2 = server.verify("POST", "/api/history", body, ok, guard)
    val other = server.sign("POST", "/api/history", body)
    val r3 = server.verify("POST", "/api/history", "{\"trackId\":43}".toByteArray(), other, guard)
    val old = HmacRequestSigner.forMockServer(DEMO_KEY.toByteArray()) { now - 60 }.sign("POST", "/api/history", body)
    val r4 = server.verify("POST", "/api/history", body, old, guard)
    return listOf(
        "Правильный запрос → $r1 (HTTP 200)",
        "Тот же nonce ещё раз → $r2 (HTTP 409)",
        "Подменили тело → $r3 (HTTP 401)",
        "Время на 60 с старше → $r4 (HTTP 401, окно ±30 с)",
        // ЛР15, Шаг 8: вместо «401» — объяснение, что случилось с часами и что делать.
        ClockSkewNotice.describeResult(r4, old.timestampSeconds, now)
    )
}

private fun runE2eeDemo(): List<String> {
    val phone = E2eeParty()
    val tablet = E2eeParty()
    val keyPhone = phone.sessionKey(tablet.publicKey)
    val keyTablet = tablet.sessionKey(phone.publicKey)
    val sealed = E2eeHistory.seal(keyPhone, "Слушал: трек #42, 23:50", "history/v1")
    val opened = E2eeHistory.open(keyTablet, sealed, "history/v1")
    val stranger = E2eeHistory.open(E2eeParty().sessionKey(phone.publicKey), sealed, "history/v1")
    return listOf(
        "Сервер хранит: " + sealed.take(40) + "…",
        "Второе устройство расшифровало: $opened",
        "Чужой ключ: " + (stranger ?: "не расшифровано (AEAD-тег не сошёлся)")
    )
}

@Suppress("unused") private fun describe(s: SignedRequest) = "ts=${s.timestampSeconds}"
