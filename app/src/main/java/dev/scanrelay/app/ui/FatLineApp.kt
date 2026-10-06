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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.scanrelay.app.model.ChannelKey
import dev.scanrelay.app.model.ConnectionStatus
import dev.scanrelay.app.model.RadioCall
import dev.scanrelay.app.model.ScannerAlert
import dev.scanrelay.app.model.ScannerState
import dev.scanrelay.app.model.ServerProfile
import dev.scanrelay.app.model.ServerScannerState
import java.util.Locale
import java.util.UUID

private enum class AppTab(val label: String, val glyph: String) {
    Scanner("Scanner", "●"),
    Channels("Channels", "≡"),
    History("History", "H"),
    Settings("Settings", "S")
}

@Composable
fun FatLineApp(viewModel: ScannerViewModel) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val scanner by viewModel.scannerState.collectAsStateWithLifecycle()
    var selectedProfileId by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(AppTab.Scanner) }

    LaunchedEffect(profiles, scanner.servers.keys) {
        val selectionValid = selectedProfileId != null && profiles.any { it.id == selectedProfileId }
        if (!selectionValid) {
            selectedProfileId = scanner.servers.values
                .firstOrNull { it.status == ConnectionStatus.CONNECTED }
                ?.profile
                ?.id
                ?: profiles.firstOrNull()?.id
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        AppTab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = { tab = item },
                                icon = { Text(item.glyph) },
                                label = { Text(item.label) }
                            )
                        }
                    }
                }
            ) { padding ->
                when (tab) {
                    AppTab.Scanner -> ScannerScreen(
                        scanner = scanner,
                        profiles = profiles,
                        selectedProfileId = selectedProfileId,
                        onSelectProfile = { selectedProfileId = it },
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize().padding(padding)
                    )
                    AppTab.Channels -> ChannelsScreen(
                        scanner = scanner,
                        profiles = profiles,
                        selectedProfileId = selectedProfileId,
                        onSelectProfile = { selectedProfileId = it },
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize().padding(padding)
                    )
                    AppTab.History -> HistoryScreen(
                        scanner = scanner,
                        profiles = profiles,
                        selectedProfileId = selectedProfileId,
                        onSelectProfile = { selectedProfileId = it },
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize().padding(padding)
                    )
                    AppTab.Settings -> SettingsScreen(
                        profiles = profiles,
                        selectedProfileId = selectedProfileId,
                        onSelectProfile = { selectedProfileId = it },
                        viewModel = viewModel,
                        modifier = Modifier.fillMaxSize().padding(padding)
                    )
                }
            }
        }
    }
}

