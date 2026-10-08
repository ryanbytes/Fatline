package dev.scanrelay.app.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
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
import dev.scanrelay.app.playback.QueuedCall
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
    val queuedCalls by ScannerService.queuedCalls.collectAsStateWithLifecycle()
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
                            queuedCalls = queuedCalls,
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
                            currentlyPlayingCall = currentlyPlayingCall,
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
    queuedCalls: List<QueuedCall>,
    currentlyPlayingCall: RadioCall?,
    viewModel: ScannerViewModel,
    modifier: Modifier
) {
    val profile = profiles.firstOrNull { it.id == selectedProfileId }
    val server = selectedProfileId?.let { scanner.servers[it] }
    val playingCall = currentlyPlayingCall?.takeIf { it.profileId == server?.profile?.id }
    var queueExpanded by remember { mutableStateOf(false) }

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
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Playback queue", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (queuedCallCount == 1) "1 call queued" else "$queuedCallCount calls queued",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (queuedCalls.isNotEmpty()) {
                            OutlinedButton(onClick = { queueExpanded = !queueExpanded }) {
                                Text(if (queueExpanded) "Hide queue" else "Show queue")
                            }
                        }
                    }
                    if (queueExpanded && queuedCalls.isNotEmpty()) {
                        HorizontalDivider()
                        queuedCalls.take(5).forEachIndexed { index, entry ->
                            Column {
                                Text(
                                    "${index + 1}. ${entry.call.talkgroupLabel}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "${if (entry.liveFeed) "Live" else "Replay"} · ${entry.call.serverName} · ${entry.call.systemLabel}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        if (queuedCalls.size > 5) {
                            Text(
                                "+ ${queuedCalls.size - 5} more calls",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
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
                    RecentLiveCallRow(
                        call = call,
                        time12hFormat = server.time12hFormat,
                        onPlay = { viewModel.replay(call.profileId, call.id) }
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
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "SYSTEM",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
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
                                    ) { Text("Hide") }
                                }
                            }
                        }
                    }
                }

                groupTalkgroupsByTag(system.talkgroups).forEachIndexed { tagIndex, tagGroup ->
                    val tagFavorite =
                        FavoriteTagKey(system.systemRef, tagGroup.tag) in server.favoriteTags
                    val tagRgb = TagColors.rgb(tagGroup.tag, server.tagColors)
                    item(key = "tag-" + server.profile.id + "-" + systemIndex + "-" + tagIndex + "-" + tagGroup.tag) {
                        Column(
                            Modifier.fillMaxWidth().padding(start = 28.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                tagGroup.tag,
                                modifier = Modifier.fillMaxWidth(),
                                fontWeight = FontWeight.SemiBold,
                                color = Color(tagRgb.red, tagRgb.green, tagRgb.blue),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                tagGroup.talkgroups.count { it.enabled }.toString() + "/" +
                                    tagGroup.talkgroups.size + " enabled",
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
                                            viewModel.setTagFavorite(
                                                server.profile.id,
                                                system.systemRef,
                                                tagGroup.tag,
                                                !tagFavorite
                                            )
                                        },
                                        enabled = tagGroup.talkgroups.isNotEmpty()
                                    ) { Text(if (tagFavorite) "★" else "☆") }
                                }
                                item {
                                    OutlinedButton(
                                        onClick = {
                                            viewModel.setChannels(
                                                server.profile.id,
                                                tagGroup.talkgroups.map { it.key },
                                                true
                                            )
                                        }
                                    ) { Text("All") }
                                }
                                item {
                                    OutlinedButton(
                                        onClick = {
                                            viewModel.setChannels(
                                                server.profile.id,
                                                tagGroup.talkgroups.map { it.key },
                                                false
                                            )
                                        }
                                    ) { Text("None") }
                                }
                            }
                        }
                    }

                    itemsIndexed(
                        tagGroup.talkgroups,
                        key = { talkgroupIndex, tg ->
                            "tg-" + server.profile.id + "-" + systemIndex + "-" + tagIndex + "-" +
                                talkgroupIndex + "-" + tg.systemRef + "-" + tg.talkgroupRef
                        }
                    ) { _, tg ->
                        Card(Modifier.fillMaxWidth().padding(start = 32.dp, end = 16.dp, top = 3.dp, bottom = 3.dp)) {
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    "CHANNEL",
                                    modifier = Modifier.padding(start = 12.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontWeight = FontWeight.Bold
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = tg.enabled,
                                        onCheckedChange = { enabled ->
                                            viewModel.setTalkgroup(server.profile.id, tg.systemRef, tg.talkgroupRef, enabled)
                                        }
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            tg.displayName,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "TG " + tg.talkgroupRef +
                                                if (tg.tag.isBlank()) "" else " · " + tg.tag,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                LazyRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    item {
                                        OutlinedButton(
                                            onClick = {
                                                viewModel.setFavorite(
                                                    server.profile.id,
                                                    tg.systemRef,
                                                    tg.talkgroupRef,
                                                    !tg.favorite
                                                )
                                            }
                                        ) { Text(if (tg.favorite) "★" else "☆") }
                                    }
                                    editingScanList?.let { list ->
                                        item {
                                            val inList = tg.key in list.channels
                                            OutlinedButton(
                                                onClick = {
                                                    viewModel.setScanListChannel(
                                                        server.profile.id,
                                                        list.id,
                                                        tg.key,
                                                        !inList
                                                    )
                                                }
                                            ) { Text(if (inList) "✓ List" else "+ List") }
                                        }
                                    }
                                    item {
                                        OutlinedButton(onClick = {
                                            if (server.hold == tg.key) viewModel.clearHold(server.profile.id)
                                            else viewModel.setHold(server.profile.id, tg.systemRef, tg.talkgroupRef)
                                        }) { Text(if (server.hold == tg.key) "Held" else "Hold") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

private data class ArchiveMenuChoice(
    val key: String,
    val label: String
)

@Composable
private fun ArchiveMenuButton(
    title: String,
    selectedKey: String,
    choices: List<ArchiveMenuChoice>,
    enabled: Boolean = true,
    onSelect: (String) -> Unit
) {
    var expanded by remember(title, selectedKey) { mutableStateOf(false) }
    val selectedLabel = choices.firstOrNull { it.key == selectedKey }?.label
        ?: choices.firstOrNull()?.label
        ?: "All"

    Column {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled && choices.isNotEmpty()
        ) {
            Text("$title: $selectedLabel")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.label) },
                    onClick = {
                        expanded = false
                        onSelect(choice.key)
                    }
                )
            }
        }
    }
}

