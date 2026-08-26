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
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                profiles.forEach { profile ->
                                    OutlinedButton(onClick = { editingId = profile.id }) { Text(profile.name) }
                                }
                                OutlinedButton(onClick = {
                                    editingId = UUID.randomUUID().toString()
                                    name = "Scanner"
                                    url = ""
                                    pin = ""
                                }) { Text("New") }
                            }
                        }
                    }

                    item {
                        Card(Modifier.padding(horizontal = 16.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(pin, { pin = it }, label = { Text("PIN (optional)") }, modifier = Modifier.fillMaxWidth())
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
                                        Column {
                                            Text(server.profile.name, style = MaterialTheme.typography.titleLarge)
                                            Text(server.statusText)
                                        }
                                        OutlinedButton(onClick = { viewModel.disconnect(server.profile.id) }) { Text("Disconnect") }
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
                                        OutlinedButton(onClick = { viewModel.setAllTalkgroups(server.profile.id, true) }) { Text("All") }
                                        OutlinedButton(onClick = { viewModel.setAllTalkgroups(server.profile.id, false) }) { Text("None") }
                                        OutlinedButton(onClick = { viewModel.clearHold(server.profile.id) }, enabled = server.hold != null) { Text("Clear hold") }
                                        OutlinedButton(onClick = { viewModel.clearAvoids(server.profile.id) }, enabled = server.avoided.isNotEmpty()) { Text("Clear avoids") }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = { viewModel.requestHistory(server.profile.id, true) },
                                            enabled = server.status == ConnectionStatus.CONNECTED
                                        ) { Text("History") }
                                        OutlinedButton(onClick = viewModel::skip) { Text("Skip audio") }
                                    }
                                }
                            }
                        }

                        server.systems.forEach { system ->
                            item(key = "system-${server.profile.id}-${system.systemRef}") {
                                Text(
                                    system.label,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
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
                                    OutlinedButton(onClick = { viewModel.setHold(server.profile.id, tg.systemRef, tg.talkgroupRef) }) {
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
