package dev.scanrelay.app.weather

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.scanrelay.app.model.ServerProfile
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun WeatherCard(profile: ServerProfile, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember(context) { NwsWeatherRepository(context) }
    var zip by remember(profile.id) { mutableStateOf(repository.savedZip(profile.id)) }
    var editingZip by remember(profile.id) { mutableStateOf(false) }
    var zipDraft by remember(profile.id) { mutableStateOf(zip) }
    var weather by remember(profile.id) { mutableStateOf<NwsWeatherBundle?>(null) }
    var loading by remember(profile.id) { mutableStateOf(false) }
    var error by remember(profile.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(profile.id) {
        if (zip.isBlank() && profile.pin.isNotBlank() && profile.baseUrl.isNotBlank()) {
            val accountZip = repository.accountZip(profile)
            if (zip.isBlank() && accountZip != null) {
                zip = accountZip
                zipDraft = accountZip
                repository.saveZip(profile.id, accountZip)
            }
        }
    }

    LaunchedEffect(profile.id, zip) {
        if (!isValidUsZip(zip)) {
            weather = null
            return@LaunchedEffect
        }
        while (true) {
            loading = weather == null
            error = null
            runCatching { repository.load(zip) }
                .onSuccess { weather = it }
                .onFailure { error = it.message ?: "Weather data is temporarily unavailable." }
            loading = false
            delay(10 * 60 * 1000L)
        }
    }

    Card(modifier.padding(horizontal = 16.dp)) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Weather", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    weather?.locationLabel?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (!editingZip) {
                    OutlinedButton(onClick = {
                        zipDraft = zip
                        editingZip = true
                    }) { Text(zip.ifBlank { "Set ZIP" }) }
                }
            }

            if (editingZip) {
                OutlinedTextField(
                    value = zipDraft,
                    onValueChange = { zipDraft = it.take(10) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("US ZIP code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val candidate = zipDraft.trim()
                        if (isValidUsZip(candidate)) {
                            repository.saveZip(profile.id, candidate)
                            zip = candidate
                            editingZip = false
                        } else {
                            error = "Enter a valid US ZIP code (12345 or 12345-6789)."
                        }
                    }) { Text("Save ZIP") }
                    OutlinedButton(onClick = { editingZip = false }) { Text("Cancel") }
                }
            }

            if (loading && weather == null) Text("Loading local forecast…")
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
            if (zip.isBlank() && !loading && !editingZip) {
                Text("Set your ZIP code for a local forecast.", style = MaterialTheme.typography.bodyMedium)
            }
            weather?.let { snapshot ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        snapshot.currentTempF?.let { "$it°F" } ?: "—",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        snapshot.currentConditions.ifBlank { "—" },
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (snapshot.hourly.isNotEmpty()) {
                    Text("Next 5 hours", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        snapshot.hourly.forEach { period ->
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(formatWeatherHour(period.startTime), style = MaterialTheme.typography.labelSmall)
                                Text(
                                    period.temperature?.let { "$it°" } ?: "—",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                period.probabilityOfPrecipitation?.takeIf { it > 0 }?.let {
                                    Text("$it%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }

                if (snapshot.daily.isNotEmpty()) {
                    Text("3-day", style = MaterialTheme.typography.labelLarge)
                    snapshot.daily.forEach { period ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(formatWeatherDay(period.startTime, period.name), modifier = Modifier.width(64.dp), fontWeight = FontWeight.Medium)
                            Text(period.temperature?.let { "$it°${period.temperatureUnit}" } ?: "—", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                period.shortForecast,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatWeatherHour(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("ha", Locale.getDefault()).format(
        Instant.parse(value).atZone(ZoneId.systemDefault())
    )
}.getOrDefault("—")

private fun formatWeatherDay(value: String, fallback: String): String = runCatching {
    DateTimeFormatter.ofPattern("EEE M/d", Locale.getDefault()).format(
        Instant.parse(value).atZone(ZoneId.systemDefault())
    )
}.getOrDefault(fallback)