@Composable
private fun HistoryScreen(
    scanner: ScannerState,
    profiles: List<ServerProfile>,
    selectedProfileId: String?,
    onSelectProfile: (String) -> Unit,
    currentlyPlayingCall: RadioCall?,
    viewModel: ScannerViewModel,
    modifier: Modifier
) {
    val server = selectedProfileId?.let { scanner.servers[it] }
    var historyQuery by remember(selectedProfileId) { mutableStateOf("") }
    var archiveSystemRef by remember(selectedProfileId, server?.historySystemRef) {
        mutableStateOf(server?.historySystemRef)
    }
    var archiveTalkgroupRef by remember(selectedProfileId, server?.historyTalkgroupRef) {
        mutableStateOf(server?.historyTalkgroupRef)
    }
    var archiveGroup by remember(selectedProfileId, server?.historyGroup) {
        mutableStateOf(server?.historyGroup)
    }
    var archiveTag by remember(selectedProfileId, server?.historyTag) {
        mutableStateOf(server?.historyTag)
    }
    var archiveDate by remember(selectedProfileId, server?.historyDate) {
        mutableStateOf(
            server?.historyDate
                ?.let { runCatching { Instant.parse(it).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull() }
                ?.toString()
                .orEmpty()
        )
    }
    var archiveTime by remember(selectedProfileId, server?.historyDate) {
        mutableStateOf(
            server?.historyDate
                ?.let { runCatching { Instant.parse(it).atZone(ZoneId.systemDefault()).toLocalTime() }.getOrNull() }
                ?.format(DateTimeFormatter.ofPattern("HH:mm"))
                .orEmpty()
        )
    }
    var archiveSort by remember(selectedProfileId, server?.historySort) {
        mutableStateOf(server?.historySort ?: -1)
    }

    val archiveDateIso = archiveDateTimeIso(archiveDate, archiveTime)
    val archiveDateValid =
        (archiveDate.isBlank() && archiveTime.isBlank()) ||
            (archiveDate.isNotBlank() && archiveDateIso != null)

    val allSystems = server?.systems.orEmpty()
    val archiveGroups = allSystems
        .flatMap { it.talkgroups }
        .flatMap { it.groups }
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy { it.lowercase() }
        .sortedBy { it.lowercase() }
    val archiveTags = allSystems
        .flatMap { it.talkgroups }
        .map { it.tag.trim() }
        .filter(String::isNotBlank)
        .distinctBy { it.lowercase() }
        .sortedBy { it.lowercase() }
    val filteredSystems = allSystems.filter { system ->
        val groupMatches = archiveGroup == null ||
            system.talkgroups.any { talkgroup ->
                talkgroup.groups.any { it.equals(archiveGroup, ignoreCase = true) }
            }
        val tagMatches = archiveTag == null ||
            system.talkgroups.any { it.tag.equals(archiveTag, ignoreCase = true) }
        groupMatches && tagMatches
    }
    val selectedArchiveSystem = allSystems.firstOrNull { it.systemRef == archiveSystemRef }
    val filteredTalkgroups = selectedArchiveSystem?.talkgroups.orEmpty().filter { talkgroup ->
        val groupMatches = archiveGroup == null ||
            talkgroup.groups.any { it.equals(archiveGroup, ignoreCase = true) }
        val tagMatches = archiveTag == null ||
            talkgroup.tag.equals(archiveTag, ignoreCase = true)
        groupMatches && tagMatches
    }
    val favoriteArchiveChannels = allSystems.flatMap { system ->
        system.talkgroups.filter { it.favorite }.map { talkgroup -> system to talkgroup }
    }

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
    val playingCall = currentlyPlayingCall?.takeIf { it.profileId == server?.profile?.id }
    val archiveFilterLabel = server?.let { current ->
        val parts = buildList {
            current.historyGroup?.let { add("group $it") }
            current.historyTag?.let { add("tag $it") }
            current.historySystemRef?.let { systemRef ->
                val system = current.systems.firstOrNull { it.systemRef == systemRef }
                add(system?.label ?: "system $systemRef")
            }
            current.historyTalkgroupRef?.let { tgRef ->
                val system = current.systems.firstOrNull { it.systemRef == current.historySystemRef }
                val talkgroup = system?.talkgroups?.firstOrNull { it.talkgroupRef == tgRef }
                add(talkgroup?.displayName ?: "TG $tgRef")
            }
            current.historyDate?.let { add("from $it") }
            if (current.historySort > 0) add("oldest first")
        }
        if (parts.isEmpty()) null else "Server filter: " + parts.joinToString(" · ")
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
                            onClick = {
                                archiveSystemRef = null
                                archiveTalkgroupRef = null
                                archiveGroup = null
                                archiveTag = null
                                archiveDate = ""
                                archiveTime = ""
                                archiveSort = -1
                                viewModel.requestHistory(server.profile.id, true)
                            },
                            enabled = server.status == ConnectionStatus.CONNECTED
                        ) { Text(if (archiveFilterLabel == null) "Refresh archive" else "All archive") }
                    }
                    playingCall?.let { call ->
                        item {
                            OutlinedButton(
                                onClick = {
                                    archiveSystemRef = call.systemRef
                                    archiveTalkgroupRef = call.talkgroupRef
                                    archiveGroup = null
                                    archiveTag = null
                                    archiveDate = ""
                                    archiveTime = ""
                                    archiveSort = -1
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
                                    archiveSystemRef = call.systemRef
                                    archiveTalkgroupRef = null
                                    archiveGroup = null
                                    archiveTag = null
                                    archiveDate = ""
                                    archiveTime = ""
                                    archiveSort = -1
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
                Card(Modifier.padding(horizontal = 16.dp)) {
                    Column(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Archive search",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            item {
                                ArchiveMenuButton(
                                    title = "Group",
                                    selectedKey = archiveGroup.orEmpty(),
                                    choices = listOf(ArchiveMenuChoice("", "All groups")) +
                                        archiveGroups.map { ArchiveMenuChoice(it, it) },
                                    onSelect = { key ->
                                        archiveGroup = key.takeIf { it.isNotBlank() }
                                        archiveSystemRef = null
                                        archiveTalkgroupRef = null
                                    }
                                )
                            }
                            item {
                                ArchiveMenuButton(
                                    title = "Tag",
                                    selectedKey = archiveTag.orEmpty(),
                                    choices = listOf(ArchiveMenuChoice("", "All tags")) +
                                        archiveTags.map { ArchiveMenuChoice(it, it) },
                                    onSelect = { key ->
                                        archiveTag = key.takeIf { it.isNotBlank() }
                                        archiveSystemRef = null
                                        archiveTalkgroupRef = null
                                    }
                                )
                            }
                            item {
                                ArchiveMenuButton(
                                    title = "System",
                                    selectedKey = archiveSystemRef?.toString().orEmpty(),
                                    choices = listOf(ArchiveMenuChoice("", "All systems")) +
                                        filteredSystems.map {
                                            ArchiveMenuChoice(it.systemRef.toString(), it.label)
                                        },
                                    onSelect = { key ->
                                        archiveSystemRef = key.toLongOrNull()
                                        archiveTalkgroupRef = null
                                    }
                                )
                            }
                            item {
                                ArchiveMenuButton(
                                    title = "Talkgroup",
                                    selectedKey = archiveTalkgroupRef?.toString().orEmpty(),
                                    choices = listOf(ArchiveMenuChoice("", "All talkgroups")) +
                                        filteredTalkgroups.map {
                                            ArchiveMenuChoice(
                                                it.talkgroupRef.toString(),
                                                it.displayName
                                            )
                                        },
                                    enabled = archiveSystemRef != null,
                                    onSelect = { key ->
                                        archiveTalkgroupRef = key.toLongOrNull()
                                    }
                                )
                            }
                            if (favoriteArchiveChannels.isNotEmpty()) {
                                item {
                                    ArchiveMenuButton(
                                        title = "Favorite",
                                        selectedKey = "",
                                        choices = listOf(ArchiveMenuChoice("", "Choose favorite")) +
                                            favoriteArchiveChannels.map { (system, talkgroup) ->
                                                ArchiveMenuChoice(
                                                    system.systemRef.toString() + ":" +
                                                        talkgroup.talkgroupRef,
                                                    system.label + " · " + talkgroup.displayName
                                                )
                                            },
                                        onSelect = { key ->
                                            val refs = key.split(":", limit = 2)
                                            if (refs.size == 2) {
                                                archiveSystemRef = refs[0].toLongOrNull()
                                                archiveTalkgroupRef = refs[1].toLongOrNull()
                                            }
                                        }
                                    )
                                }
                            }
                            item {
                                ArchiveMenuButton(
                                    title = "Sort",
                                    selectedKey = archiveSort.toString(),
                                    choices = listOf(
                                        ArchiveMenuChoice("-1", "Newest"),
                                        ArchiveMenuChoice("1", "Oldest")
                                    ),
                                    onSelect = { key ->
                                        archiveSort = key.toIntOrNull()?.let { if (it < 0) -1 else 1 } ?: -1
                                    }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = archiveDate,
                            onValueChange = { archiveDate = it },
                            label = { Text("From date (YYYY-MM-DD)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = archiveTime,
                            onValueChange = { archiveTime = it },
                            label = { Text("From time (HH:MM, optional)") },
                            singleLine = true,
                            enabled = archiveDate.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (!archiveDateValid) {
                            Text(
                                "Enter a valid date and optional 24-hour time.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    viewModel.requestHistoryFiltered(
                                        profileId = server.profile.id,
                                        systemRef = archiveSystemRef,
                                        talkgroupRef = archiveTalkgroupRef,
                                        date = archiveDateIso,
                                        group = archiveGroup,
                                        tag = archiveTag,
                                        sort = archiveSort
                                    )
                                },
                                enabled = server.status == ConnectionStatus.CONNECTED &&
                                    archiveDateValid
                            ) { Text("Search server") }
                            OutlinedButton(
                                onClick = {
                                    archiveSystemRef = null
                                    archiveTalkgroupRef = null
                                    archiveGroup = null
                                    archiveTag = null
                                    archiveDate = ""
                                    archiveTime = ""
                                    archiveSort = -1
                                }
                            ) { Text("Clear fields") }
                        }
                        Text(
                            "Server results load 200 calls at a time. Use More to continue the same search.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
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
                    CallRow(
                        call,
                        time12hFormat = server.time12hFormat,
                        onContinue = { viewModel.continueHistory(call.profileId, call.id) },
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
private fun TranscriptsScreen(
    scanner: ScannerState,
    profiles: List<ServerProfile>,
    selectedProfileId: String?,
    onSelectProfile: (String) -> Unit,
    viewModel: ScannerViewModel,
    modifier: Modifier
) {
    val server = selectedProfileId?.let { scanner.servers[it] }
    var search by remember(selectedProfileId) { mutableStateOf("") }
    var systemId by remember(selectedProfileId) { mutableStateOf<Long?>(null) }
    var talkgroupId by remember(selectedProfileId) { mutableStateOf<Long?>(null) }
    var dateFrom by remember(selectedProfileId) { mutableStateOf("") }
    var dateTo by remember(selectedProfileId) { mutableStateOf("") }

    fun loadPage(offset: Int) {
        val current = server ?: return
        viewModel.refreshTranscripts(
            profileId = current.profile.id,
            offset = offset,
            systemId = systemId,
            talkgroupId = talkgroupId,
            dateFrom = transcriptDateBoundary(dateFrom, endOfDay = false),
            dateTo = transcriptDateBoundary(dateTo, endOfDay = true),
            search = search
        )
    }

    LaunchedEffect(server?.profile?.id) {
        if (server != null && server.profile.pin.isNotBlank()) {
            loadPage(0)
        }
    }

    val availableSystems = server?.systems.orEmpty().filter { it.systemId != null }
    val availableTalkgroups = availableSystems
        .filter { systemId == null || it.systemId == systemId }
        .flatMap { it.talkgroups }
        .filter { it.talkgroupId != null }

    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.padding(top = 16.dp)) {
                Text(
                    "Transcripts",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                ProfileStrip(profiles, selectedProfileId, onSelectProfile)
            }
        }

        if (server == null) {
            item { Text("Connect the selected scanner to load transcripts.", modifier = Modifier.padding(16.dp)) }
        } else if (server.profile.pin.isBlank()) {
            item { Text("Sign in to this scanner to load transcripts.", modifier = Modifier.padding(16.dp)) }
        } else {
            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Search transcript text") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = dateFrom,
                        onValueChange = { dateFrom = it },
                        label = { Text("From YYYY-MM-DD") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = dateTo,
                        onValueChange = { dateTo = it },
                        label = { Text("To YYYY-MM-DD") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (availableSystems.isNotEmpty()) {
                item { Text("System", modifier = Modifier.padding(horizontal = 16.dp), fontWeight = FontWeight.SemiBold) }
                item {
                    LazyRow(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            if (systemId == null) {
                                Button(onClick = { systemId = null; talkgroupId = null }) { Text("All") }
                            } else {
                                OutlinedButton(onClick = { systemId = null; talkgroupId = null }) { Text("All") }
                            }
                        }
                        items(availableSystems, key = { "transcript-system-" + (it.systemId ?: it.systemRef) }) { system ->
                            if (systemId == system.systemId) {
                                Button(onClick = { systemId = system.systemId; talkgroupId = null }) { Text(system.label) }
                            } else {
                                OutlinedButton(onClick = { systemId = system.systemId; talkgroupId = null }) { Text(system.label) }
                            }
                        }
                    }
                }
            }
            if (availableTalkgroups.isNotEmpty()) {
                item { Text("Talkgroup", modifier = Modifier.padding(horizontal = 16.dp), fontWeight = FontWeight.SemiBold) }
                item {
                    LazyRow(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            if (talkgroupId == null) {
                                Button(onClick = { talkgroupId = null }) { Text("All") }
                            } else {
                                OutlinedButton(onClick = { talkgroupId = null }) { Text("All") }
                            }
                        }
                        items(availableTalkgroups, key = { "transcript-talkgroup-" + (it.talkgroupId ?: it.talkgroupRef) }) { talkgroup ->
                            if (talkgroupId == talkgroup.talkgroupId) {
                                Button(onClick = { talkgroupId = talkgroup.talkgroupId }) { Text(talkgroup.displayName) }
                            } else {
                                OutlinedButton(onClick = { talkgroupId = talkgroup.talkgroupId }) { Text(talkgroup.displayName) }
                            }
                        }
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { loadPage(0) },
                        enabled = !server.transcriptsLoading
                    ) { Text(if (server.transcriptsLoading) "Loading…" else "Search") }
                    OutlinedButton(
                        onClick = {
                            search = ""
                            systemId = null
                            talkgroupId = null
                            dateFrom = ""
                            dateTo = ""
                            viewModel.refreshTranscripts(server.profile.id, offset = 0)
                        },
                        enabled = !server.transcriptsLoading
                    ) { Text("Clear") }
                }
            }

            server.transcriptsError?.let { error ->
                item { Text(error, modifier = Modifier.padding(horizontal = 16.dp)) }
            }

            if (!server.transcriptsLoading && server.transcripts.isEmpty()) {
                item { Text("No transcripts found.", modifier = Modifier.padding(horizontal = 16.dp)) }
            }

            items(server.transcripts, key = { "transcript-" + it.profileId + "-" + it.callId }) { transcript ->
                Card(
                    onClick = { viewModel.replay(server.profile.id, transcript.callId) },
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            transcript.talkgroupLabel ?: transcript.talkgroupName ?: "Call ${transcript.callId}",
                            fontWeight = FontWeight.SemiBold
                        )
                        transcript.systemLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        transcript.timestamp?.let {
                            Text(
                                formatTranscriptTimestamp(it, server.time12hFormat),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        transcript.alertSummary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Text(
                            transcript.reviewedTranscript?.takeIf { it.isNotBlank() } ?: transcript.transcript,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        transcript.transcriptionStatus?.let {
                            Text("Status: $it", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Tap to play", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { loadPage((server.transcriptsOffset - 50).coerceAtLeast(0)) },
                        enabled = server.transcriptsOffset > 0 && !server.transcriptsLoading
                    ) { Text("Previous") }
                    OutlinedButton(
                        onClick = { loadPage(server.transcriptsOffset + 50) },
                        enabled = server.transcriptsHasMore && !server.transcriptsLoading
                    ) { Text("Next") }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun AlertsScreen(
    scanner: ScannerState,
    profiles: List<ServerProfile>,
    selectedProfileId: String?,
    onSelectProfile: (String) -> Unit,
    viewModel: ScannerViewModel,
    modifier: Modifier
) {
    val server = selectedProfileId?.let { scanner.servers[it] }
    var alertQuery by remember(selectedProfileId) { mutableStateOf("") }
    var preferenceQuery by remember(selectedProfileId) { mutableStateOf("") }
    var showAlertPreferences by remember(selectedProfileId) { mutableStateOf(false) }
    var keywordListEditorId by remember(selectedProfileId) { mutableStateOf<Long?>(null) }
    var keywordListEditorLabel by remember(selectedProfileId) { mutableStateOf("") }
    var keywordListEditorDescription by remember(selectedProfileId) { mutableStateOf("") }
    var keywordListEditorKeywords by remember(selectedProfileId) { mutableStateOf("") }
    val normalizedQuery = alertQuery.trim().lowercase()
    val normalizedPreferenceQuery = preferenceQuery.trim().lowercase()
    val visibleAlerts = server?.alerts.orEmpty().filter { alert ->
        normalizedQuery.isEmpty() ||
            alert.title.lowercase().contains(normalizedQuery) ||
            alert.body.lowercase().contains(normalizedQuery) ||
            alert.systemLabel?.lowercase()?.contains(normalizedQuery) == true ||
            alert.talkgroupLabel?.lowercase()?.contains(normalizedQuery) == true ||
            alert.talkgroupName?.lowercase()?.contains(normalizedQuery) == true ||
            alert.alertType?.lowercase()?.contains(normalizedQuery) == true ||
            alert.matchedToneSets.any { it.lowercase().contains(normalizedQuery) } ||
            alert.keywords.any { it.lowercase().contains(normalizedQuery) } ||
            alert.transcript?.lowercase()?.contains(normalizedQuery) == true ||
            alert.summary?.lowercase()?.contains(normalizedQuery) == true ||
            alert.incidentAddress?.lowercase()?.contains(normalizedQuery) == true ||
            alert.incidentNature?.lowercase()?.contains(normalizedQuery) == true
    }
    val visiblePreferenceChannels = server?.systems.orEmpty().flatMap { system ->
        system.talkgroups.map { talkgroup -> system to talkgroup }
    }.filter { (system, talkgroup) ->
        normalizedPreferenceQuery.isEmpty() ||
            system.label.lowercase().contains(normalizedPreferenceQuery) ||
            talkgroup.displayName.lowercase().contains(normalizedPreferenceQuery) ||
            talkgroup.tag.lowercase().contains(normalizedPreferenceQuery) ||
            talkgroup.talkgroupRef.toString().contains(normalizedPreferenceQuery)
    }

    LaunchedEffect(server?.profile?.id) {
        if (server != null && server.status == ConnectionStatus.CONNECTED && server.profile.pin.isNotBlank()) {
            viewModel.refreshAlerts(server.profile.id)
            viewModel.refreshSystemAlerts(server.profile.id)
            viewModel.refreshAlertPreferences(server.profile.id)
            viewModel.refreshAlertKeywordLists(server.profile.id)
        }
    }

    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.padding(top = 16.dp)) {
                Text(
                    "Alerts",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                ProfileStrip(profiles, selectedProfileId, onSelectProfile)
            }
        }

        if (server == null) {
            item { Text("Connect the selected scanner to load alerts.", modifier = Modifier.padding(16.dp)) }
        } else {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            viewModel.refreshAlerts(server.profile.id)
                            viewModel.refreshSystemAlerts(server.profile.id)
                        },
                        enabled = server.status == ConnectionStatus.CONNECTED &&
                            server.profile.pin.isNotBlank() &&
                            !server.alertsLoading &&
                            !server.systemAlertsLoading
                    ) {
                        Text(
                            if (server.alertsLoading || server.systemAlertsLoading) "Refreshing…"
                            else "Refresh"
                        )
                    }
                    Text(
                        visibleAlerts.size.toString() + "/" + server.alerts.size + " shown",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            server.alertsError?.takeIf { it.isNotBlank() }?.let { message ->
                item {
                    Text(
                        message,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            server.systemAlertsError?.takeIf { it.isNotBlank() }?.let { message ->
                item {
                    Text(
                        message,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (
                server.canViewSystemAlerts ||
                server.systemAlerts.isNotEmpty() ||
                server.systemAlertsLoading
            ) {
                item {
                    Card(Modifier.padding(horizontal = 16.dp)) {
                        Column(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("System health alerts", fontWeight = FontWeight.SemiBold)
                            if (server.systemAlertsLoading) {
                                Text("Refreshing system alerts…", style = MaterialTheme.typography.bodySmall)
                            } else if (server.systemAlerts.isEmpty()) {
                                Text("No active system alerts.", style = MaterialTheme.typography.bodySmall)
                            } else {
                                server.systemAlerts.forEachIndexed { index, systemAlert ->
                                    if (index > 0) HorizontalDivider()
                                    Text(
                                        listOf(systemAlert.severity, systemAlert.alertType)
                                            .filter { it.isNotBlank() }
                                            .joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    Text(systemAlert.title, fontWeight = FontWeight.Medium)
                                    if (systemAlert.message.isNotBlank()) {
                                        Text(systemAlert.message, style = MaterialTheme.typography.bodySmall)
                                    }
                                    systemAlert.data?.takeIf { it.isNotBlank() }?.let { data ->
                                        Text(data, style = MaterialTheme.typography.bodySmall)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            viewModel.dismissSystemAlert(
                                                server.profile.id,
                                                systemAlert.id
                                            )
                                        },
                                        enabled = !server.systemAlertsLoading
                                    ) { Text("Dismiss") }
                                }
                            }
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = alertQuery,
                    onValueChange = { alertQuery = it },
                    label = { Text("Search alerts") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
            }

            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                showAlertPreferences = !showAlertPreferences
                                if (showAlertPreferences) {
                                    viewModel.refreshAlertPreferences(server.profile.id)
                                    viewModel.refreshAlertKeywordLists(server.profile.id)
                                }
                            },
                            enabled = server.profile.pin.isNotBlank()
                        ) {
                            Text(if (showAlertPreferences) "Hide alert settings" else "Alert settings")
                        }
                        if (server.alertPreferencesLoading) {
                            Text("Loading settings…", style = MaterialTheme.typography.bodySmall)
                        } else if (server.alertPreferencesSaving) {
                            Text("Saving settings…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    server.alertPreferencesError?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    server.alertKeywordListsError?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    if (showAlertPreferences && server.alertKeywordListsLoading) {
                        Text("Loading keyword lists…", style = MaterialTheme.typography.bodySmall)
                    } else if (showAlertPreferences && server.alertKeywordListsSaving) {
                        Text("Saving keyword lists…", style = MaterialTheme.typography.bodySmall)
                    }
                    if (showAlertPreferences) {
                        OutlinedTextField(
                            value = preferenceQuery,
                            onValueChange = { preferenceQuery = it },
                            label = { Text("Search alert channels") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            visiblePreferenceChannels.size.toString() + " channels shown",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (showAlertPreferences) {
                item {
                    Card(Modifier.padding(horizontal = 16.dp)) {
                        Column(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Manage keyword lists", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (keywordListEditorId == null) {
                                    "Create a reusable keyword list for alert matching."
                                } else {
                                    "Editing keyword list #" + keywordListEditorId
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                            OutlinedTextField(
                                value = keywordListEditorLabel,
                                onValueChange = { keywordListEditorLabel = it },
                                label = { Text("List name") },
                                singleLine = true,
                                enabled = !server.alertKeywordListsSaving,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = keywordListEditorDescription,
                                onValueChange = { keywordListEditorDescription = it },
                                label = { Text("Description") },
                                singleLine = true,
                                enabled = !server.alertKeywordListsSaving,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = keywordListEditorKeywords,
                                onValueChange = { keywordListEditorKeywords = it },
                                label = { Text("Keywords") },
                                supportingText = { Text("Separate phrases with commas or new lines.") },
                                enabled = !server.alertKeywordListsSaving,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        val listId = keywordListEditorId
                                        if (listId == null) {
                                            viewModel.createAlertKeywordList(
                                                server.profile.id,
                                                keywordListEditorLabel,
                                                keywordListEditorDescription,
                                                keywordListEditorKeywords
                                            )
                                        } else {
                                            viewModel.updateAlertKeywordList(
                                                server.profile.id,
                                                listId,
                                                keywordListEditorLabel,
                                                keywordListEditorDescription,
                                                keywordListEditorKeywords
                                            )
                                        }
                                    },
                                    enabled = keywordListEditorLabel.isNotBlank() &&
                                        !server.alertKeywordListsSaving
                                ) {
                                    Text(if (keywordListEditorId == null) "Create list" else "Save changes")
                                }
                                if (keywordListEditorId != null) {
                                    OutlinedButton(
                                        onClick = {
                                            keywordListEditorId = null
                                            keywordListEditorLabel = ""
                                            keywordListEditorDescription = ""
                                            keywordListEditorKeywords = ""
                                        },
                                        enabled = !server.alertKeywordListsSaving
                                    ) { Text("Cancel edit") }
                                }
                            }

                            if (server.alertKeywordLists.isEmpty() && !server.alertKeywordListsLoading) {
                                Text("No keyword lists yet.", style = MaterialTheme.typography.bodySmall)
                            } else {
                                server.alertKeywordLists.forEach { keywordList ->
                                    Column(
                                        Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(keywordList.label, fontWeight = FontWeight.Medium)
                                        if (keywordList.description.isNotBlank()) {
                                            Text(
                                                keywordList.description,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                        Text(
                                            keywordList.keywords.size.toString() + " keyword(s)",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = {
                                                    keywordListEditorId = keywordList.id
                                                    keywordListEditorLabel = keywordList.label
                                                    keywordListEditorDescription = keywordList.description
                                                    keywordListEditorKeywords =
                                                        keywordList.keywords.joinToString(", ")
                                                },
                                                enabled = !server.alertKeywordListsSaving
                                            ) { Text("Edit") }
                                            OutlinedButton(
                                                onClick = {
                                                    if (keywordListEditorId == keywordList.id) {
                                                        keywordListEditorId = null
                                                        keywordListEditorLabel = ""
                                                        keywordListEditorDescription = ""
                                                        keywordListEditorKeywords = ""
                                                    }
                                                    viewModel.deleteAlertKeywordList(
                                                        server.profile.id,
                                                        keywordList.id
                                                    )
                                                },
                                                enabled = !server.alertKeywordListsSaving
                                            ) { Text("Delete") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                itemsIndexed(
                    visiblePreferenceChannels,
                    key = { index, pair ->
                        "alert-pref-" + server.profile.id + "-" + index + "-" +
                            pair.second.systemRef + "-" + pair.second.talkgroupRef
                    }
                ) { _, pair ->
                    val system = pair.first
                    val talkgroup = pair.second
                    val preference = server.alertPreferences.firstOrNull {
                        it.systemRef == talkgroup.systemRef && it.talkgroupRef == talkgroup.talkgroupRef
                    }
                    val alertEnabled = preference?.alertEnabled == true
                    val toneAlerts = preference?.toneAlerts ?: true
                    val keywordAlerts = preference?.keywordAlerts ?: true
                    val selectedToneSetIds = preference?.toneSetIds.orEmpty().toSet()
                    var showToneSets by remember(
                        server.profile.id,
                        talkgroup.systemRef,
                        talkgroup.talkgroupRef
                    ) { mutableStateOf(false) }
                    var showKeywordLists by remember(
                        server.profile.id,
                        talkgroup.systemRef,
                        talkgroup.talkgroupRef
                    ) { mutableStateOf(false) }

                    var showChannelSoundMenu by remember(
                        server.profile.id,
                        talkgroup.systemRef,
                        talkgroup.talkgroupRef
                    ) { mutableStateOf(false) }

                    var customKeywordsText by remember(
                        server.profile.id,
                        talkgroup.systemRef,
                        talkgroup.talkgroupRef,
                        preference?.keywords
                    ) {
                        mutableStateOf(preference?.keywords?.joinToString(", ").orEmpty())
                    }

                    Card(Modifier.padding(horizontal = 16.dp)) {
                        Column(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(talkgroup.displayName, fontWeight = FontWeight.SemiBold)
                            Text(
                                system.label + " · TG " + talkgroup.talkgroupRef,
                                style = MaterialTheme.typography.bodySmall
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                item {
                                    if (alertEnabled) {
                                        Button(
                                            onClick = {
                                                viewModel.setAlertPreference(
                                                    server.profile.id,
                                                    talkgroup.key,
                                                    alertEnabled = false
                                                )
                                            }
                                        ) { Text("Alert ✓") }
                                    } else {
                                        OutlinedButton(
                                            onClick = {
                                                viewModel.setAlertPreference(
                                                    server.profile.id,
                                                    talkgroup.key,
                                                    alertEnabled = true
                                                )
                                            }
                                        ) { Text("Alert off") }
                                    }
                                }
                                item {
                                    Column {
                                        OutlinedButton(onClick = { showChannelSoundMenu = true }) {
                                            Text(
                                                "Sound: " +
                                                    ServerAlertSounds.labelFor(preference?.notificationSound)
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = showChannelSoundMenu,
                                            onDismissRequest = { showChannelSoundMenu = false }
                                        ) {
                                            ServerAlertSounds.choices.forEach { sound ->
                                                DropdownMenuItem(
                                                    text = { Text(sound.label) },
                                                    onClick = {
                                                        showChannelSoundMenu = false
                                                        viewModel.setAlertNotificationSound(
                                                            server.profile.id,
                                                            talkgroup.key,
                                                            sound.fileName
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                                item {
                                    when {
                                        !talkgroup.toneDetectionEnabled -> {
                                            OutlinedButton(
                                                onClick = {},
                                                enabled = false
                                            ) { Text("Tone unavailable") }
                                        }
                                        toneAlerts -> {
                                            Button(
                                                onClick = {
                                                    viewModel.setAlertPreference(
                                                        server.profile.id,
                                                        talkgroup.key,
                                                        toneAlerts = false
                                                    )
                                                }
                                            ) { Text("Tone ✓") }
                                        }
                                        else -> {
                                            OutlinedButton(
                                                onClick = {
                                                    viewModel.setAlertPreference(
                                                        server.profile.id,
                                                        talkgroup.key,
                                                        toneAlerts = true
                                                    )
                                                }
                                            ) { Text("Tone off") }
                                        }
                                    }
                                }
                                if (talkgroup.toneDetectionEnabled && talkgroup.toneSets.isNotEmpty()) {
                                    item {
                                        OutlinedButton(onClick = { showToneSets = !showToneSets }) {
                                            Text(
                                                if (selectedToneSetIds.isEmpty()) {
                                                    "Tones all"
                                                } else {
                                                    "Tones " + selectedToneSetIds.size + "/" + talkgroup.toneSets.size
                                                }
                                            )
                                        }
                                    }
                                }
                                item {
                                    if (keywordAlerts) {
                                        Button(
                                            onClick = {
                                                viewModel.setAlertPreference(
                                                    server.profile.id,
                                                    talkgroup.key,
                                                    keywordAlerts = false
                                                )
                                            }
                                        ) { Text("Keyword ✓") }
                                    } else {
                                        OutlinedButton(
                                            onClick = {
                                                viewModel.setAlertPreference(
                                                    server.profile.id,
                                                    talkgroup.key,
                                                    keywordAlerts = true
                                                )
                                            }
                                        ) { Text("Keyword off") }
                                    }
                                }
                                item {
                                    OutlinedButton(onClick = { showKeywordLists = !showKeywordLists }) {
                                        Text(
                                            "Lists " +
                                                (preference?.keywordListIds?.size ?: 0) +
                                                "/" + server.alertKeywordLists.size
                                        )
                                    }
                                }
                            }
                            if (showToneSets && talkgroup.toneDetectionEnabled && talkgroup.toneSets.isNotEmpty()) {
                                Text(
                                    "Leave all unselected to alert on every tone set.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(talkgroup.toneSets, key = { it.id }) { toneSet ->
                                        val selected = toneSet.id in selectedToneSetIds
                                        var showToneSoundMenu by remember(
                                            server.profile.id,
                                            talkgroup.systemRef,
                                            talkgroup.talkgroupRef,
                                            toneSet.id
                                        ) { mutableStateOf(false) }
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            if (selected) {
                                                Button(
                                                    onClick = {
                                                        viewModel.setAlertToneSets(
                                                            server.profile.id,
                                                            talkgroup.key,
                                                            selectedToneSetIds - toneSet.id
                                                        )
                                                    }
                                                ) { Text(toneSet.label + " ✓") }
                                            } else {
                                                OutlinedButton(
                                                    onClick = {
                                                        viewModel.setAlertToneSets(
                                                            server.profile.id,
                                                            talkgroup.key,
                                                            selectedToneSetIds + toneSet.id
                                                        )
                                                    }
                                                ) { Text(toneSet.label) }
                                            }
                                            OutlinedButton(onClick = { showToneSoundMenu = true }) {
                                                Text(
                                                    "Sound: " +
                                                        ServerAlertSounds.labelFor(
                                                            preference?.toneSetSounds?.get(toneSet.id)
                                                        )
                                                )
                                            }
                                            DropdownMenu(
                                                expanded = showToneSoundMenu,
                                                onDismissRequest = { showToneSoundMenu = false }
                                            ) {
                                                ServerAlertSounds.choices.forEach { sound ->
                                                    DropdownMenuItem(
                                                        text = { Text(sound.label) },
                                                        onClick = {
                                                            showToneSoundMenu = false
                                                            viewModel.setAlertToneSetSound(
                                                                server.profile.id,
                                                                talkgroup.key,
                                                                toneSet.id,
                                                                sound.fileName
                                                            )
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (showKeywordLists) {
                                if (server.alertKeywordLists.isEmpty() && !server.alertKeywordListsLoading) {
                                    Text(
                                        "No server keyword lists are available.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                } else {
                                    val selectedIds = preference?.keywordListIds.orEmpty().toSet()
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        items(server.alertKeywordLists, key = { it.id }) { keywordList ->
                                            val selected = keywordList.id in selectedIds
                                            if (selected) {
                                                Button(
                                                    onClick = {
                                                        viewModel.setAlertKeywordLists(
                                                            server.profile.id,
                                                            talkgroup.key,
                                                            selectedIds - keywordList.id
                                                        )
                                                    }
                                                ) {
                                                    Text(keywordList.label + " ✓")
                                                }
                                            } else {
                                                OutlinedButton(
                                                    onClick = {
                                                        viewModel.setAlertKeywordLists(
                                                            server.profile.id,
                                                            talkgroup.key,
                                                            selectedIds + keywordList.id
                                                        )
                                                    }
                                                ) {
                                                    Text(keywordList.label)
                                                }
                                            }
                                        }
                                    }
                                    server.alertKeywordLists
                                        .filter { it.id in selectedIds }
                                        .takeIf { it.isNotEmpty() }
                                        ?.let { selected ->
                                            Text(
                                                selected.joinToString(" · ") {
                                                    it.label + " (" + it.keywords.size + " words)"
                                                },
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                }
                            }

                            if (showKeywordLists) {
                                OutlinedTextField(
                                    value = customKeywordsText,
                                    onValueChange = { customKeywordsText = it },
                                    label = { Text("Custom keywords") },
                                    supportingText = { Text("Separate phrases with commas or new lines.") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedButton(
                                    onClick = {
                                        viewModel.setAlertKeywords(
                                            server.profile.id,
                                            talkgroup.key,
                                            customKeywordsText
                                        )
                                    }
                                ) { Text("Save keywords") }
                            }

                            if (preference != null) {
                                val detail = buildList {
                                    if (preference.toneSetIds.isNotEmpty()) {
                                        add(preference.toneSetIds.size.toString() + " tone set(s)")
                                    }
                                    if (preference.keywords.isNotEmpty()) {
                                        add(preference.keywords.size.toString() + " keyword(s)")
                                    }
                                    if (preference.keywordListIds.isNotEmpty()) {
                                        add(preference.keywordListIds.size.toString() + " keyword list(s)")
                                    }
                                    if (preference.notificationSound.isNotBlank()) {
                                        add("sound " + preference.notificationSound)
                                    }
                                    if (preference.pagerAlert) add("pager alert")
                                }.joinToString(" · ")
                                if (detail.isNotBlank()) {
                                    Text(detail, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                item {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }

            if (server.alerts.isEmpty() && !server.alertsLoading) {
                item { Text("No alerts loaded.", modifier = Modifier.padding(16.dp)) }
            } else if (visibleAlerts.isEmpty() && alertQuery.isNotBlank()) {
                item { Text("No alerts match the search.", modifier = Modifier.padding(16.dp)) }
            } else {
                itemsIndexed(
                    visibleAlerts,
                    key = { index, alert -> "alert-" + server.profile.id + "-" + index + "-" + alert.stableKey }
                ) { _, alert ->
                    AlertCard(
                        alert,
                        mappingEnabled = server.incidentMappingEnabled,
                        time12hFormat = server.time12hFormat,
                        onReplay = alert.callId?.let { callId ->
                            { viewModel.replay(server.profile.id, callId) }
                        }
                    )
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SettingsScreen(
    scanner: ScannerState,
    profiles: List<ServerProfile>,
    selectedProfileId: String?,
    onSelectProfile: (String) -> Unit,
    viewModel: ScannerViewModel,
    modifier: Modifier
) {
    val loginState by viewModel.accountLogin.collectAsStateWithLifecycle()
    val accountProfiles by viewModel.accountProfiles.collectAsStateWithLifecycle()
    val passwordRecovery by viewModel.passwordRecovery.collectAsStateWithLifecycle()
    val accountPasswordChange by viewModel.accountPasswordChange.collectAsStateWithLifecycle()
    val accountEmailChange by viewModel.accountEmailChange.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editingId by remember(selectedProfileId) {
        mutableStateOf(selectedProfileId ?: UUID.randomUUID().toString())
    }
    val editing = profiles.firstOrNull { it.id == editingId }
    val accountProfileState = accountProfiles[editingId]
    val connectedServer = scanner.servers[editingId]
    var backlogMinutesText by remember(editingId, connectedServer?.livefeedBacklogMinutes) {
        mutableStateOf((connectedServer?.livefeedBacklogMinutes ?: 0).toString())
    }
    var name by remember(editingId, editing?.name) { mutableStateOf(editing?.name ?: "Scanner") }
    var url by remember(editingId, editing?.baseUrl) { mutableStateOf(editing?.baseUrl ?: "") }
    var username by remember(editingId) { mutableStateOf("") }
    var password by remember(editingId) { mutableStateOf("") }
    var forcedNewPassword by remember(editingId) { mutableStateOf("") }
    var forcedConfirmPassword by remember(editingId) { mutableStateOf("") }
    var showPasswordRecovery by remember(editingId) { mutableStateOf(false) }
    var recoveryEmail by remember(editingId) { mutableStateOf("") }
    var recoveryCode by remember(editingId) { mutableStateOf("") }
    var recoveryNewPassword by remember(editingId) { mutableStateOf("") }
    var accountPasswordCode by remember(editingId) { mutableStateOf("") }
    var accountNewPassword by remember(editingId) { mutableStateOf("") }
    var emailChangeCode by remember(editingId) { mutableStateOf("") }
    var emailChangeNewAddress by remember(editingId) { mutableStateOf("") }
    var emailChangePassword by remember(editingId) { mutableStateOf("") }
    var pin by remember(editingId, editing?.pin) { mutableStateOf(editing?.pin ?: "") }
    var alertSoundLabel by remember(editingId) {
        mutableStateOf(AlertSoundPreferences.displayName(context, editingId))
    }
    var disconnectSoundLabel by remember(editingId) {
        mutableStateOf(AlertSoundPreferences.displayDisconnectName(context, editingId))
    }
    var weatherSoundLabel by remember(editingId) {
        mutableStateOf(AlertSoundPreferences.displayWeatherName(context, editingId))
    }
    var weatherSoundEnabled by remember(editingId) {
        mutableStateOf(AlertSoundPreferences.isWeatherSoundEnabled(context, editingId))
    }
    val alertSoundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val picked = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            AlertSoundPreferences.set(context, editingId, picked)
            alertSoundLabel = AlertSoundPreferences.displayName(context, editingId)
        }
    }

    val disconnectSoundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val picked = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            AlertSoundPreferences.setDisconnect(context, editingId, picked)
            disconnectSoundLabel = AlertSoundPreferences.displayDisconnectName(context, editingId)
        }
    }
    val weatherSoundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val picked = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            AlertSoundPreferences.setWeather(context, editingId, picked)
            weatherSoundLabel = AlertSoundPreferences.displayWeatherName(context, editingId)
        }
    }

    LaunchedEffect(loginState.message) {
        if (loginState.message == "Signed in") {
            password = ""
            forcedNewPassword = ""
            forcedConfirmPassword = ""
        }
    }

    LaunchedEffect(passwordRecovery.message) {
        if (passwordRecovery.message == "Password reset successful") {
            recoveryCode = ""
            recoveryNewPassword = ""
            password = ""
        }
    }

    LaunchedEffect(accountPasswordChange.message) {
        if (accountPasswordChange.message == "Password updated successfully") {
            accountPasswordCode = ""
            accountNewPassword = ""
            password = ""
        }
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
                        viewModel.clearPasswordRecoveryStatus()
                        viewModel.clearAccountPasswordChangeStatus()
                        viewModel.clearAccountEmailChangeStatus()
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
                        viewModel.clearPasswordRecoveryStatus()
                        viewModel.clearAccountPasswordChangeStatus()
                        viewModel.clearAccountEmailChangeStatus()
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

                    if (loginState.needsPasswordReset) {
                        HorizontalDivider()
                        Text(
                            "Password update required",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Your scanner administrator requires a new password before you can connect. Use at least 8 characters with an uppercase letter, a lowercase letter, and a number.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            forcedNewPassword,
                            { forcedNewPassword = it },
                            label = { Text("New password") },
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true
                        )
                        AccountLoginPolicy.passwordValidationError(forcedNewPassword)?.let { error ->
                            Text(error, style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedTextField(
                            forcedConfirmPassword,
                            { forcedConfirmPassword = it },
                            label = { Text("Confirm new password") },
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true
                        )
                        if (forcedConfirmPassword.isNotEmpty() && forcedConfirmPassword != forcedNewPassword) {
                            Text("Passwords do not match", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            onClick = {
                                viewModel.forcePasswordResetAndConnect(
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
private fun RecentLiveCallRow(
    call: RadioCall,
    time12hFormat: Boolean,
    onPlay: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(
                onClickLabel = "Play ${call.talkgroupLabel}",
                role = Role.Button,
                onClick = onPlay
            )
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                call.talkgroupLabel,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                call.systemLabel + " · TG " + call.talkgroupRef,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (call.dateTime.isNotBlank()) {
            Text(
                formatServerDateTime(call.dateTime, time12hFormat, includeDate = false),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
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
