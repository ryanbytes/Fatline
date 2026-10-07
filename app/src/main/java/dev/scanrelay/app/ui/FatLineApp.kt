Warning: truncated output (original token count: 43560)
Total output lines: 3422

package dev.scanrelay.app.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.scanrelay.app.AccountLoginPolicy
import dev.scanrelay.app.ScannerViewModel
import dev.scanrelay.app.alerts.AlertSoundPreferences
import dev.scanrelay.app.alerts.ServerAlertSounds
import dev.scanrelay.app.model.ChannelKey
import dev.scanrelay.app.model.ConnectionStatus
import dev.scanrelay.app.model.FavoriteTagKey
import dev.scanrelay.app.model.RadioCall
import dev.scanrelay.app.model.ScannerAlert
import dev.scanrelay.app.model.ScannerState
import dev.scanrelay.app.model.ServerProfile
import dev.scanrelay.app.model.ServerScannerState
import dev.scanrelay.app.playback.ScannerService
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private enum class AppTab(val label: String, val glyph: String) {
    Scanner("Scanner", "●"),
    Channels("Channels", "≡"),
    History("History", "H"),
    Alerts("Alerts", "!"),
    Weather("Weather", "☼"),
    Transcripts("Transcripts", "T"),
    Settings("Settings", "S")
}

private const val COMPACT_UI_DENSITY_SCALE = 0.90f