@Composable
private fun ScannerScreen(
    scanner: ScannerState,
    profiles: List<ServerProfile>,
    selectedProfileId: String?,
    onSelectProfile: (String) -> Unit,
    viewModel: ScannerViewModel,
    modifier: Modifier
) {
    val profile = profiles.firstOrNull { it.id == selectedProfileId }
    val server = selectedProfileId?.let { scanner.servers[it] }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column(Modifier.padding(top = 16.dp)) {
                Text(
                    "FatLine",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "ThinLine-compatible scanner",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall
                )
                ProfileStrip(profiles, selectedProfileId, onSelectProfile)
            }
        }

        if (profile == null) {
            item {
                Card(Modifier.padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No scanner configured", style = MaterialTheme.typography.titleLarge)
                        Text("Add a scanner under Settings.")
                    }
                }
            }
        } else if (server == null) {
            item {
                Card(Modifier.padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(profile.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Disconnected")
                        Button(onClick = { viewModel.connect(profile) }) { Text("Connect") }
                    }
                }
            }
        } else {
            item { ScannerStatusCard(server, viewModel) }

            server.lastCall?.let { call ->
                item(key = "now-" + server.profile.id + "-" + call.id) {
                    NowPlayingCard(server, call, viewModel)
                }
            } ?: item {
                Card(Modifier.padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Waiting for traffic", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (server.paused) "Live feed is paused." else "No transmission has arrived yet.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            if (server.alerts.isNotEmpty()) {
                item {
                    Text(
                        "Alerts",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                items(server.alerts.take(3), key = { alert -> alert.title + alert.body + alert.dateTime.orEmpty() }) { alert ->
                    AlertCard(alert)
                }
            }

            item {
                Text(
                    "Recent live calls",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            val recent = server.recentCalls
            if (recent.isEmpty()) {
                item {
                    Text(
                        "No live calls received yet.",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                items(recent, key = { call -> "recent-" + call.profileId + "-" + call.id }) { call ->
                    CallRow(call, onReplay = { viewModel.replay(call.profileId, call.id) })
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun ScannerStatusCard(server: ServerScannerState, viewModel: ScannerViewModel) {
    Card(Modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(server.profile.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(server.statusText)
                }
                if (server.status == ConnectionStatus.CONNECTED) {
                    OutlinedButton(onClick = { viewModel.disconnect(server.profile.id) }) { Text("Disconnect") }
                } else {
                    Button(onClick = { viewModel.connect(server.profile) }) { Text("Reconnect") }
                }
            }

            server.error?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            val enabled = server.systems.sumOf { system -> system.talkgroups.count { it.enabled } }
            val total = server.systems.sumOf { it.talkgroups.size }
            Text(
                enabled.toString() + "/" + total + " channels enabled" +
                    if (server.serverVersion.isNullOrBlank()) "" else " · Server " + server.serverVersion,
                style = MaterialTheme.typography.bodySmall
            )

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
                ) { Text(if (server.paused) "Resume" else "Pause") }
                OutlinedButton(onClick = viewModel::skip) { Text("Skip") }
                OutlinedButton(
                    onClick = { viewModel.clearHold(server.profile.id) },
                    enabled = server.hold != null || server.holdSystemRef != null
                ) { Text("Clear hold") }
            }
        }
    }
}

@Composable
private fun NowPlayingCard(server: ServerScannerState, call: RadioCall, viewModel: ScannerViewModel) {
    val key = ChannelKey(call.systemRef, call.talkgroupRef)
    val talkgroup = server.systems
        .firstOrNull { it.systemRef == call.systemRef }
        ?.talkgroups
        ?.firstOrNull { it.talkgroupRef == call.talkgroupRef }

    Card(Modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("LATEST TRANSMISSION", style = MaterialTheme.typography.labelLarge)
            Text(call.talkgroupLabel, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            InfoRow("System", call.systemLabel)
            InfoRow("TGID", call.talkgroupRef.toString())
            talkgroup?.tag?.takeIf { it.isNotBlank() }?.let { InfoRow("Tag", it) }
            call.sourceDisplay?.let { InfoRow(if (call.sources.size > 1) "Units" else "Unit", it) }
            call.frequency?.let { InfoRow("Frequency", formatFrequency(it)) }
            call.durationSeconds?.let { InfoRow("Duration", String.format(Locale.US, "%.1f s", it)) }
            if (call.dateTime.isNotBlank()) InfoRow("Time", call.dateTime)

            call.transcript?.takeIf { it.isNotBlank() }?.let {
                HorizontalDivider()
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = {
                    if (server.hold == key) viewModel.clearHold(server.profile.id)
                    else viewModel.setHold(server.profile.id, call.systemRef, call.talkgroupRef)
                }) { Text(if (server.hold == key) "Release TG" else "Hold TG") }

                OutlinedButton(onClick = {
                    viewModel.setSystemHold(
                        server.profile.id,
                        if (server.holdSystemRef == call.systemRef) null else call.systemRef
                    )
                }) { Text(if (server.holdSystemRef == call.systemRef) "Release SYS" else "Hold SYS") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = {
                    viewModel.avoid(
                        server.profile.id,
                        call.systemRef,
                        call.talkgroupRef,
                        key !in server.avoided
                    )
                }) { Text(if (key in server.avoided) "Unavoid" else "Avoid") }

                OutlinedButton(onClick = { viewModel.replay(call.profileId, call.id) }) { Text("Replay") }
                OutlinedButton(onClick = viewModel::skip) { Text("Skip") }
            }
        }
    }
}

@Composable
private fun ChannelsScreen(
    scanner: ScannerState,
    profiles: List<ServerProfile>,
    selectedProfileId: String?,
    onSelectProfile: (String) -> Unit,
    viewModel: ScannerViewModel,
    modifier: Modifier
) {
    val server = selectedProfileId?.let { scanner.servers[it] }
    var channelQuery by remember(selectedProfileId) { mutableStateOf("") }
    var favoritesOnly by remember(selectedProfileId) { mutableStateOf(false) }
    val normalizedQuery = channelQuery.trim().lowercase()
    val visibleSystems = server?.systems.orEmpty().mapNotNull { system ->
        val systemMatches = normalizedQuery.isNotEmpty() && system.label.lowercase().contains(normalizedQuery)
        val visibleTalkgroups = system.talkgroups.filter { talkgroup ->
            val favoriteMatches = !favoritesOnly || talkgroup.favorite
            val queryMatches = normalizedQuery.isEmpty() || systemMatches ||
                talkgroup.displayName.lowercase().contains(normalizedQuery) ||
                talkgroup.tag.lowercase().contains(normalizedQuery) ||
                talkgroup.talkgroupRef.toString().contains(normalizedQuery)
            favoriteMatches && queryMatches
        }
        system.copy(talkgroups = visibleTalkgroups).takeIf { visibleTalkgroups.isNotEmpty() }
    }

    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Column(Modifier.padding(top = 16.dp)) {
                Text(
                    "Channels",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                ProfileStrip(profiles, selectedProfileId, onSelectProfile)
            }
        }

        if (server == null) {
            item {
                Text(
                    "Connect the selected scanner to load systems and talkgroups.",
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = { viewModel.setAllTalkgroups(server.profile.id, true) }) { Text("Enable all") }
                    OutlinedButton(onClick = { viewModel.setAllTalkgroups(server.profile.id, false) }) { Text("Disable all") }
                    OutlinedButton(
                        onClick = { viewModel.clearAvoids(server.profile.id) },
                        enabled = server.avoided.isNotEmpty()
                    ) { Text("Clear avoids") }
                }
            }

            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = channelQuery,
                        onValueChange = { channelQuery = it },
                        label = { Text("Search channels") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = { favoritesOnly = !favoritesOnly }) {
                            Text(if (favoritesOnly) "★ Favorites only" else "☆ Favorites only")
                        }
                        if (channelQuery.isNotBlank()) {
                            OutlinedButton(onClick = { channelQuery = "" }) { Text("Clear") }
                        }
                        Text(
                            visibleSystems.sumOf { it.talkgroups.size }.toString() + " shown",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (server.scanLists.isNotEmpty()) {
                item {
                    Text(
                        "Scan Lists",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                itemsIndexed(
                    server.scanLists,
                    key = { index, scanList -> "scan-list-" + server.profile.id + "-" + index + "-" + scanList.id }
                ) { _, scanList ->
                    val enabledKeys = server.systems
                        .flatMap { it.talkgroups }
                        .filter { it.enabled }
                        .map { it.key }
                        .toSet()
                    val enabledCount = scanList.channels.count { it in enabledKeys }

                    Card(Modifier.padding(horizontal = 16.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(scanList.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    enabledCount.toString() + "/" + scanList.channels.size + " enabled",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            OutlinedButton(
                                onClick = { viewModel.setChannels(server.profile.id, scanList.channels, true) },
                                enabled = scanList.channels.isNotEmpty()
                            ) { Text("Enable") }
                            OutlinedButton(
                                onClick = { viewModel.setChannels(server.profile.id, scanList.channels, false) },
                                enabled = scanList.channels.isNotEmpty()
                            ) { Text("Disable") }
                        }
                    }
                }
                item { HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
            }

            if (visibleSystems.isEmpty()) {
                item {
                    Text("No channels match the current filter.", modifier = Modifier.padding(16.dp))
                }
            }

            visibleSystems.forEachIndexed { systemIndex, system ->
                item(key = "system-" + server.profile.id + "-" + systemIndex + "-" + system.systemRef) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(system.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                system.talkgroups.count { it.enabled }.toString() + "/" + system.talkgroups.size + " enabled",
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
                            ) { Text(if (server.holdSystemRef == system.systemRef) "Held" else "Hold") }
                            OutlinedButton(
                                onClick = { viewModel.setSystemTalkgroups(server.profile.id, system.systemRef, true) }
                            ) { Text("All") }
                            OutlinedButton(
                                onClick = { viewModel.setSystemTalkgroups(server.profile.id, system.systemRef, false) }
                            ) { Text("None") }
                        }
                    }
                }

                itemsIndexed(
                    system.talkgroups,
                    key = { talkgroupIndex, tg -> "tg-" + server.profile.id + "-" + systemIndex + "-" + talkgroupIndex + "-" + tg.systemRef + "-" + tg.talkgroupRef }
                ) { _, tg ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = tg.enabled,
                            onCheckedChange = { enabled ->
                                viewModel.setTalkgroup(server.profile.id, tg.systemRef, tg.talkgroupRef, enabled)
                            }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(tg.displayName)
                            Text(
                                "TG " + tg.talkgroupRef +
                                    if (tg.tag.isBlank()) "" else " · " + tg.tag,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        OutlinedButton(
                            onClick = { viewModel.setFavorite(server.profile.id, tg.systemRef, tg.talkgroupRef, !tg.favorite) }
                        ) { Text(if (tg.favorite) "★" else "☆") }
                        OutlinedButton(onClick = {
                            if (server.hold == tg.key) viewModel.clearHold(server.profile.id)
                            else viewModel.setHold(server.profile.id, tg.systemRef, tg.talkgroupRef)
                        }) { Text(if (server.hold == tg.key) "Held" else "Hold") }
                    }
                    HorizontalDivider(Modifier.padding(start = 64.dp))
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun HistoryScreen(
    scanner: ScannerState,
    profiles: List<ServerProfile>,
    selectedProfileId: String?,
    onSelectProfile: (String) -> Unit,
    viewModel: ScannerViewModel,
    modifier: Modifier
) {
    val server = selectedProfileId?.let { scanner.servers[it] }
    var historyQuery by remember(selectedProfileId) { mutableStateOf("") }
    val normalizedHistoryQuery = historyQuery.trim().lowercase()
    val visibleHistory = server?.history.orEmpty().filter { call ->
        normalizedHistoryQuery.isEmpty() ||
            call.systemLabel.lowercase().contains(normalizedHistoryQuery) ||
            call.talkgroupLabel.lowercase().contains(normalizedHistoryQuery) ||
            call.talkgroupRef.toString().contains(normalizedHistoryQuery) ||
            call.id.toString().contains(normalizedHistoryQuery) ||
            call.dateTime.lowercase().contains(normalizedHistoryQuery) ||
            call.sourceDisplay?.lowercase()?.contains(normalizedHistoryQuery) == true ||
            call.transcript?.lowercase()?.contains(normalizedHistoryQuery) == true
    }
    val latestCall = server?.lastCall
    val archiveFilterLabel = server?.let { current ->
        when {
            current.historyTalkgroupRef != null -> {
                val tgRef = current.historyTalkgroupRef
                val system = current.systems.firstOrNull { it.systemRef == current.historySystemRef }
                val talkgroup = system?.talkgroups?.firstOrNull { it.talkgroupRef == tgRef }
                "Server filter: " + (talkgroup?.displayName ?: "TG $tgRef")
            }
            current.historySystemRef != null -> {
                val systemRef = current.historySystemRef
                val system = current.systems.firstOrNull { it.systemRef == systemRef }
                "Server filter: " + (system?.label ?: "System $systemRef")
            }
            else -> null
        }
    }

    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.padding(top = 16.dp)) {
                Text(
                    "History",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                ProfileStrip(profiles, selectedProfileId, onSelectProfile)
            }
        }

        if (server == null) {
            item { Text("Connect the selected scanner to retrieve history.", modifier = Modifier.padding(16.dp)) }
        } else {
            item {
                LazyRow(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Button(
                            onClick = { viewModel.requestHistory(server.profile.id, true) },
                            enabled = server.status == ConnectionStatus.CONNECTED
                        ) { Text(if (archiveFilterLabel == null) "Refresh archive" else "All archive") }
                    }
                    latestCall?.let { call ->
                        item {
                            OutlinedButton(
                                onClick = {
                                    viewModel.requestHistory(
                                        server.profile.id,
                                        true,
                                        call.systemRef,
                                        call.talkgroupRef
                                    )
                                },
                                enabled = server.status == ConnectionStatus.CONNECTED
                            ) { Text("Current TG") }
                        }
                        item {
                            OutlinedButton(
                                onClick = {
                                    viewModel.requestHistory(
                                        server.profile.id,
                                        true,
                                        call.systemRef,
                                        null
                                    )
                                },
                                enabled = server.status == ConnectionStatus.CONNECTED
                            ) { Text("Current SYS") }
                        }
                    }
                    if (server.historyHasMore) {
                        item {
                            OutlinedButton(
                                onClick = { viewModel.requestHistory(server.profile.id, false) },
                                enabled = server.status == ConnectionStatus.CONNECTED
                            ) { Text("More") }
                        }
                    }
                }
            }

            archiveFilterLabel?.let { label ->
                item {
                    Text(
                        label,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = historyQuery,
                        onValueChange = { historyQuery = it },
                        label = { Text("Search loaded history") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            visibleHistory.size.toString() + "/" + server.history.size + " shown",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (historyQuery.isNotBlank()) {
                            OutlinedButton(onClick = { historyQuery = "" }) { Text("Clear") }
                        }
                    }
                }
            }

            if (server.history.isEmpty()) {
                item { Text("No calls loaded.", modifier = Modifier.padding(16.dp)) }
            } else if (visibleHistory.isEmpty()) {
                item { Text("No loaded calls match the search.", modifier = Modifier.padding(16.dp)) }
            } else {
                items(visibleHistory, key = { call -> "history-" + call.profileId + "-" + call.id }) { call ->
                    CallRow(call, onReplay = { viewModel.replay(call.profileId, call.id) })
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SettingsScreen(
    profiles: List<ServerProfile>,
    selectedProfileId: String?,
    onSelectProfile: (String) -> Unit,
    viewModel: ScannerViewModel,
    modifier: Modifier
) {
    val loginState by viewModel.accountLogin.collectAsStateWithLifecycle()
    var editingId by remember(selectedProfileId) {
        mutableStateOf(selectedProfileId ?: UUID.randomUUID().toString())
    }
    val editing = profiles.firstOrNull { it.id == editingId }
    var name by remember(editingId, editing?.name) { mutableStateOf(editing?.name ?: "Scanner") }
    var url by remember(editingId, editing?.baseUrl) { mutableStateOf(editing?.baseUrl ?: "") }
    var username by remember(editingId) { mutableStateOf("") }
    var password by remember(editingId) { mutableStateOf("") }
    var pin by remember(editingId, editing?.pin) { mutableStateOf(editing?.pin ?: "") }

    LaunchedEffect(loginState.message) {
        if (loginState.message == "Signed in") password = ""
    }

    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                "Settings",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(profiles, key = { it.id }) { profile ->
                    OutlinedButton(onClick = {
                        editingId = profile.id
                        username = ""
                        password = ""
                        viewModel.clearAccountLoginStatus()
                        onSelectProfile(profile.id)
                    }) { Text(profile.name) }
                }
                item {
                    OutlinedButton(onClick = {
                        editingId = UUID.randomUUID().toString()
                        name = "Scanner"
                        url = ""
                        username = ""
                        password = ""
                        pin = ""
                        viewModel.clearAccountLoginStatus()
                    }) { Text("New") }
                }
            }
        }

        item {
            Card(Modifier.padding(horizontal = 16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Scanner account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        username,
                        { username = it },
                        label = { Text("Username / email") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        password,
                        { password = it },
                        label = { Text("Password") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation()
                    )

                    if (url.startsWith("http://", true)) {
                        Text("HTTP does not provide transport encryption.", style = MaterialTheme.typography.bodySmall)
                    }

                    Button(
                        onClick = {
                            viewModel.loginAndConnect(
                                ServerProfile(editingId, name, url, pin),
                                username,
                                password
                            )
                            onSelectProfile(editingId)
                        },
                        enabled = url.isNotBlank() && username.isNotBlank() && password.isNotBlank() && !loginState.working
                    ) { Text(if (loginState.working) "Signing in…" else "Sign in & connect") }

                    loginState.message?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    loginState.error?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }

                    Text(
                        "Your password is used only for the sign-in request. FatLine stores the returned listener PIN in Android Keystore.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    HorizontalDivider()
                    Text("Advanced", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        pin,
                        { pin = it },
                        label = { Text("Listener PIN (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val saved = viewModel.saveProfile(ServerProfile(editingId, name, url, pin))
                                onSelectProfile(saved.id)
                            },
                            enabled = url.isNotBlank()
                        ) { Text("Save") }

                        OutlinedButton(
                            onClick = {
                                val profile = ServerProfile(editingId, name, url, pin)
                                viewModel.connect(profile)
                                onSelectProfile(editingId)
                            },
                            enabled = url.isNotBlank() && pin.isNotBlank()
                        ) { Text("Connect with PIN") }

                        if (editing != null) {
                            OutlinedButton(onClick = {
                                viewModel.deleteProfile(editingId)
                                editingId = UUID.randomUUID().toString()
                                name = "Scanner"
                                url = ""
                                username = ""
                                password = ""
                                pin = ""
                                viewModel.clearAccountLoginStatus()
                            }) { Text("Delete") }
                        }
                    }
                }
            }
        }

        item {
            Card(Modifier.padding(horizontal = 16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("About", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Independent ThinLine-compatible Android client.")
                    Text("No ThinLine app code, artwork, package name, or branding is included.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun ProfileStrip(
    profiles: List<ServerProfile>,
    selectedProfileId: String?,
    onSelectProfile: (String) -> Unit
) {
    if (profiles.isEmpty()) return

    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(profiles, key = { it.id }) { profile ->
            if (profile.id == selectedProfileId) {
                Button(onClick = { onSelectProfile(profile.id) }) { Text(profile.name) }
            } else {
                OutlinedButton(onClick = { onSelectProfile(profile.id) }) { Text(profile.name) }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CallRow(call: RadioCall, onReplay: () -> Unit) {
    Card(Modifier.padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(call.talkgroupLabel, fontWeight = FontWeight.SemiBold)
                Text(call.systemLabel + " · TG " + call.talkgroupRef, style = MaterialTheme.typography.bodySmall)
                call.sourceDisplay?.let { Text("Unit: " + it, style = MaterialTheme.typography.bodySmall) }
                if (call.dateTime.isNotBlank()) Text(call.dateTime, style = MaterialTheme.typography.bodySmall)
                call.transcript?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(onClick = onReplay) { Text("Replay") }
        }
    }
}

@Composable
private fun AlertCard(alert: ScannerAlert) {
    Card(Modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(alert.title, fontWeight = FontWeight.SemiBold)
            Text(alert.body)
            alert.dateTime?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun formatFrequency(frequency: Long): String =
    if (frequency >= 1_000_000L) {
        String.format(Locale.US, "%.5f MHz", frequency / 1_000_000.0)
    } else {
        frequency.toString()
    }
