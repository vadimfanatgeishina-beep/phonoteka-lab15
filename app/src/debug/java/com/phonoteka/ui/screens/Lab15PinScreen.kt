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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phonoteka.security.Lab15PinCase
import com.phonoteka.security.Lab15PinJournal
import com.phonoteka.security.Lab15PinResult
import com.phonoteka.security.Lab15PinScenarios
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ЛР15 (debug): три сценария pinning на своём loopback-стенде.
 * Только локальные сертификаты и фиктивные данные; внешняя сеть не используется.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Lab15PinScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pinningEnabled by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<Map<Lab15PinCase, Lab15PinResult>>(emptyMap()) }
    var journal by remember { mutableStateOf(Lab15PinJournal.read(context)) }

    fun run(cases: List<Lab15PinCase>) {
        if (running) return
        running = true
        scope.launch {
            val produced = withContext(Dispatchers.IO) {
                cases.map { case ->
                    val result = Lab15PinScenarios.run(case, pinningEnabled)
                    Lab15PinJournal.append(context, result)
                    result
                }
            }
            results = results + produced.associateBy { it.case }
            journal = Lab15PinJournal.read(context)
            running = false
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Pinning и ротация · ЛР15") }, navigationIcon = {
            TextButton(onClick = onBack) { Text("Назад") }
        })
    }) { padding ->
    Column(
        Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Локальный стенд на устройстве: HTTPS-сервер на 127.0.0.1 предъявляет один из трёх " +
                "собственных сертификатов. Внешняя сеть не используется, данные фиктивные.",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Пины (основной + запасной) вычисляются для сертификатов этого запуска; структура pin-set " +
                "та же, что в debug network_security_config.xml и LocalMockTlsClient.",
            style = MaterialTheme.typography.bodySmall
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Учебный режим: pinning выключен", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Только для локального стенда в debug. В release и в обычном клиенте " +
                                "приложения pinning не отключается.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(checked = !pinningEnabled, onCheckedChange = { pinningEnabled = !it })
                }
                if (!pinningEnabled) {
                    Text(
                        "Pinning выключен: третий сценарий будет принят. Это доказывает, что отказ " +
                            "в сценарии 3 даёт именно pin-set.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Button(
            onClick = { run(Lab15PinCase.values().toList()) },
            enabled = !running,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (running) "Выполняется…" else "Прогнать все три сценария") }

        Lab15PinCase.values().forEach { case ->
            val result = results[case]
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(case.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Ожидается: " + Lab15PinScenarios.expected(case, pinningEnabled),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (result == null) {
                        Text("Фактически: сценарий ещё не запускался", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(
                            "Фактически: " + result.actual,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (result.accepted) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                        Text("Причина: " + result.reason, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { run(listOf(case)) }, enabled = !running) {
                        Text("Запустить сценарий")
                    }
                }
            }
        }

        Text("Журнал лабораторной", style = MaterialTheme.typography.titleMedium)
        if (journal.isEmpty()) {
            Text("Пока пусто.", style = MaterialTheme.typography.bodySmall)
        } else {
            journal.asReversed().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        TextButton(onClick = {
            Lab15PinJournal.clear(context)
            journal = emptyList()
        }) { Text("Очистить журнал") }

        Text(
            "Ограничение: стенд проверяет pinning в OkHttp-клиенте. Он не перехватывает трафик " +
                "по Wi-Fi и не изображает MITM: прокси здесь не используется.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    }
}
