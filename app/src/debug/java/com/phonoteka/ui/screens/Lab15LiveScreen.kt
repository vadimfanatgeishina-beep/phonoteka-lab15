package com.phonoteka.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonoteka.BuildConfig
import com.phonoteka.security.HmacTextFrameSigner
import com.phonoteka.security.LocalLiveSocket
import com.phonoteka.security.LocalMockTlsClient
import com.phonoteka.security.PrincipalChangeSignal
import com.phonoteka.security.SocketPrincipal
import com.phonoteka.security.SocketUserBinding
import com.phonoteka.security.UserBoundSocketKeys
import javax.crypto.spec.SecretKeySpec

/** ЛР15 (debug): живой wss:// к своему стенду tools/LocalLab15WssServer.java через adb reverse tcp:8444 tcp:8444. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Lab15LiveScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val app = LocalContext.current.applicationContext as com.phonoteka.PhonotekaApp
    val keys = remember { runCatching { UserBoundSocketKeys(BuildConfig.LOCAL_FIXTURE_KEY.toByteArray()) }.getOrNull() }
    if (BuildConfig.LOCAL_FIXTURE_KEY.isEmpty() || keys == null) {
        Scaffold(topBar = {
            TopAppBar(title = { Text("Живой WSS · ЛР15") }, navigationIcon = {
                TextButton(onClick = onBack) { Text("Назад") }
            })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Локальный WSS отключён: соберите debug с переменной PHONOTEKA_MOCK_HMAC_KEY " +
                        "(≥ 16 символов, учебный ключ). Реальные секреты не вводите.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        return
    }
    // Текущий пользователь сокета = учебный пользователь ЛР15 + версия сессии.
    val binding = remember {
        SocketUserBinding(keys, currentPrincipal = { SocketPrincipal(app.lab15User.value, app.principalChanges.epoch.value) })
    }
    val client = remember { LocalMockTlsClient.create(BuildConfig.LOCAL_FIXTURE_KEY.toByteArray()) }
    val socket = remember {
        LocalLiveSocket(client, HmacTextFrameSigner(SecretKeySpec(BuildConfig.LOCAL_FIXTURE_KEY.toByteArray(), "HmacSHA256")), scope,
            userBinding = binding)
    }
    val metrics by socket.metrics.collectAsStateWithLifecycle()
    // Смена пользователя сразу закрывает сокет (USER_CHANGED).
    LaunchedEffect(socket) {
        PrincipalChangeSignal.changes(app.principalChanges.epoch).collect { socket.onPrincipalChanged() }
    }
    DisposableEffect(socket) { onDispose { socket.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() } }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Живой WSS · ЛР15") }, navigationIcon = {
            TextButton(onClick = onBack) { Text("Назад") }
        })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "wss://localhost:8444/ws — TLS-пин, билет пользователя на 30 с, подпись каждого кадра, " +
                    "переподключение 1/2/4/8/16/30 с.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Состояние канала", style = MaterialTheme.typography.titleMedium)
                    Lab15Metric("Пользователь", metrics.boundUserTag?.let { "#$it" } ?: "—")
                    Lab15Metric("Соединение", if (metrics.connected) "подключено" else "нет")
                    Lab15Metric("Попыток", metrics.attempts.toString())
                    Lab15Metric("Переподключений", metrics.reconnects.toString())
                    Lab15Metric("Отправлено", metrics.sent.toString())
                    Lab15Metric("Получено", metrics.received.toString())
                    Lab15Metric("Отклонено кадров", metrics.rejected.toString())
                    metrics.terminalError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Button(onClick = { socket.connect("wss://localhost:8444/ws") }, modifier = Modifier.fillMaxWidth()) {
                Text("Подключиться")
            }
            OutlinedButton(onClick = { socket.close() }, modifier = Modifier.fillMaxWidth()) {
                Text("Остановить")
            }
            OutlinedButton(onClick = { app.lab15SwitchUser() }, modifier = Modifier.fillMaxWidth()) {
                Text("Сменить пользователя")
            }
        }
    }
}

@Composable
private fun Lab15Metric(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}
