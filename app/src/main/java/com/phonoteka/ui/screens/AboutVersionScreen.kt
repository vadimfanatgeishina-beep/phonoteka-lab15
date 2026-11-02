package com.phonoteka.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonoteka.VersionInfo

/** Экран «О версии»: что требовала лаба, что сделано и где это найти. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutVersionScreen(onBack: () -> Unit, onOpenDemo: () -> Unit = {}) {
    Scaffold(topBar = {
        TopAppBar(title = { Text("О версии") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
        })
    }) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(VersionInfo.TITLE, style = MaterialTheme.typography.titleLarge)
                Text(VersionInfo.SUMMARY, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp))
                if (VersionInfo.HAS_DEMO) {
                    Button(onClick = onOpenDemo, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Text("Открыть экран лабы")
                    }
                }
            }
            itemsIndexed(VersionInfo.items) { index, item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${index + 1}. ${item.requirement}", style = MaterialTheme.typography.titleSmall)
                        Text("Что сделано: ${item.whatDone}", style = MaterialTheme.typography.bodyMedium)
                        Text("Где: ${item.where}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
