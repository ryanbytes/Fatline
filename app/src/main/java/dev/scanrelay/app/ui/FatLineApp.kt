package dev.scanrelay.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.scanrelay.app.ScannerViewModel
import dev.scanrelay.app.model.ConnectionStatus
import dev.scanrelay.app.model.ServerProfile
import java.util.UUID

@Composable
fun FatLineApp(viewModel: ScannerViewModel) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val scanner by viewModel.scannerState.collectAsStateWithLifecycle()
    var editingId by remember { mutableStateOf(profiles.firstOrNull()?.id ?: UUID.randomUUID().toString()) }
    val editing = profiles.firstOrNull { it.id == editingId }
    var name by remember(editingId, editing?.name) { mutableStateOf(editing?.name ?: "Scanner") }
    var url by remember(editingId, editing?.baseUrl) { mutableStateOf(editing?.baseUrl ?: "") }
    var pin by remember(editingId, editing?.pin) { mutableStateOf(editing?.pin ?: "") }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Scaffold { padding ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Column(Modifier.padding(16.dp)) {
                            Text("FatLine", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("Independent ThinLine-compatible Android scanner client", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    if (profiles.isNotEmpty()) {
                        item {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(profiles, key = { it.id }) { profile ->
                                    OutlinedButton(onClick = { editingId = profile.id }) { Text(profile.name) }
                                }
                                item {
                                    OutlinedButton(onClick = {
                                        editingId = UUID.randomUUID().toString()
                                        name = "Scanner"
                                        url = ""
                                        pin = ""
                                    }) { Text("New") }
                                }
                            }
                        }
                    }

                    item {
                        Card(Modifier.padding(horizontal = 16.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(
                                    pin,
                                    { pin = it },
                                    label = { Text("PIN (optional)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    visualTransformation = PasswordVisualTransformation()
                                )
                                if (url.startsWith("http://", true)) {
                                    Text("Warning: HTTP does not provide transport encryption.", style = MaterialTheme.typography.bodySmall)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { viewModel.connect(ServerProfile(editingId, name, url, pin)) },
                                        enabled = url.isNotBlank()
                                    ) { Text("Connect") }
                                    OutlinedButton(onClick = { viewModel.saveProfile(ServerProfile(editingId, name, url, pin)) }) { Text("Save") }
                                    if (editing != null) OutlinedButton(onClick = { viewModel.deleteProfile(editingId) }) { Text("Delete") }
                                }
                            }
                        }
                    }

                    scanner.servers.values.sortedBy { it.profile.name.lowercase() }.forEach { server ->
                        item(key = "server-${server.profile.id}") {
                            Card(Modifier.padding(horizontal = 16.dp)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(server.profile.name, style = MaterialTheme.typography.titleLarge)
                                            Text(server.statusText)
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            if (server.status != ConnectionStatus.CONNECTED) {
                                                OutlinedButton(onClick = { viewModel.connect(server.profile) }) { Text("Reconnect") }
                                            }
                                            OutlinedButton(onClick = { viewModel.disconnect(server.profile.id) }) { Text("Disconnect") }
                                        }
                                    }
                                    server.serverVersion?.let { Text("Server $it", style = MaterialTheme.typography.bodySmall) }
                                    server.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                    if (server.audioEncryptionEnabled) {
                                        Text(
                                            if (server.encryptionReady) "Encrypted audio ready" else "Encrypted audio key exchange pending",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = { viewModel.setPaused(server.profile.id, !server.paused) },
                                            enabled = server.status == ConnectionStatus.CONNECTED
                                        ) { Text(if (server.paused) "Resume live" else "Pause live") }
                                        OutlinedButton(onClick = { viewModel.setAllTalkgroups(server.profile.id, true) }) { Text("All") }
                                        OutlinedButton(onClick = { viewModel.setAllTalkgroups(server.profile.id, false) }) { Text("None") }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = { viewModel.clearHold(server.profile.id) },
                                            enabled = server.hold != null || server.holdSystemRef != null
                                        ) { Text("Clear hold") }
                                        OutlinedButton(onClick = { viewModel.clearAvoids(server.profile.id) }, enabled = server.avoided.isNotEmpty()) { Text("Clear avoids") }
                                        OutlinedButton(onClick = viewModel::skip) { Text("Skip audio") }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = { viewModel.requestHistory(server.profile.id, true) },
                                            enabled = server.status == ConnectionStatus.CONNECTED
                                        ) { Text("History") }
                                        if (server.historyHasMore) {
                                            OutlinedButton(
                                                onClick = { viewModel.requestHistory(server.profile.id, false) },
                                                enabled = server.status == ConnectionStatus.CONNECTED
                                            ) { Text("More") }
                                        }
                                    }
                                }
                            }
                        }

                        server.lastCall?.let { call ->
                            item(key = "latest-${server.profile.id}-${call.id}") {
                                Card(Modifier.padding(horizontal = 16.dp)) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("Latest transmission", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        Text(call.talkgroupLabel, style = MaterialTheme.typography.titleLarge)
                                        Text("${call.systemLabel} · TG ${call.talkgroupRef}", style = MaterialTheme.typography.bodySmall)
                                        if (call.dateTime.isNotBlank()) Text(call.dateTime, style = MaterialTheme.typography.bodySmall)
                                        call.transcript?.let { Text(it) }
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            OutlinedButton(onClick = {
                                                if (server.hold == call.key) viewModel.clearHold(server.profile.id)
                                                else viewModel.setHold(server.profile.id, call.systemRef, call.talkgroupRef)
                                            }) { Text(if (server.hold == call.key) "Release TG" else "Hold TG") }
                                            OutlinedButton(onClick = {
                                                viewModel.setSystemHold(
                                                    server.profile.id,
                                                    if (server.holdSystemRef == call.systemRef) null else call.systemRef
                                                )
                                            }) { Text(if (server.holdSystemRef == call.systemRef) "Release system" else "Hold system") }
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            OutlinedButton(onClick = {
                                                viewModel.avoid(
                                                    server.profile.id,
                                                    call.systemRef,
                                                    call.talkgroupRef,
                                                    call.key !in server.avoided
                                                )
                                            }) { Text(if (call.key in server.avoided) "Unavoid" else "Avoid") }
                                            OutlinedButton(onClick = viewModel::skip) { Text("Skip") }
                                            OutlinedButton(onClick = { viewModel.replay(call.profileId, call.id) }) { Text("Replay") }
                                        }
                                    }
                                }
                            }
                        }

                        server.systems.forEach { system ->
                            item(key = "system-${server.profile.id}-${system.systemRef}") {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            system.label,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        val enabledCount = system.talkgroups.count { it.enabled }
                                        Text(
                                            "$enabledCount/${system.talkgroups.size} enabled",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        OutlinedButton(
                                            onClick = {
                                                viewModel.setSystemHold(
                                                    server.profile.id,
                                                    if (server.holdSystemRef == system.systemRef) null else system.systemRef
                                                )
                                            }
                                        ) { Text(if (server.holdSystemRef == system.systemRef) "Held system" else "Hold system") }
                                        OutlinedButton(
                                            onClick = { viewModel.setSystemTalkgroups(server.profile.id, system.systemRef, true) },
                                            enabled = system.talkgroups.any { !it.enabled }
                                        ) { Text("All") }
                                        OutlinedButton(
                                            onClick = { viewModel.setSystemTalkgroups(server.profile.id, system.systemRef, false) },
                                            enabled = system.talkgroups.any { it.enabled }
                                        ) { Text("None") }
                                    }
                                }
                            }
                            items(system.talkgroups, key = { "tg-${server.profile.id}-${it.systemRef}-${it.talkgroupRef}" }) { tg ->
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(tg.enabled, { viewModel.setTalkgroup(server.profile.id, tg.systemRef, tg.talkgroupRef, it) })
                                    Column(Modifier.weight(1f)) {
                                        Text(tg.displayName)
                                        Text(
                                            "TG ${tg.talkgroupRef}${if (tg.tag.isNotBlank()) " · ${tg.tag}" else ""}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    OutlinedButton(onClick = { viewModel.setFavorite(server.profile.id, tg.systemRef, tg.talkgroupRef, !tg.favorite) }) {
                                        Text(if (tg.favorite) "★" else "☆")
                                    }
                                    OutlinedButton(onClick = {
                                        if (server.hold == tg.key) viewModel.clearHold(server.profile.id)
                                        else viewModel.setHold(server.profile.id, tg.systemRef, tg.talkgroupRef)
                                    }) {
                                        Text(if (server.hold == tg.key) "Held" else "Hold")
                                    }
                                    OutlinedButton(onClick = { viewModel.avoid(server.profile.id, tg.systemRef, tg.talkgroupRef, tg.key !in server.avoided) }) {
                                        Text(if (tg.key in server.avoided) "Unavoid" else "Avoid")
                                    }
                                }
                                HorizontalDivider(Modifier.padding(start = 64.dp))
                            }
                        }
                    }

                    if (scanner.alerts.isNotEmpty()) {
                        item {
                            Text(
                                "Alerts",
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                        items(scanner.alerts.take(100)) { alert ->
                            Card(Modifier.padding(horizontal = 16.dp)) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(alert.title, fontWeight = FontWeight.SemiBold)
                                    Text(alert.serverName, style = MaterialTheme.typography.bodySmall)
                                    Text(alert.body)
                                    alert.dateTime?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }

                    if (scanner.history.isNotEmpty()) {
                        item {
                            Text(
                                "Recent transmissions",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                        items(scanner.history, key = { "history-${it.profileId}-${it.id}" }) { call ->
                            Card(Modifier.padding(horizontal = 16.dp)) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(call.talkgroupLabel, fontWeight = FontWeight.SemiBold)
                                        Text("${call.serverName} · ${call.systemLabel}", style = MaterialTheme.typography.bodySmall)
                                        if (call.dateTime.isNotBlank()) Text(call.dateTime, style = MaterialTheme.typography.bodySmall)
                                        call.transcript?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                    }
                                    OutlinedButton(onClick = { viewModel.replay(call.profileId, call.id) }) { Text("Replay") }
                                }
                            }
                        }
                    }

                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}