@Composable
fun FatLineApp(viewModel: ScannerViewModel) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val scanner by viewModel.scannerState.collectAsStateWithLifecycle()
    val queuedCallCount by ScannerService.queuedCallCount.collectAsStateWithLifecycle()
    val currentlyPlayingCall by ScannerService.currentlyPlayingCall.collectAsStateWithLifecycle()
    var selectedProfileId by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(AppTab.Scanner) }
    val systemDensity = LocalDensity.current
    val compactDensity = remember(systemDensity) {
        Density(
            density = systemDensity.density * COMPACT_UI_DENSITY_SCALE,
            fontScale = systemDensity.fontScale
        )
    }

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

    val accent = selectedProfileId
        ?.let { scanner.servers[it] }
        ?.let { server -> resolvedUiAccentColor(server.uiAccentColor, server.userUiAccentColor) }
        ?.let(::uiAccentRgb)
    val colorScheme = if (accent != null) {
        val color = Color(accent.red, accent.green, accent.blue)
        darkColorScheme(primary = color, secondary = color, tertiary = color)
    } else {
        darkColorScheme()
    }

    CompositionLocalProvider(LocalDensity provides compactDensity) {
        MaterialTheme(colorScheme = colorScheme) {
            Surface(Modifier.fillMaxSize()) {
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            AppTab.entries.forEach { item ->
                                NavigationBarItem(
                                    selected = tab == item,
                                    onClick = { tab = item },
                                    icon = {
                                        if (item == AppTab.Scanner && tab != AppTab.Scanner && queuedCallCount > 0) {
                                            BadgedBox(
                                                badge = {
                                                    Badge {
                                                        Text(if (queuedCallCount > 99) "99+" else queuedCallCount.toString())
                                                    }
                                                }
                                            ) {
                                                Text(item.glyph)
                                            }
                                        } else {
                                            Text(item.glyph)
                                        }
                                    },
                                    label = {
                                        Text(
                                            item.label,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp)
                                        )
                                    }
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
                            queuedCallCount = queuedCallCount,
                            currentlyPlayingCall = currentlyPlayingCall,
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
                        AppTab.Alerts -> AlertsScreen(
                            scanner = scanner,
                            profiles = profiles,
                            selectedProfileId = selectedProfileId,
                            onSelectProfile = { selectedProfileId = it },
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize().padding(padding)
                        )
                        AppTab.Weather -> WeatherScreen(
                            modifier = Modifier.fillMaxSize().padding(padding)
                        )
                        AppTab.Transcripts -> TranscriptsScreen(
                            scanner = scanner,
                            profiles = profiles,
                            selectedProfileId = selectedProfileId,
                            onSelectProfile = { selectedProfileId = it },
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize().padding(padding)
                        )
                        AppTab.Settings -> SettingsScreen(
                            scanner = scanner,
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
}

@Composable
private fun ScannerScreen(
    scanner: ScannerState,
    profiles: List<ServerProfile>,
    selectedProfileId: String?,
    onSelectProfile: (String) -> Unit,
    queuedCallCount: Int,
    currentlyPlayingCall: RadioCall?,
    viewModel: ScannerViewModel,
    modifier: Modifier
) {
    val profile = profiles.firstOrNull { it.id == selectedProfileId }
    val server = selectedProfileId?.let { scanner.servers[it] }
    val playingCall = currentlyPlayingCall?.takeIf { it.profileId == server?.profile?.id }

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

        item {
            Card(Modifier.padding(horizontal = 16.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Playback queue", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (queuedCallCount == 1) "1 call queued" else "$queuedCallCount calls queued",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
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

            playingCall?.let { call ->
                item(key = "playing-" + server.profile.id + "-" + call.id) {
                    NowPlayingCard(server, call, viewModel)
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
                itemsIndexed(server.alerts.take(3), key = { index, alert -> "preview-alert-" + index + "-" + alert.stableKey }) { _, alert ->
                    AlertCard(
                        alert,
                        mappingEnabled = server.incidentMappingEnabled,
                        time12hFormat = server.time12hFormat
                    )
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
                    CallRow(
                        call,
                        time12hFormat = server.time12hFormat,
                        onReplay = { viewModel.replay(call.profileId, call.id) },
                        onDownload = { viewModel.downloadCall(call.profileId, call.id) }
                    )
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

            if (server.showListenersCount && server.status == ConnectionStatus.CONNECTED) {
                Text(
                    "Listeners " + server.listenerCount,
                    style = MaterialTheme.typography.bodySmall
                )
            }

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
            Text("NOW PLAYING", style = MaterialTheme.typography.labelLarge)
            Text(call.talkgroupLabel, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            InfoRow("System", call.systemLabel)
            InfoRow("TGID", call.talkgroupRef.toString())
            talkgroup?.tag?.takeIf { it.isNotBlank() }?.let { InfoRow("Tag", it) }
            call.sourceDisplay?.let { InfoRow(if (call.sources.size > 1) "Units" else "Unit", it) }
            call.frequency?.let { InfoRow("Frequency", formatFrequency(it)) }
            call.durationSeconds?.let { InfoRow("Duration", String.format(Locale.US, "%.1f s", it)) }
            if (call.dateTime.isNotBlank()) {
                InfoRow(
                    "Time",
                    formatServerDateTime(
                        call.dateTime,
                        time12hFormat = server.time12hFormat,
                        includeDate = false
                    )
                )
            }

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
    var newScanListName by remember(selectedProfileId) { mutableStateOf("") }
    var editingScanListId by remember(selectedProfileId) { mutableStateOf<String?>(null) }
    val editingScanList = server?.scanLists?.firstOrNull { it.id == editingScanListId }
    val normalizedQuery = channelQuery.trim().lowercase()
    val visibleSystems = server?.systems.orEmpty()
        .filterNot { it.systemRef in server?.hiddenSystemRefs.orEmpty() }
        .mapNotNull { system ->
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
    val favoriteKeys = server?.systems.orEmpty()
        .flatMap { it.talkgroups }
        .filter { it.favorite }
        .map { it.key }
        .toSet()

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
                    if (favoritesOnly) {
                        Button(
                            onClick = { viewModel.setChannels(server.profile.id, favoriteKeys, true) },
                            enabled = favoriteKeys.isNotEmpty()
                        ) { Text("Enable favorites") }
                        OutlinedButton(
                            onClick = { viewModel.setChannels(server.profile.id, favoriteKeys, false) },
                            enabled = favoriteKeys.isNotEmpty()
                        ) { Text("Disable favorites") }
                    } else {
                        Button(onClick = { viewModel.setAllTalkgroups(server.profile.id, true) }) { Text("Enable all") }
                        OutlinedButton(onClick = { viewModel.setAllTalkgroups(server.profile.id, false) }) { Text("Disable all") }
                    }
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

            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "Scan Lists",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newScanListName,
                            onValueChange = { newScanListName = it },
                            label = { Text("New list name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                viewModel.createScanList(server.profile.id, newScanListName)
                                newScanListName = ""
                            },
                            enabled = newScanListName.isNotBlank()
                        ) { Text("Create") }
                    }
                    if (server.scanListSyncing) {
                        Text("Saving Scan Lists…", style = MaterialTheme.typography.bodySmall)
                    }
                    server.scanListError?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    editingScanList?.let {
                        Text(
                            "Editing membership: " + it.name + " — use + List / ✓ List on channels below.",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            itemsIndexed(
                server.scanLists,
                key = { index, scanList -> "scan-list-" + server.profile.id + "-" + index + "-" + scanList.id }
            ) { scanListIndex, scanList ->
                val enabledKeys = server.systems
                    .flatMap { it.talkgroups }
                    .filter { it.enabled }
                    .map { it.key }
                    .toSet()
                val enabledCount = scanList.channels.count { it in enabledKeys }
                var editName by remember(scanList.id, scanList.name) { mutableStateOf(scanList.name) }
                val editing = editingScanListId == scanList.id

                Card(Modifier.padding(horizontal = 16.dp)) {
                    Column(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (editing) {
                            OutlinedTextField(
                                value = editName,
                                onValueChange = { editName = it },
                                label = { Text("List name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(scanList.name, fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            enabledCount.toString() + "/" + scanList.channels.size + " enabled · " +
                                scanList.channels.size + " members",
                            style = MaterialTheme.typography.bodySmall
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            item {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.reorderScanList(
                                            server.profile.id,
                                            scanListIndex,
                                            scanListIndex - 1
                                        )
                                    },
                                    enabled = scanListIndex > 0 && !server.scanListSyncing
                                ) { Text("↑ Move") }
                            }
                            item {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.reorderScanList(
                                            server.profile.id,
                                            scanListIndex,
                                            scanListIndex + 1
                                        )
                                    },
                                    enabled = scanListIndex < server.scanLists.lastIndex && !server.scanListSyncing
                                ) { Text("↓ Move") }
                            }
                            item {
                                OutlinedButton(
                                    onClick = { viewModel.setChannels(server.profile.id, scanList.channels, true) },
                                    enabled = scanList.channels.isNotEmpty()
                                ) { Text("Enable") }
                            }
                            item {
                                OutlinedButton(
                                    onClick = { viewModel.setChannels(server.profile.id, scanList.channels, false) },
                                    enabled = scanList.channels.isNotEmpty()
                                ) { Text("Disable") }
                            }
                            item {
                                OutlinedButton(
                                    onClick = {
                                        editingScanListId = if (editing) null else scanList.id
                                    }
                                ) { Text(if (editing) "Done" else "Edit members") }
                            }
                            if (editing) {
                                item {
                                    OutlinedButton(
                                        onClick = {
                                            viewModel.renameScanList(server.profile.id, scanList.id, editName)
                                        },
                                        enabled = editName.isNotBlank() && editName.trim() != scanList.name
                                    ) { Text("Save name") }
                                }
                                item {
                                    OutlinedButton(
                                        onClick = {
                                            viewModel.deleteScanList(server.profile.id, scanList.id)
                                            editingScanListId = null
                                        }
                                    ) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
            }
            item { HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }

            if (server.hiddenSystemRefs.isNotEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Hidden systems", fontWeight = FontWeight.SemiBold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(
                                server.systems.filter { it.systemRef in server.hiddenSystemRefs },
                                key = { "hidden-system-" + it.systemRef }
                            ) { hiddenSystem ->
                                OutlinedButton(
                                    onClick = {
                                        viewModel.setSystemHidden(
                                            server.profile.id,
                                            hiddenSystem.systemRef,
                                            false
                                        )
                                    }
                                ) { Text("Show " + hiddenSystem.label) }
                            }
                        }
                    }
                }
            }

            if (visibleSystems.isEmpty()) {
                item {
                    Text("No channels match the current filter.", modifier = Modifier.padding(16.dp))
                }
            }

            visibleSystems.forEachIndexed { systemIndex, system ->
                val fullSystem = server.systems.firstOrNull { it.systemRef == system.systemRef } ?: system
                val systemFavorite = system.systemRef in server.favoriteSystemRefs
                item(key = "system-" + server.profile.id + "-" + systemIndex + "-" + system.systemRef) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            system.label,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            system.talkgroups.count { it.enabled }.toString() + "/" + system.talkgroups.size + " enabled",
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall
                        )
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            item {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.setSystemFavorite(
                                            server.profile.id,
                                            system.systemRef,
                                            !systemFavorite
                                        )
                                    },
                                    enabled = fullSystem.talkgroups.isNotEmpty()
                                ) { Text(if (systemFavorite) "★" else "☆") }
                            }
                            item {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.setSystemHold(
                                            server.profile.id,
                                            if (server.holdSystemRef == system.systemRef) null else system.systemRef
                                        )
                                    }
                                ) { Text(if (server.holdSystemRef == system.systemRef) "Held" else "Hold") }
                            }
                            item {
                                OutlinedButton(
                                    onClick = { viewModel.setSystemTalkgroups(server.profile.id, system.systemRef, true) }
                                ) { Text("All") }
                            }
                            item {
                                OutlinedButton(
                                    onClick = { viewModel.setSystemTalkgroups(server.profile.id, system.systemRef, false) }
                                ) { Text("None") }
                            }
                            item {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.setSystemHidden(
                                            server.profile.id,
                                            system.systemRef,
                                            true
                                        )
                                    }
                                ) { Text("Hide…23560 tokens truncated…wordResetAndConnect(
                                    ServerProfile(editingId, name, url, pin),
                                    username,
                                    password,
                                    forcedNewPassword
                                )
                                onSelectProfile(editingId)
                            },
                            enabled = username.isNotBlank() &&
                                password.isNotBlank() &&
                                AccountLoginPolicy.passwordValidationError(forcedNewPassword) == null &&
                                forcedNewPassword == forcedConfirmPassword &&
                                !loginState.working
                        ) {
                            Text(if (loginState.working) "Updating…" else "Update password & connect")
                        }
                    }

                    OutlinedButton(onClick = { showPasswordRecovery = !showPasswordRecovery }) {
                        Text(if (showPasswordRecovery) "Hide password recovery" else "Forgot password?")
                    }
                    if (showPasswordRecovery) {
                        Text(
                            "Request a reset code, then enter the code and a new password.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            recoveryEmail,
                            { recoveryEmail = it },
                            label = { Text("Account email") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedButton(
                            onClick = { viewModel.requestPasswordReset(url, recoveryEmail) },
                            enabled = url.isNotBlank() && recoveryEmail.isNotBlank() && !passwordRecovery.working
                        ) {
                            Text(if (passwordRecovery.working) "Working…" else "Send reset code")
                        }
                        OutlinedTextField(
                            recoveryCode,
                            { recoveryCode = it },
                            label = { Text("Reset code") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            recoveryNewPassword,
                            { recoveryNewPassword = it },
                            label = { Text("New password") },
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                viewModel.resetAccountPassword(
                                    url,
                                    recoveryEmail,
                                    recoveryCode,
                                    recoveryNewPassword
                                )
                            },
                            enabled = url.isNotBlank() &&
                                recoveryEmail.isNotBlank() &&
                                recoveryCode.isNotBlank() &&
                                recoveryNewPassword.isNotBlank() &&
                                !passwordRecovery.working
                        ) {
                            Text(if (passwordRecovery.working) "Resetting…" else "Reset password")
                        }
                        passwordRecovery.message?.takeIf(String::isNotBlank)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        passwordRecovery.error?.takeIf(String::isNotBlank)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    if (editing != null && editing.pin.isNotBlank()) {
                        HorizontalDivider()
                        Text(
                            "Account details",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        OutlinedButton(
                            onClick = { viewModel.refreshAccountProfile(editing) },
                            enabled = accountProfileState?.loading != true
                        ) {
                            Text(if (accountProfileState?.loading == true) "Refreshing…" else "Refresh account details")
                        }
                        accountProfileState?.error?.takeIf(String::isNotBlank)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        accountProfileState?.account?.let { account ->
                            account.displayName.takeIf(String::isNotBlank)?.let {
                                Text(it, fontWeight = FontWeight.Medium)
                            }
                            account.email.takeIf(String::isNotBlank)?.let { Text(it) }
                            if (account.groupName.isNotBlank()) Text("Group: ${account.groupName}")
                            Text("Email verified: ${if (account.verified) "Yes" else "No"}")
                            Text(
                                "Subscription: " + account.subscriptionStatus.ifBlank { "not reported" }
                            )
                            Text("Billing required: ${if (account.billingRequired) "Yes" else "No"}")
                            if (account.isGroupAdmin) Text("Group administrator")
                            Text("Listener PIN expired: ${if (account.pinExpired) "Yes" else "No"}")
                        }

                        HorizontalDivider()
                        Text(
                            "Change account email",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Verify your current email, then request a confirmation link for the new address.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        accountEmailChange.pendingEmail?.let { pendingEmail ->
                            Text(
                                "Confirmation link sent to $pendingEmail. Open that link, then refresh account details.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        LaunchedEffect(accountEmailChange.pendingEmail) {
                            if (accountEmailChange.pendingEmail != null) {
                                emailChangeCode = ""
                                emailChangeNewAddress = ""
                                emailChangePassword = ""
                            }
                        }
                        if (!accountEmailChange.verified) {
                            OutlinedButton(
                                onClick = { viewModel.requestAccountEmailChangeCode(url, editing.pin) },
                                enabled = url.isNotBlank() && !accountEmailChange.working
                            ) {
                                Text(if (accountEmailChange.working) "Working…" else "Send current-email code")
                            }
                            if (accountEmailChange.codeSent || emailChangeCode.isNotBlank()) {
                                OutlinedTextField(
                                    emailChangeCode,
                                    { emailChangeCode = it },
                                    label = { Text("Current-email verification code") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Button(
                                    onClick = {
                                        viewModel.verifyAccountEmailChangeCode(url, editing.pin, emailChangeCode)
                                    },
                                    enabled = emailChangeCode.isNotBlank() && !accountEmailChange.working
                                ) {
                                    Text(if (accountEmailChange.working) "Verifying…" else "Verify current email")
                                }
                            }
                        } else {
                            Text(
                                "Current email verified. Enter the new address and your account password.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            OutlinedTextField(
                                emailChangeNewAddress,
                                { emailChangeNewAddress = it },
                                label = { Text("New email address") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                emailChangePassword,
                                { emailChangePassword = it },
                                label = { Text("Account password") },
                                modifier = Modifier.fillMaxWidth(),
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true
                            )
                            Button(
                                onClick = {
                                    viewModel.changeAccountEmail(
                                        url,
                                        editing.pin,
                                        emailChangeCode,
                                        emailChangeNewAddress,
                                        emailChangePassword
                                    )
                                },
                                enabled = emailChangeNewAddress.isNotBlank() &&
                                    emailChangePassword.isNotBlank() &&
                                    !accountEmailChange.working
                            ) {
                                Text(if (accountEmailChange.working) "Changing…" else "Change email")
                            }
                        }
                        accountEmailChange.message?.takeIf(String::isNotBlank)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        accountEmailChange.error?.takeIf(String::isNotBlank)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }

                        HorizontalDivider()
                        Text(
                            "Change account password",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Send a verification code to the account email, then enter it with your new password.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(
                            onClick = { viewModel.requestAccountPasswordChangeCode(url, editing.pin) },
                            enabled = url.isNotBlank() && !accountPasswordChange.working
                        ) {
                            Text(if (accountPasswordChange.working) "Working…" else "Send verification code")
                        }
                        OutlinedTextField(
                            accountPasswordCode,
                            { accountPasswordCode = it },
                            label = { Text("Verification code") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            accountNewPassword,
                            { accountNewPassword = it },
                            label = { Text("New account password") },
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                viewModel.changeAccountPassword(
                                    url,
                                    editing.pin,
                                    accountPasswordCode,
                                    accountNewPassword
                                )
                            },
                            enabled = url.isNotBlank() &&
                                accountPasswordCode.isNotBlank() &&
                                accountNewPassword.isNotBlank() &&
                                !accountPasswordChange.working
                        ) {
                            Text(if (accountPasswordChange.working) "Updating…" else "Update account password")
                        }
                        accountPasswordChange.message?.takeIf(String::isNotBlank)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        accountPasswordChange.error?.takeIf(String::isNotBlank)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
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

                    HorizontalDivider()
                    Text("Notifications", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Alert sound: " + alertSoundLabel, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val setting = AlertSoundPreferences.get(context, editingId)
                            val existing = when (setting) {
                                null -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                                AlertSoundPreferences.SILENT -> null
                                else -> runCatching { Uri.parse(setting) }.getOrNull()
                            }
                            val picker = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "FatLine alert sound")
                            }
                            alertSoundPicker.launch(picker)
                        }) { Text("Choose sound") }

                        OutlinedButton(onClick = {
                            AlertSoundPreferences.useSystemDefault(context, editingId)
                            alertSoundLabel = AlertSoundPreferences.displayName(context, editingId)
                        }) { Text("System default") }
                    }

                    Text(
                        "Severe weather sound: " + weatherSoundLabel,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Uses the ZIP code on your scanner account. Warnings already active at first check appear silently.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = weatherSoundEnabled,
                            onCheckedChange = {
                                weatherSoundEnabled = it
                                AlertSoundPreferences.setWeatherSoundEnabled(context, editingId, it)
                            }
                        )
                        Text("Play sound for new Severe or Extreme weather warnings")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val setting = AlertSoundPreferences.getWeather(context, editingId)
                            val existing = when (setting) {
                                null -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                                AlertSoundPreferences.SILENT -> null
                                else -> runCatching { Uri.parse(setting) }.getOrNull()
                            }
                            val picker = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "FatLine severe weather sound")
                            }
                            weatherSoundPicker.launch(picker)
                        }) { Text("Choose weather sound") }

                        OutlinedButton(onClick = {
                            AlertSoundPreferences.setWeather(context, editingId, null)
                            weatherSoundLabel = AlertSoundPreferences.displayWeatherName(context, editingId)
                        }) { Text("System default") }
                    }

                    Text("Disconnect sound: " + disconnectSoundLabel, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val setting = AlertSoundPreferences.getDisconnect(context, editingId)
                            val existing = when (setting) {
                                null -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                                AlertSoundPreferences.SILENT -> null
                                else -> runCatching { Uri.parse(setting) }.getOrNull()
                            }
                            val picker = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "FatLine disconnect sound")
                            }
                            disconnectSoundPicker.launch(picker)
                        }) { Text("Choose") }

                        OutlinedButton(onClick = {
                            AlertSoundPreferences.useSystemDefaultDisconnect(context, editingId)
                            disconnectSoundLabel = AlertSoundPreferences.displayDisconnectName(context, editingId)
                        }) { Text("System default") }
                    }

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
                                AlertSoundPreferences.clear(context, editingId)
                                editingId = UUID.randomUUID().toString()
                                name = "Scanner"
                                url = ""
                                username = ""
                                password = ""
                                pin = ""
                                viewModel.clearAccountLoginStatus()
                        viewModel.clearPasswordRecoveryStatus()
                        viewModel.clearAccountPasswordChangeStatus()
                        viewModel.clearAccountEmailChangeStatus()
                            }) { Text("Delete") }
                        }
                    }
                }
            }
        }

        connectedServer?.let { server ->
            item {
                Card(Modifier.padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Live feed",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        OutlinedTextField(
                            value = backlogMinutesText,
                            onValueChange = { value ->
                                if (value.isEmpty() || value.all(Char::isDigit)) {
                                    backlogMinutesText = value
                                }
                            },
                            label = { Text("Backlog (minutes)") },
                            singleLine = true,
                            enabled = !server.userSettingsSaving,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            if (server.livefeedBacklogMinutes == 0) {
                                "0 means live audio only."
                            } else {
                                "Current server setting: " + server.livefeedBacklogMinutes + " minute(s)."
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "The server applies backlog on a fresh live-feed start. Pause, then Resume after saving to apply it to the current connection.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(
                            onClick = {
                                backlogMinutesText.toIntOrNull()?.let { minutes ->
                                    viewModel.setLivefeedBacklogMinutes(server.profile.id, minutes)
                                }
                            },
                            enabled = backlogMinutesText.toIntOrNull() != null &&
                                server.profile.pin.isNotBlank() &&
                                !server.userSettingsSaving
                        ) {
                            Text(if (server.userSettingsSaving) "Saving…" else "Save backlog")
                        }
                        server.userSettingsError?.takeIf { it.isNotBlank() }?.let { error ->
                            Text(error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            val availableTags = server.systems
                .flatMap { it.talkgroups }
                .map { it.tag.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
                .sortedBy { it.lowercase() }

            if (availableTags.isNotEmpty()) {
                item {
                    Card(Modifier.padding(horizontal = 16.dp)) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                "Tag colors",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Synced with ThinLine user settings.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            availableTags.forEach { tag ->
                                val rgb = TagColors.rgb(tag, server.tagColors)
                                val custom = TagColors.customColor(tag, server.tagColors)
                                val customLabel = custom?.let { hex ->
                                    TagColors.choices.firstOrNull {
                                        it.hex.equals(hex, ignoreCase = true)
                                    }?.label ?: hex
                                } ?: "Default"
                                var menuExpanded by remember(
                                    server.profile.id,
                                    tag,
                                    custom
                                ) { mutableStateOf(false) }

                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        tag,
                                        color = Color(rgb.red, rgb.green, rgb.blue),
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Column {
                                        OutlinedButton(
                                            onClick = { menuExpanded = true },
                                            enabled = server.profile.pin.isNotBlank() &&
                                                !server.userSettingsSaving
                                        ) { Text(customLabel) }
                                        DropdownMenu(
                                            expanded = menuExpanded,
                                            onDismissRequest = { menuExpanded = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Default") },
                                                onClick = {
                                                    menuExpanded = false
                                                    viewModel.setTagColor(
                                                        server.profile.id,
                                                        tag,
                                                        null
                                                    )
                                                }
                                            )
                                            TagColors.choices.forEach { choice ->
                                                val choiceRgb = TagColors.rgb(
                                                    tag,
                                                    mapOf(tag.lowercase() to choice.hex)
                                                )
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            choice.label,
                                                            color = Color(
                                                                choiceRgb.red,
                                                                choiceRgb.green,
                                                                choiceRgb.blue
                                                            )
                                                        )
                                                    },
                                                    onClick = {
                                                        menuExpanded = false
                                                        viewModel.setTagColor(
                                                            server.profile.id,
                                                            tag,
                                                            choice.hex
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
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

private fun archiveDateTimeIso(dateText: String, timeText: String): String? {
    val date = dateText.trim()
    val time = timeText.trim()
    if (date.isBlank()) return null
    return runCatching {
        val localDate = LocalDate.parse(date)
        val localTime = if (time.isBlank()) LocalTime.MIDNIGHT else LocalTime.parse(time)
        localDate.atTime(localTime)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toString()
    }.getOrNull()
}

private fun transcriptDateBoundary(value: String, endOfDay: Boolean): Long? {
    val text = value.trim()
    if (text.isBlank()) return null
    return runCatching {
        val date = LocalDate.parse(text)
        val time = if (endOfDay) LocalTime.of(23, 59, 59) else LocalTime.MIDNIGHT
        date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()
}

private fun formatTranscriptTimestamp(timestamp: Long, time12hFormat: Boolean): String {
    val pattern = if (time12hFormat) "MMM d, yyyy h:mm a" else "MMM d, yyyy HH:mm"
    return runCatching {
        Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern(pattern))
    }.getOrDefault(timestamp.toString())
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
private fun CallRow(
    call: RadioCall,
    time12hFormat: Boolean,
    onReplay: () -> Unit,
    onDownload: () -> Unit,
    onContinue: (() -> Unit)? = null
) {
    Card(Modifier.padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(call.talkgroupLabel, fontWeight = FontWeight.SemiBold)
                Text(call.systemLabel + " · TG " + call.talkgroupRef, style = MaterialTheme.typography.bodySmall)
                call.sourceDisplay?.let { Text("Unit: " + it, style = MaterialTheme.typography.bodySmall) }
                if (call.dateTime.isNotBlank()) {
                    Text(
                        formatServerDateTime(call.dateTime, time12hFormat),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                call.transcript?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                onContinue?.let {
                    OutlinedButton(onClick = it) { Text("Continue") }
                }
                OutlinedButton(onClick = onReplay) { Text("Replay") }
                OutlinedButton(onClick = onDownload) { Text("Download") }
            }
        }
    }
}

@Composable
private fun AlertCard(
    alert: ScannerAlert,
    mappingEnabled: Boolean,
    time12hFormat: Boolean,
    onReplay: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val mapUri = incidentMapUri(alert, mappingEnabled)
    Card(Modifier.padding(horizontal = 16.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(alert.title, fontWeight = FontWeight.SemiBold)
            Text(alert.body)

            alert.systemLabel?.takeIf { it.isNotBlank() }?.let { system ->
                val talkgroup = alert.talkgroupLabel ?: alert.talkgroupName
                Text(
                    if (talkgroup.isNullOrBlank()) system else system + " · " + talkgroup,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            alert.matchedToneSets.takeIf { it.isNotEmpty() }?.let {
                Text("Tone: " + it.joinToString(", "), style = MaterialTheme.typography.bodySmall)
            }
            alert.keywords.takeIf { it.isNotEmpty() }?.let {
                Text("Keywords: " + it.joinToString(", "), style = MaterialTheme.typography.bodySmall)
            }
            alert.incidentNature?.takeIf { it.isNotBlank() && it != alert.body }?.let {
                Text("Incident: " + it, style = MaterialTheme.typography.bodySmall)
            }
            alert.incidentAddress?.takeIf { it.isNotBlank() }?.let {
                Text("Address: " + it, style = MaterialTheme.typography.bodySmall)
            }
            if (alert.incidentLat != null && alert.incidentLon != null) {
                Text(
                    String.format(Locale.US, "%.5f, %.5f", alert.incidentLat, alert.incidentLon),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            alert.transcript?.takeIf { it.isNotBlank() && it != alert.body }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            alert.dateTime?.takeIf { it.isNotBlank() }?.let {
                Text(
                    formatServerDateTime(it, time12hFormat),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (onReplay != null || mapUri != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onReplay?.let {
                        OutlinedButton(onClick = it) { Text("Replay call") }
                    }
                    mapUri?.let { uri ->
                        OutlinedButton(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                            if (intent.resolveActivity(context.packageManager) != null) {
                                context.startActivity(intent)
                            }
                        }) { Text("Open map") }
                    }
                }
            }
        }
    }
}

internal fun incidentMapUri(alert: ScannerAlert, mappingEnabled: Boolean = true): String? {
    if (!mappingEnabled) return null
    val lat = alert.incidentLat
    val lon = alert.incidentLon
    if (lat != null && lon != null) {
        return String.format(
            Locale.US,
            "geo:%.6f,%.6f?q=%.6f,%.6f",
            lat,
            lon,
            lat,
            lon
        )
    }

    val address = alert.incidentAddress?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return "geo:0,0?q=" + URLEncoder.encode(address, "UTF-8").replace("+", "%20")
}

private fun formatFrequency(frequency: Long): String =
    if (frequency >= 1_000_000L) {
        String.format(Locale.US, "%.5f MHz", frequency / 1_000_000.0)
    } else {
        frequency.toString()
    }
