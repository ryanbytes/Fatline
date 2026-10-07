package dev.scanrelay.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.scanrelay.app.weather.WeatherRepository
import dev.scanrelay.app.weather.WeatherSnapshot
import kotlinx.coroutines.launch

@Composable
internal fun WeatherScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("fatline_weather_location", 0) }
    val repository = remember { WeatherRepository() }
    val scope = rememberCoroutineScope()
    var zip by remember { mutableStateOf(preferences.getString("zip", "").orEmpty()) }
    var snapshot by remember { mutableStateOf<WeatherSnapshot?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        val normalized = zip.trim()
        if (!Regex("^\\d{5}$").matches(normalized)) {
            error = "Enter a five-digit ZIP code."
            snapshot = null
            return
        }
        preferences.edit().putString("zip", normalized).apply()
        loading = true
        error = null
        scope.launch {
            runCatching { repository.forecastForZip(normalized) }
                .onSuccess { snapshot = it }
                .onFailure { error = it.message ?: "Weather could not be loaded." }
            loading = false
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (zip.isNotBlank()) refresh()
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Weather", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Enter a ZIP code for a local forecast and severe weather warnings.")
                Text(
                    "Weather requests go to public weather services. Your ZIP is not sent to a scanner server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = zip,
                        onValueChange = { zip = it.filter(Char::isDigit).take(5) },
                        label = { Text("ZIP code") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = { refresh() }, enabled = !loading && zip.length == 5) {
                        Text(if (snapshot == null) "Show" else "Refresh")
                    }
                }
            }
        }

        if (loading) item {
            CircularProgressIndicator(Modifier.padding(horizontal = 16.dp))
        }
        error?.let { message -> item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            }
        } }

        snapshot?.let { weather ->
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(weather.location, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("National Weather Service forecast", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (weather.severeAlerts.isNotEmpty()) item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Severe weather", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                        weather.severeAlerts.forEach { Text(it) }
                    }
                }
            }
            items(weather.periods, key = { it.name }) { period ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(period.name, fontWeight = FontWeight.SemiBold)
                            Text("${period.temperature}°${period.temperatureUnit}", style = MaterialTheme.typography.titleMedium)
                        }
                        Text(period.shortForecast)
                        if (period.wind.isNotBlank()) Text("Wind ${period.wind}", style = MaterialTheme.typography.bodySmall)
                        if (period.detailedForecast.isNotBlank()) {
                            Text(period.detailedForecast, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
