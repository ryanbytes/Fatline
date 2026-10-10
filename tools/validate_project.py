#!/usr/bin/env python3
from pathlib import Path
import json
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
errors = []

def require(cond, msg):
    if not cond:
        errors.append(msg)

required = [
    'settings.gradle.kts', 'build.gradle.kts', 'app/build.gradle.kts', '.github/workflows/android.yml',
    'app/src/main/AndroidManifest.xml',
    'app/src/main/java/dev/scanrelay/app/MainActivity.kt',
    'app/src/main/java/dev/scanrelay/app/ScannerViewModel.kt',
    'app/src/main/java/dev/scanrelay/app/net/ThinLineProtocol.kt',
    'app/src/main/java/dev/scanrelay/app/net/ThinLineSocket.kt',
    'app/src/main/java/dev/scanrelay/app/net/ScannerRepository.kt',
    'app/src/main/java/dev/scanrelay/app/net/AudioCrypto.kt',
    'app/src/main/java/dev/scanrelay/app/playback/ScannerService.kt',
    'app/src/main/java/dev/scanrelay/app/data/PinVault.kt',
    'app/src/main/java/dev/scanrelay/app/data/ProfileStore.kt',
    'app/src/main/java/dev/scanrelay/app/data/ChannelStore.kt',
    'app/src/main/java/dev/scanrelay/app/alerts/AlertSoundPreferences.kt',
    'app/src/main/java/dev/scanrelay/app/alerts/LocalTranscriptAlerts.kt',
    'app/src/test/java/dev/scanrelay/app/alerts/LocalTranscriptAlertPolicyTest.kt',
    'app/src/main/java/dev/scanrelay/app/ui/FatLineApp.kt',
    'app/src/main/java/dev/scanrelay/app/ui/ServerDateTime.kt',
    'app/src/main/java/dev/scanrelay/app/ui/UiAccent.kt',
    'app/src/test/java/dev/scanrelay/app/net/ThinLineProtocolTest.kt',
    'app/src/test/java/dev/scanrelay/app/net/ThinLineSocketTest.kt',
    'app/src/main/java/dev/scanrelay/app/net/NetworkHandoffPolicy.kt',
    'app/src/test/java/dev/scanrelay/app/net/NetworkHandoffPolicyTest.kt',
    'app/src/test/java/dev/scanrelay/app/net/AudioCryptoTest.kt',
    'app/src/test/java/dev/scanrelay/app/net/ScannerRepositoryTest.kt',
    'app/src/test/java/dev/scanrelay/app/data/ChannelStoreTest.kt',
    'app/src/main/java/dev/scanrelay/app/data/ScannerPauseStore.kt',
    'app/src/test/java/dev/scanrelay/app/data/ScannerPauseStoreTest.kt',
    'app/src/test/java/dev/scanrelay/app/playback/PlaybackQueuePolicyTest.kt',
    'app/src/main/java/dev/scanrelay/app/playback/PlaybackQueueStore.kt',
    'app/src/test/java/dev/scanrelay/app/playback/PlaybackQueueStoreTest.kt',
    'app/src/main/java/dev/scanrelay/app/playback/PlaybackVolumeStore.kt',
    'app/src/test/java/dev/scanrelay/app/playback/PlaybackVolumeStoreTest.kt',
    'app/src/main/java/dev/scanrelay/app/ui/ScannerHudPolicy.kt',
    'app/src/test/java/dev/scanrelay/app/ui/ScannerHudPolicyTest.kt',
    'app/src/main/java/dev/scanrelay/app/playback/PlaybackNotificationPolicy.kt',
    'app/src/test/java/dev/scanrelay/app/playback/PlaybackNotificationPolicyTest.kt',
    'app/src/test/java/dev/scanrelay/app/ui/ServerDateTimeTest.kt',
    'app/src/test/java/dev/scanrelay/app/ui/UiAccentTest.kt',
]
for rel in required:
    require((ROOT / rel).is_file(), f'missing {rel}')

app_gradle = (ROOT / 'app/build.gradle.kts').read_text()
root_gradle = (ROOT / 'build.gradle.kts').read_text()
manifest_text = (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
activity = (ROOT / 'app/src/main/java/dev/scanrelay/app/MainActivity.kt').read_text()
theme = (ROOT / 'app/src/main/res/values/themes.xml').read_text()
require('<item name="android:windowLightStatusBar">false</item>' in theme
        and '<item name="android:windowFullscreen">false</item>' in theme,
        'dark FatLine theme must use visible light status-bar icons and keep status bar enabled')
require('WindowCompat.getInsetsController(window, window.decorView)' in activity
        and 'isAppearanceLightStatusBars = false' in activity
        and 'show(WindowInsetsCompat.Type.statusBars())' in activity
        and 'window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)' in activity,
        'Android 15+ edge-to-edge must show light status bar icons and never use immersive fullscreen')
require('hide(WindowInsetsCompat.Type.statusBars())' not in activity
        and 'FLAG_FULLSCREEN,' not in activity,
        'activity must not hide the status bar')
protocol = (ROOT / 'app/src/main/java/dev/scanrelay/app/net/ThinLineProtocol.kt').read_text()
socket = (ROOT / 'app/src/main/java/dev/scanrelay/app/net/ThinLineSocket.kt').read_text()
service = (ROOT / 'app/src/main/java/dev/scanrelay/app/playback/ScannerService.kt').read_text()
vault = (ROOT / 'app/src/main/java/dev/scanrelay/app/data/PinVault.kt').read_text()
channel_store = (ROOT / 'app/src/main/java/dev/scanrelay/app/data/ChannelStore.kt').read_text()
repo = (ROOT / 'app/src/main/java/dev/scanrelay/app/net/ScannerRepository.kt').read_text()
crypto = (ROOT / 'app/src/main/java/dev/scanrelay/app/net/AudioCrypto.kt').read_text()
models = (ROOT / 'app/src/main/java/dev/scanrelay/app/model/Models.kt').read_text()
viewmodel = (ROOT / 'app/src/main/java/dev/scanrelay/app/ScannerViewModel.kt').read_text()
ui = (ROOT / 'app/src/main/java/dev/scanrelay/app/ui/FatLineApp.kt').read_text()
alert_sound = (ROOT / 'app/src/main/java/dev/scanrelay/app/alerts/AlertSoundPreferences.kt').read_text()
alert_notifier = (ROOT / 'app/src/main/java/dev/scanrelay/app/alerts/AlertNotifier.kt').read_text()
protocol_tests = (ROOT / 'app/src/test/java/dev/scanrelay/app/net/ThinLineProtocolTest.kt').read_text()
socket_tests = (ROOT / 'app/src/test/java/dev/scanrelay/app/net/ThinLineSocketTest.kt').read_text()

require('compileSdk = 36' in app_gradle, 'compileSdk must be 36')
require('targetSdk = 36' in app_gradle, 'targetSdk must be 36')
require('version "9.2.1"' in root_gradle, 'AGP 9.2.1 not pinned')
require('org.jetbrains.kotlin.android' not in root_gradle + app_gradle, 'AGP 9 built-in Kotlin must not apply org.jetbrains.kotlin.android')
require('kotlin-gradle-plugin:2.3.21' in root_gradle, 'Kotlin Gradle Plugin 2.3.21 not pinned')
require('org.jetbrains.kotlin.plugin.compose' in root_gradle + app_gradle, 'Compose compiler plugin missing')
require('compose-bom:2026.06.00' in app_gradle, 'API-36-compatible Compose BOM not pinned')
require('compose-bom:2026.08.00' not in app_gradle, 'Compose 1.12 BOM requires compileSdk 37')
require('okhttp-bom:5.3.0' in app_gradle, 'OkHttp BOM not pinned')
require('lifecycle-runtime-compose:2.10.0' in app_gradle, 'Lifecycle Compose dependency missing')
require('media3-session:1.11.0' in app_gradle and 'media3-exoplayer:1.11.0' in app_gradle, 'Media3 1.11.0 dependencies missing')
require('FATLINE_CI_KEYSTORE' in app_gradle and 'ciDebug' in app_gradle, 'stable CI signing config missing')
require('GITHUB_RUN_ID' in app_gradle and 'runId / 1_000L' in app_gradle and 'versionCode = ciVersionCode ?: 2' in app_gradle,
        'Android versionCode must use cross-workflow monotonic GitHub run IDs, not workflow-specific run numbers')
require('FATLINE_VERSION_NAME' in app_gradle and 'versionName = releaseVersionName' in app_gradle,
        'GitHub release APK must report its explicit prerelease version name')
require(37_867_092_490 // 1_000 > 10_000 + 266,
        'new release code must be higher than the previous Android CI code 10266')
require((37_867_092_490 // 1_000) <= (37_867_092_569 // 1_000),
        'adjacent release and CI workflow version codes must not decrease')
require('fatline-ci-debug.p12.b64' in (ROOT / '.github/workflows/android.yml').read_text(), 'stable CI signing key decode missing')
require('Verify stable APK signing certificate' in (ROOT / '.github/workflows/android.yml').read_text(), 'APK certificate verification missing')
require((ROOT / 'tools/fatline-ci-debug.p12.b64').is_file(), 'stable CI debug key material missing')

for cmd in ['ALT','INC','CAL','CFG','ERR','XPR','LCL','LSC','LFM','MAX','PIN','PNS','PNG','VER']:
    require(f'= "{cmd}"' in protocol, f'protocol constant {cmd} missing')

require(json.dumps(['PIN','MTIzNA=='], separators=(',', ':')) == '["PIN","MTIzNA=="]', 'PIN fixture malformed')
livefeed = ['LFM', {'4294967299': {'8589934599': True, '8589934600': False}}]
round_trip = json.loads(json.dumps(livefeed, separators=(',', ':')))
require(round_trip[1]['4294967299']['8589934599'] is True, '64-bit LFM fixture malformed')
require(round_trip[1]['4294967299']['8589934600'] is False, 'explicit false LFM fixture malformed')

# Public ThinLine client wire parity.
require('webSocket.send(ThinLineProtocol.command(ThinLineProtocol.VERSION))' in socket, 'VER websocket negotiation missing')
require('webSocket.send(ThinLineProtocol.command(ThinLineProtocol.CONFIG))' in socket, 'CFG websocket negotiation missing')
require('savedPinAttempted.compareAndSet(false, true)' in socket, 'saved PIN must be challenge-driven and single-attempt')
require('profile.pin.isNotBlank()' in socket and 'ThinLineProtocol.pin(profile.pin)' in socket, 'saved PIN challenge response missing')
require('return URI(scheme, uri.userInfo, uri.host, uri.port, "/", null, null).toString()' in socket, 'ThinLine websocket must normalize to server root')
require('system.talkgroups.forEach' in protocol and 'talkgroup.enabled' in protocol, 'LFM must send complete boolean map')
require('callId.toString()' in protocol, 'CAL call id must use ThinLine string wire type')
require('downloadCall' in repo and '/api/calls/$callId/audio' in repo and 'Authorization", "Bearer $pin' in repo, 'authenticated call audio download missing')
require('MediaStore.Downloads.EXTERNAL_CONTENT_URI' in repo and 'Downloads/FatLine' in repo, 'call audio must save to Downloads on modern Android')
require('downloadCall' in viewmodel and 'onDownload' in ui and 'label = "Download archived call audio"' in ui, 'call download UI bridge missing')
require('stopLivefeed()' in socket and 'command(ThinLineProtocol.LIVEFEED_MAP)' in socket, 'bare LFM pause command missing')
require('ExplicitBooleans' in protocol_tests and 'callIdMatchesThinLineStringWireType' in protocol_tests, 'protocol parity regression tests missing')
require('bareLivefeedCommandMatchesThinLinePauseWireType' in protocol_tests, 'bare LFM pause regression test missing')
require('httpServerUrlUsesRootWebsocketEndpoint' in socket_tests, 'websocket URL regression test missing')

try:
    tree = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml')
    manifest = tree.getroot()
    android = '{http://schemas.android.com/apk/res/android}'
    perms = {p.attrib.get(android+'name') for p in manifest.findall('uses-permission')}
    require('android.permission.INTERNET' in perms, 'INTERNET permission missing')
    require('android.permission.ACCESS_NETWORK_STATE' in perms, 'ACCESS_NETWORK_STATE permission missing')
    require('android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK' in perms, 'media playback FGS permission missing')
    services = manifest.findall('application/service')
    scanner = next((s for s in services if s.attrib.get(android+'name') == '.playback.ScannerService'), None)
    require(scanner is not None, 'ScannerService not declared')
    if scanner is not None:
        actions = {a.attrib.get(android+'name') for f in scanner.findall('intent-filter') for a in f.findall('action')}
        require('androidx.media3.session.MediaLibraryService' in actions, 'MediaLibraryService action missing')
        require('android.media.browse.MediaBrowserService' in actions, 'legacy Auto browser action missing')
except Exception as e:
    errors.append(f'manifest parse failed: {e}')

require('AndroidKeyStore' in vault and 'AES/GCM/NoPadding' in vault, 'PIN vault is not Android Keystore AES-GCM')
require('tlr-audio-key-wrap-v1' in crypto and 'ECDH' in crypto and 'AES/GCM/NoPadding' in crypto, 'ThinLine encrypted audio primitives missing')
require('relayKeyExchangeRoundTripsThroughMockTransport' in (ROOT / 'app/src/test/java/dev/scanrelay/app/net/AudioCryptoTest.kt').read_text(), 'encrypted relay key-exchange integration regression missing')
require('ConcurrentHashMap' in repo and 'sessions' in repo, 'multi-server session map missing')
require('CallKey' in models, 'per-server call identity missing')
require('pendingEncrypted' in repo and '20' in repo, 'bounded encrypted-call buffering missing')
require('requestHistory' in repo and 'LIST_CALL' in protocol, 'server history support missing')
require('callMutex = Mutex()' in repo and 'callMutex.withLock' in repo, 'per-server call ordering mutex missing')
require('keyHttpClient = OkHttpClient()' in repo or 'keyHttpClient = OkHttpClient.Builder()' in repo, 'shared encrypted-audio key exchange client missing')
require('.followRedirects(false)' in repo and '.followSslRedirects(false)' in repo, 'scanner HTTP client must not follow redirects')
require('ScannerEndpointPolicy.requireAllowedHost(uri.host)' in socket and 'ScannerEndpointPolicy.requireAllowedHost(relayHost)' in crypto, 'ThinLine-hosted endpoints must be blocked before socket or relay requests')
require('ScannerEndpointPolicy.requireAllowedHost(uri.host)' in viewmodel, 'ThinLine-hosted account endpoints must be blocked')
require('"thinlineradio.com", "thinlineds.com"' in (ROOT / 'app/src/main/java/dev/scanrelay/app/net/ScannerEndpointPolicy.kt').read_text(), 'vendor domain privacy policy must cover both owned domain families')
require('refreshing audio key' in repo and 'bufferEncryptedCallLocked' in repo, 'encrypted-audio key rotation recovery missing')
require('ScannerService.removeProfile' not in repo and 'ScannerService::stopAudio' not in repo, 'repository must not control service disconnect lifecycle')

# Network handoff / connection-loss hardening.
require('pingInterval(15' in socket and 'terminalDelivered' in socket and 'fun abort()' in socket, 'fast terminal socket handling missing')
require('registerDefaultNetworkCallback' in service, 'Android default-network callback missing')
require('network.networkHandle' in service and 'NETWORK_LOSS_GRACE_MS = 650L' in service, 'network handoff identity/grace handling missing')
require('networkUnavailable()' in repo and 'networkChanged(' in repo, 'network-aware repository recovery missing')
require('NetworkHandoffPolicy.transition' in service and 'NetworkHandoffPolicy.isCurrentLoss' in service, 'network callback ordering must use the tested handoff policy')
pause_store = (ROOT / 'app/src/main/java/dev/scanrelay/app/data/ScannerPauseStore.kt').read_text()
pause_tests = (ROOT / 'app/src/test/java/dev/scanrelay/app/data/ScannerPauseStoreTest.kt').read_text()
require('ScannerPauseStore(context.applicationContext)' in repo and 'paused = pauseStore?.isPaused(profile.id) == true' in repo and 'savedOverrides.hold' in repo, 'reconnected sessions must restore persisted pause choice')
require('pauseStore?.setPaused(profileId, paused)' in repo and 'paused = paused' in repo, 'pause/resume changes must be persisted')
require('pausedProfiles.addAll(validIds.filter(pauseStore::isPaused))' in service, 'foreground restart must restore paused scanner list')
require('pauseStore.isPaused(profileId)' in service and 'ScannerPausePolicy.suppressIncomingAudio(' in service, 'service must reject queued live audio from persisted paused profiles')
require('ScannerPauseStore(getApplication()).deleteProfile(profileId)' in viewmodel, 'deleted scanner pause preference must be removed')
require('pausedScannerRejectsLiveCallsButAllowsManualReplays' in pause_tests and 'fun deleteProfile(profileId: String)' in pause_store, 'pause persistence policy regression test missing')
require('classifiesOfflineRestoreSwitchAndDuplicateCallbacks' in (ROOT / 'app/src/test/java/dev/scanrelay/app/net/NetworkHandoffPolicyTest.kt').read_text(), 'network handoff transition regression test missing')
require('socketGeneration' in repo and 'isCurrent(session, generation)' in repo, 'stale socket callback suppression missing')
require('handshakeJob' in repo and 'armHandshakeWatchdog' in repo and 'Handshake stalled; reconnecting' in repo, 'CFG/auth handshake watchdog missing')
require('Waiting for network' in repo, 'offline retry suspension state missing')
require('30_000L' in repo, 'bounded reconnect backoff missing')

# Background playback / Auto / service lifecycle.
require('MediaLibraryService' in service and 'MediaLibrarySession' in service and 'ExoPlayer' in service, 'Media3 Android Auto/media service missing')

audio_config = service.split('setAudioAttributes(', 1)[1].split('addListener(', 1)[0]
require(re.search(r'\.build\(\),\s*(?://[^\n]*\n\s*)*false\s*\)', audio_config) is not None, 'scanner playback must not request audio focus or duck other apps')
require('android:foregroundServiceType="mediaPlayback"' in manifest_text and 'android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK' in manifest_text and 'START_STICKY' in service, 'background playback must use a persistent media foreground service')
require('android.permission.WAKE_LOCK' in manifest_text and '.setWakeMode(C.WAKE_MODE_LOCAL)' in service, 'screen-off background playback must hold a local wake lock while audio is active')

volume_policy = (ROOT / 'app/src/main/java/dev/scanrelay/app/playback/PlaybackVolumeStore.kt').read_text()
volume_tests = (ROOT / 'app/src/test/java/dev/scanrelay/app/playback/PlaybackVolumeStoreTest.kt').read_text()
require('volume = PlaybackVolumePolicy.gain(_outputVolumePercent.value)' in service, 'startup must restore independent scanner playback gain')
require('player.volume = PlaybackVolumePolicy.gain(normalized)' in service and 'volumeStore.save(normalized)' in service, 'volume action must change and persist ExoPlayer volume only')
require('ACTION_SET_OUTPUT_VOLUME -> setOutputVolumeInternal(' in service and 'fun setOutputVolume(context: Context, percent: Int)' in service, 'scanner volume service action missing')
require('fun setOutputVolume(percent: Int)' in viewmodel and 'viewModel.setOutputVolume(pendingVolume)' in ui, 'scanner volume UI and ViewModel missing')
require('fun gain(percent: Int): Float = clamp(percent) / 100f' in volume_policy and 'outputVolumeControlsOnlyPlayerGain' in volume_tests, 'independent output gain policy test missing')
require('AudioManager' not in service and 'setStreamVolume(' not in service, 'scanner volume must never set the OS shared stream volume')


require('startForeground' in service and 'START_STICKY' in service, 'foreground restart behavior missing')
require('suppressRepositoryServiceCallbacks' in service and 'withRepositoryServiceCallbacksSuppressed' in service, 'disconnect service callback suppression missing')
require('validIds' in service and 'persistActiveProfiles' in service and 'stopIfIdle' in service, 'stale-profile restart cleanup missing')
require('trimQueueForIncomingCall' in service and 'PlaybackQueuePolicy.removalIndex' in service, 'bounded playback queue handling missing')
require('queuedCallCount' in ui and 'BadgedBox' in ui and 'queuedCallCount > 0' in ui, 'queued-call count must be visible off the Scanner tab')
navigation = ui.split('NavigationBar {', 1)[1].split(') { padding ->', 1)[0]
require(
    'primaryNavigationTabs = listOf(AppTab.Scanner, AppTab.Channels, AppTab.History, AppTab.Transcripts)' in ui
    and 'moreNavigationTabs = AppTab.entries.filterNot { it in primaryNavigationTabs }' in ui
    and 'Box(Modifier.weight(1f))' in navigation
    and 'moreNavigationTabs.forEach { item ->' in navigation
    and 'DropdownMenu(' in navigation
    and 'fontSize = 9.sp' not in navigation,
    'mobile navigation must keep every destination reachable without seven cramped bottom labels'
)
require('AppTab.Alerts -> AlertsScreen(' in ui and 'AppTab.Transcripts -> TranscriptsScreen(' in ui,
        'Alerts must stay available through More when Transcripts is a primary tab')
dot_policy = (ROOT / 'app/src/main/java/dev/scanrelay/app/ui/AlertDotPolicy.kt').read_text()
dot_tests = (ROOT / 'app/src/test/java/dev/scanrelay/app/ui/AlertDotPolicyTest.kt').read_text()
alert_notifier = (ROOT / 'app/src/main/java/dev/scanrelay/app/alerts/AlertNotifier.kt').read_text()
weather_notifier = (ROOT / 'app/src/main/java/dev/scanrelay/app/alerts/WeatherAlertNotifier.kt').read_text()
require('AlertDotPolicy.markViewedOrBaseline(' in ui and 'AlertDotPolicy.hasNewAlerts(' in ui
        and 'if (hasNewAlerts && tab != AppTab.Alerts)' in ui and 'BadgedBox(badge = { Badge() })' in ui
        and 'item == AppTab.Alerts && hasNewAlerts' in ui,
        'unread alerts must display a dot on More while Alerts remains reachable')
require('markViewedOrBaseline' in dot_policy and 'hasNewAlerts' in dot_policy
        and 'newAlertsAcrossMultipleServersShowDotUntilAlertsViewed' in dot_tests,
        'unread alert dot must not be based only on the presence of archived alerts')
require('channel.setShowBadge(true)' in alert_notifier
        and 'channel.setShowBadge(true)' in weather_notifier
        and 'channel.setShowBadge(false)' in service
        and 'private const val CHANNEL_ID = "fatline_playback_no_badge_v2"' in service
        and 'NotificationCompat.Builder(this, CHANNEL_ID)' in service,
        'alert channels must allow real alerts to badge, while playback migrates to a new non-badging channel')
scanner_screen = ui.split('private fun ScannerScreen(', 1)[1].split('private fun ScannerStatusCard(', 1)[0]
scanner_card = ui.split('private fun ScannerStatusCard(', 1)[1].split('private fun ScannerHudPanel(', 1)[0]
require('ProfileStrip(' not in scanner_screen and 'ScannerProfileTitle(' in scanner_screen,
        'scanner home must not show a redundant profile pill above its status card')
profile_title = ui.split('private fun ScannerProfileTitle(', 1)[1].split('private fun ScannerStatusCard(', 1)[0]
require('profiles.size > 1' in profile_title and 'onSelectProfile(option.id)' in profile_title
        and 'onClickLabel = "Switch scanner"' in profile_title
        and 'DropdownMenu(' in profile_title,
        'multiple scanner profiles must stay selectable by tapping the status card title')
require('ScannerProfileTitle(server.profile, profiles, onSelectProfile)' in scanner_card
        and 'ScannerStatusCard(server, profiles, onSelectProfile,' in scanner_screen,
        'active scanner must pass profile chooser into its card without an external pill')

clock_view = ui.split('private fun ScannerClock()', 1)[1].split('private fun ScannerScreen(', 1)[0]
require('"FatLine"' not in scanner_screen and '"ThinLine-compatible scanner"' not in scanner_screen,
        'Scanner screen must not show a redundant page title above the status card')
require('TextClock(context)' in clock_view and 'format12Hour = "h:mm a"' in clock_view
        and 'format24Hour = "HH:mm"' in clock_view and 'AndroidView(' in clock_view
        and 'clock.setTextColor(clockColor.toArgb())' in clock_view,
        'live scanner clock must follow Android system time, timezone, locale and 12/24-hour setting')
require(scanner_screen.count('ScannerClock()') >= 2
        and 'ScannerClock()' in scanner_card.split('ScannerHudPanel(', 1)[0],
        'clock must live in scanner status cards, including disconnected fallback, not as an extra top title')

require('private fun NowPlayingCard(' not in ui and 'NowPlayingCard(server' not in scanner_screen, 'large duplicate now-playing card must not return')
require('Text("Call actions")' in scanner_card and '"Avoid channel"' in scanner_card and 'viewModel.setSystemHold(' in scanner_card, 'compact now-playing actions must preserve controls')
require('contentDescription = if (audioEnabled) "Stop scanner audio" else "Play scanner audio"' in scanner_card
        and 'viewModel.setAudioEnabled(!audioEnabled)' in scanner_card, 'independent audio Play/Stop control missing')
require('ACTION_SET_AUDIO_ENABLED' in service and 'KEY_AUDIO_ENABLED' in service and 'if (liveFeed && !_audioEnabled.value)' in service and 'player.pause()' in service, 'stopped audio must be persisted and suppress live output without disconnecting monitoring')
require('if (!liveFeed && !_audioEnabled.value) setAudioEnabledInternal(true)' in service, 'explicit replay must reactivate muted output')
require('val audioEnabled: StateFlow<Boolean>' in service and 'ScannerService.setAudioEnabled(getApplication(), enabled)' in viewmodel, 'audio output state and view-model bridge missing')
require('Slider(' in scanner_card and 'Scanner output · $pendingVolume%' in scanner_card, 'scanner volume slider must be visible in scanner card')

hud_policy = (ROOT / 'app/src/main/java/dev/scanrelay/app/ui/ScannerHudPolicy.kt').read_text()
hud_tests = (ROOT / 'app/src/test/java/dev/scanrelay/app/ui/ScannerHudPolicyTest.kt').read_text()
require('ScannerHudPanel(server, currentlyPlayingCall)' in scanner_card, 'scanner HUD must follow live player state on the status card')
require('ScannerHudPolicy.flags(' in ui and '"LIVE" to flags.live' in ui and '"SCAN" to flags.scan' in ui and '"RX" to flags.rx' in ui, 'scanner HUD annunciators must derive from real state')
require('"HOLD SYS" to flags.holdSystem' in ui and '"HOLD TG" to flags.holdTalkgroup' in ui and '"PAUSE" to flags.pause' in ui, 'scanner HUD hold/pause indicators missing')
require('isPlaying = call != null' in ui and 'scan = live && !isPlaying' in hud_policy and 'rx = isPlaying' in hud_policy, 'HUD must not fake receiving while idle')
require('server.tagColors::get' in ui and 'tagBacklight.copy(alpha = 0.14f)' in ui, 'HUD tag-color backlight missing')
require('connectedIdleShowsScanningButNotReceiverActivity' in hud_tests and 'disconnectedBufferedPlaybackIsStillRecognizedAsAudio' in hud_tests, 'HUD truthful state regression tests missing')

require('Playback queue' not in scanner_screen, 'separate playback queue card must be removed')
require('queuedCalls' in scanner_card and 'Queue · $queuedCallCount waiting' in scanner_card and 'entry.call.talkgroupLabel' in scanner_card, 'queue count and cross-server preview must live in scanner status card')
require('clearPlaybackQueue' in scanner_card and 'ACTION_CLEAR_QUEUE' in service and 'fun clearQueue(context: Context)' in service and 'clearPlaybackQueue()' in viewmodel, 'expanded queue must expose existing clear action')
require('queuedMediaIds' in (ROOT / 'app/src/main/java/dev/scanrelay/app/playback/PlaybackQueuePolicy.kt').read_text() and 'queuedPreviewListsCallsAfterCurrentPlayback' in (ROOT / 'app/src/test/java/dev/scanrelay/app/playback/PlaybackQueuePolicyTest.kt').read_text(), 'queued-call ordering regression test missing')

queue_store = (ROOT / 'app/src/main/java/dev/scanrelay/app/playback/PlaybackQueueStore.kt').read_text()
queue_store_tests = (ROOT / 'app/src/test/java/dev/scanrelay/app/playback/PlaybackQueueStoreTest.kt').read_text()
auto_policy = (ROOT / 'app/src/main/java/dev/scanrelay/app/playback/AndroidAutoLibraryPolicy.kt').read_text()
auto_tests = (ROOT / 'app/src/test/java/dev/scanrelay/app/playback/AndroidAutoLibraryPolicyTest.kt').read_text()
require('AndroidAutoFavoritesCache' in auto_policy
        and 'previous.systems === server.systems' in auto_policy
        and 'previous.hiddenSystems === server.hiddenSystemRefs' in auto_policy
        and 'private val favoriteLibraryCache = AndroidAutoFavoritesCache()' in service
        and 'favoriteLibraryCache.refresh(profileId, state)' in service,
        'Android Auto favorites must reuse unchanged config when only audio/alerts update')
require('skipsRebuildOnLiveCallAndAlertStateChanges' in auto_tests
        and 'changedFavoritesAndHiddenSystemsRefreshAutoLibrary' in auto_tests
        and 'removedServerClearsCachedFavoritesAndDoesNotLeakStaleEntries' in auto_tests,
        'Android Auto favorites cache must refresh on changes and remove disconnected profiles')
require('PlaybackQueueStore(this)' in service and 'restoreSavedPlaybackQueue()' in service and 'persistPlaybackQueue()' in service, 'buffered playback must restore and persist across service restart')
require('PlaybackQueueJournalPolicy.shouldWrite(lastSavedPlaybackQueue, snapshot)' in service
        and 'lastSavedPlaybackQueue = playbackQueueStore.load()' not in service
        and 'lastSavedPlaybackQueue = snapshot' in service
        and 'saveQueueSnapshot(null)' in service
        and 'playbackQueueStore.save(snapshot)' in service
        and 'fun shouldWrite(previous: SavedPlaybackQueue?, current: SavedPlaybackQueue?)' in queue_store
        and 'identicalQueueSnapshotsDoNotNeedRepeatedDiskWrites' in queue_store_tests
        and 'queueEditsAndRecoveryPositionStillJournalImmediately' in queue_store_tests,
        'queue recovery must skip only identical journal writes without delaying new calls, position or stop state')
require('ScannerCallRoutingPolicy.channelEnabled(session.state.systems, key)' in repo
        and 'systems.flatMap { it.talkgroups }.firstOrNull { it.key == key }' not in repo
        and 'hotPathChannelLookupAvoidsFlatteningWithoutChangingRouting' in
            (ROOT / 'app/src/test/java/dev/scanrelay/app/net/ScannerRepositoryTest.kt').read_text()
        and 'duplicateTalkgroupKeyKeepsFirstMatchingEntrySemantics' in
            (ROOT / 'app/src/test/java/dev/scanrelay/app/net/ScannerRepositoryTest.kt').read_text(),
        'call membership lookup must avoid allocating a flat talkgroup list and preserve first-match behavior')
require('recoveryStartIndex(' in service and 'recoveryStartIndex(' in (ROOT / 'app/src/main/java/dev/scanrelay/app/playback/PlaybackQueuePolicy.kt').read_text(), 'playback restore must not replay completed calls')
require('PlaybackQueueStore(context).protectedAudioPaths()' in repo and 'PlaybackCachePolicy.evictablePaths(' in repo, 'audio cache cleanup must not delete queued audio')
require('PlaybackCachePruneSchedule' in queue_store
        and 'private val cachePruneSchedule = PlaybackCachePruneSchedule()' in repo
        and 'if (cachePruneSchedule.afterWrite(profileId)) pruneCache(dir, context)' in repo,
        'cache cleanup should be batched per scanner without changing queued-file protection')
require('cachePruningHappensImmediatelyThenEverySixteenWritesPerScanner' in queue_store_tests
        and 'batchedPruningStillProtectsQueuedCallsWhenSweepOccurs' in queue_store_tests,
        'batched cache cleanup must be covered by queue recovery safety tests')
require('!(entry.liveFeed && pauseStore.isPaused(entry.call.profileId))' in service, 'recovered queue must respect scanner pause settings')
require('player.setMediaItems(recovered.map' in service and 'player.prepare()' in service and 'snapshot.playWhenReady' in service, 'recovered media must preserve saved playback intent')
require('if (snapshot == null)' in service and 'File(it).isFile' in service, 'queue recovery must skip missing audio')
require('queuedCallsSurviveSnapshotRoundTripInOrder' in queue_store_tests and 'cachePruningProtectsAllQueuedFilesAcrossProfiles' in queue_store_tests and 'serviceRecoveryKeepsCurrentAndPendingCallsButNotCompletedOnes' in (ROOT / 'app/src/test/java/dev/scanrelay/app/playback/PlaybackQueuePolicyTest.kt').read_text(), 'queue recovery and cache safety tests missing')


queue_policy = (ROOT / 'app/src/main/java/dev/scanrelay/app/playback/PlaybackQueuePolicy.kt').read_text()
queue_tests = (ROOT / 'app/src/test/java/dev/scanrelay/app/playback/PlaybackQueuePolicyTest.kt').read_text()

require('shouldEnqueueLiveCall(' in queue_policy and 'liveCallKey(' in queue_policy, 'stable live call identity deduplication missing')
require('if (liveFeed && !PlaybackQueuePolicy.shouldEnqueueLiveCall(' in service and 'pendingCalls.remove(token)' in service, 'duplicate live deliveries must be dropped before queue trimming')
require('rememberLiveCall(CallKey(profileId, callId))' in service and 'RECENT_LIVE_ID_LIMIT' in service, 'recent live deduplication must be bounded across reconnects')
require('PlaybackQueuePolicy.liveCallKey(entry.mediaId)?.let(::rememberLiveCall)' in service, 'recovered queued calls must populate the recent-live identity window')
require('recentlyAcceptedLiveCalls.removeAll { it.profileId == profileId }' in service, 'explicit scanner disconnect must clear its recent-live identity window')
require('reconnectCannotQueueDuplicateLiveCallOrEvictOtherWaitingCalls' in queue_tests and 'recentlyPlayedLiveCallsAreNotReplayedWhenServerResendsBacklog' in queue_tests, 'reconnect deduplication tests missing')
require('removeLiveProfileMedia(profileId)' in service and 'isLiveCallForProfile(' in service and 'isLiveCallForProfile(' in queue_policy, 'pausing a scanner must remove only its live calls')
require('removeProfileMedia(profileId)' in service, 'disconnect must still remove all audio for its scanner')
require('pausingScannerFiltersOnlyItsLiveMediaAndPreservesManualReplay' in queue_tests and 'malformedMediaIdentifiersAreNeverMatchedAsLiveCalls' in queue_tests, 'paused scanner manual replay regression coverage missing')
require('fun playNow(profileId: String, callId: Long)' in repo and 'session.pendingImmediateReplay.remove(id)' in repo, 'manual replay priority must survive asynchronous server CAL')
require('fun playNow(profileId: String, callId: Long)' in viewmodel and 'playImmediately = playImmediately' in repo, 'manual replay must reach playback service')
require('EXTRA_PLAY_IMMEDIATELY' in service and 'player.seekTo(position, 0L)' in service, 'selected recent call must start immediately')
require('ScannerPausePolicy.suppressIncomingAudio(' in service and 'liveFeed = liveFeed,' in service and 'paused = profileId in pausedProfiles || pauseStore.isPaused(profileId)' in service and 'liveFeed && paused' in pause_store, 'paused scanning must suppress live calls but permit manual replay')
require('immediateReplayInsertsBeforeCurrentWithoutDiscardingQueue' in (ROOT / 'app/src/test/java/dev/scanrelay/app/playback/PlaybackQueuePolicyTest.kt').read_text(), 'manual replay priority queue regression missing')

require('PlaybackQueueProjectionPolicy.project(' in service
        and 'projection.activeMediaIds' in service
        and 'projection.playingCall' in service
        and 'projection.queuedCalls' in service
        and 'val mediaIds = List(player.mediaItemCount)' not in service
        and 'mediaCount = player.mediaItemCount' in service
        and 'mediaIdAt = { index -> player.getMediaItemAt(index).mediaId }' in service,
        'scanner service must use single-pass playback projection and lazy media iteration')
require('internal object PlaybackQueueProjectionPolicy' in queue_policy
        and 'for (index in 0 until mediaCount)' in queue_policy
        and 'fun removalIndex(' in queue_policy
        and 'val sameKind = mediaIds.indices.filter' not in queue_policy
        and "val parts = mediaId.split(':')" not in queue_policy.split('internal fun mediaKind(mediaId: String)', 1)[1].split('internal data class PlaybackQueueProjection', 1)[0],
        'media kind and playback queue operations must avoid per-item temporary lists')
require('singlePassProjectionExactlyMatchesPreviousPlaybackAndQueueSemantics' in queue_tests
        and 'lazyQueueTrimMatchesIndependentLegacyOverflowReference' in queue_tests
        and 'lazyDuplicateCheckMatchesPreviousLookupAndAvoidsListConstruction' in queue_tests
        and 'allocationFreeMediaKindParsingKeepsOldParsingSemantics' in queue_tests,
        'playback performance changes require queue order/dedup/parsing equivalence tests')

notification_policy = (ROOT / 'app/src/main/java/dev/scanrelay/app/playback/PlaybackNotificationPolicy.kt').read_text()
notification_tests = (ROOT / 'app/src/test/java/dev/scanrelay/app/playback/PlaybackNotificationPolicyTest.kt').read_text()
require('if (!isPlaying) return PlaybackNotificationText("FatLine", "Waiting for traffic")' in notification_policy, 'idle notifications must not claim stale playback metadata')
require('updatePlaybackNotification()' in service and 'PlaybackNotificationPolicy.display(' in service, 'player notification must use current playback state')
require('override fun onIsPlayingChanged(isPlaying: Boolean)' in service
        and 'updatePlaybackNotification()' in service.split(
            'override fun onIsPlayingChanged(isPlaying: Boolean)', 1
        )[1].split('override fun onMediaItemTransition(', 1)[0],
        'playback state changes must refresh notification')
require('stoppedOrPausedPlaybackDoesNotShowStaleCallMetadata' in notification_tests, 'stale playback notification regression test missing')
require('PlaybackNotificationPolicy.needsUpdate(lastPostedNotification, current)' in service
        and 'private var foregroundStarted = false' in service
        and 'if (!foregroundStarted) {' in service
        and 'foregroundStarted = false' in service
        and 'lastPostedNotification = null' in service
        and 'getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(title, queueText))' in service
        and 'identicalNotificationTextAndQueueCountDoNotRepublish' in notification_tests,
        'notification posting must skip duplicates without omitting queue/status changes')
recent_main = ui.split('"Recent live calls",', 1)[1].split('item { Spacer(Modifier.height(16.dp)) }', 1)[0]
recent_row = ui.split('private fun RecentLiveCallRow(', 1)[1].split('@Composable', 1)[0]
require('RecentLiveCallRow(' in recent_main and 'onPlay = { viewModel.playNow(call.profileId, call.id) }' in recent_main, 'recent live calls must tap to play immediately')
require('if (recent.isNotEmpty()) {' in ui
        and '"No live calls received yet."' not in ui
        and 'enabled = server.recentCalls.isNotEmpty()' in scanner_card,
        'hide Recent until a call has completed; Replay must not target unplayed calls')
require('fun recordCompletedLiveCall(call: RadioCall)' in repo
        and 'RecentlyPlayedCallsPolicy.complete(session.state.recentCalls, call)' in repo
        and 'recentCalls = recent' not in repo.split('val shouldPlay: Boolean', 1)[1]
        and 'playedLiveCallTracker.ended()?.let(ScannerRepository::recordCompletedLiveCall)' in service
        and 'reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO' in service
        and 'completed?.let(ScannerRepository::recordCompletedLiveCall)' in service
        and 'playedLiveCallTracker.cancelIf(' in service
        and 'playedLiveCallTracker.cancel()' in service,
        'Recent must update exclusively when actual live playback completes, never on arrival, Skip or Stop')
require('queuedAndStartedCallsAreNotRecentUntilNaturalCompletion' in queue_tests
        and 'manualStopAndRemovingAPlayingCallNeverMarkItPlayed' in queue_tests
        and 'replayItemsAndUnstartedCallsNeverCountAsPlayedLive' in queue_tests,
        'completed-live tracker needs pending, skip, stop and end-of-playlist coverage')

require('private var playbackAdvanced = false' in queue_policy
        and 'fun observedProgress(mediaId: String?, positionMs: Long)' in queue_policy
        and 'if (automatic && playbackAdvanced)' in queue_policy
        and 'val completed = if (playbackAdvanced)' in queue_policy,
        'Recent completion must require actual player position progress')
require('private val playbackProgressCheck = object : Runnable' in service
        and 'playedLiveCallTracker.observedProgress(id, player.currentPosition)' in service
        and 'override fun onPositionDiscontinuity(' in service
        and 'reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION' in service
        and 'oldPosition.mediaItem?.mediaId, oldPosition.positionMs' in service
        and 'playbackProgressHandler.removeCallbacks(playbackProgressCheck)' in service,
        'service must verify progress and stop monitoring it when idle')
require('otherArrivalsAndReplayProgressCannotAdvanceCurrentLiveCall' in queue_tests
        and 'autoTransitionRequiresPlaybackProgressAndCannotInventRecentEntries' in queue_tests
        and 'assertFalse(tracker.observedProgress(id("live", 11), 0L))' in queue_tests,
        'Recent needs zero-progress and unrelated-arrival regression coverage')

scanner_status_controls = scanner_card.split('// Four standard icon-only playback controls', 1)[1].split('if (!audioEnabled)', 1)[0]
require('LazyRow(' not in scanner_status_controls
        and scanner_status_controls.count('modifier = Modifier.weight(1f)') == 5
        and scanner_status_controls.count('OutlinedIconButton(') == 4
        and scanner_status_controls.count('FilledIconButton(') == 1
        and scanner_status_controls.count('Icon(') == 5
        and 'Text(' not in scanner_status_controls
        and 'Spacer(' not in scanner_status_controls,
        'four standard icon-only playback actions plus conditional legacy Resume must fit one row')
require('if (server.paused) {' in scanner_status_controls
        and 'viewModel.setPaused(server.profile.id, false)' in scanner_status_controls
        and 'contentDescription = "Resume previously paused scanner"' in scanner_status_controls
        and 'viewModel.setPaused(server.profile.id, !server.paused)' not in scanner_status_controls
        and 'R.drawable.ic_fatline_pause' not in scanner_status_controls
        and 'Pause scanning' not in scanner_status_controls,
        'main scanner card must not allow pausing; legacy paused users must still be able to resume')
require('viewModel.setAudioEnabled(!audioEnabled)' in scanner_status_controls
        and 'viewModel.replayLast(server.profile.id)' in scanner_status_controls
        and 'onClick = viewModel::skip' in scanner_status_controls
        and 'viewModel.clearHold(server.profile.id)' in scanner_status_controls
        and all('R.drawable.ic_fatline_' + name in scanner_status_controls
                for name in ('stop', 'play', 'replay', 'skip', 'clear'))
        and 'contentDescription = "Skip current call"' in scanner_status_controls
        and 'contentDescription = "Clear scanner hold"' in scanner_status_controls,
        'Play/Stop, Replay, Skip and Clear Hold must remain accessible and functional')
require('fun replayLast(profileId: String)' in repo and 'lastReplayCandidate(session.state)' in repo and 'playNow(profileId, call.id)' in repo, 'Replay last must select and immediately play most recent call')
require('fun replayLast(profileId: String)' in viewmodel, 'Replay last ViewModel binding missing')
replay_tests = (ROOT / 'app/src/test/java/dev/scanrelay/app/net/ScannerRepositoryTest.kt').read_text()
require('replayLastChoosesMostRecentlyCompletedLiveCall' in replay_tests
        and 'replayLastNeverTargetsReceivedButUnplayedCalls' in replay_tests
        and 'recentlyPlayedIsBoundedDeduplicatedAndNewestCompletedFirst' in replay_tests
        and 'invalidCallIdsCannotEnterRecent' in replay_tests,
        'Replay last and Recent must use completed live calls exclusively')

require('                    CallRow(' not in recent_main and 'onDownload' not in recent_main, 'recent live call list must not use History actions')
require('.clickable(' in recent_row and 'onClick = onPlay' in recent_row and 'OutlinedButton(' not in recent_row and 'Button(' not in recent_row, 'recent live rows must be tappable without buttons')

require('serverItem' in service and 'setIsBrowsable(true).setIsPlayable(true)' in service, 'Android Auto server connect item missing')

local_monitor = (ROOT / 'app/src/main/java/dev/scanrelay/app/alerts/LocalTranscriptAlerts.kt').read_text()
local_monitor_tests = (ROOT / 'app/src/test/java/dev/scanrelay/app/alerts/LocalTranscriptAlertPolicyTest.kt').read_text()
require('fun configureLocalTranscriptAlerts(' in repo and 'fun configureLocalTranscriptAlerts(' in viewmodel and 'Local transcript alerts' in ui, 'local transcript rules must be editable in app')
require('fun startTranscriptMonitor(' in repo and 'delay(LocalTranscriptAlertPolicy.pollIntervalMs(store.batterySaver(profileId)))' in repo and 'pollRecentTranscripts(session, store)' in repo, 'transcript monitor must run in the foreground scanner service independently of audio pause')
require('api/transcripts?limit=100&offset=0&pin=' in repo and 'executeJsonArray(request)' in repo, 'monitor must poll server transcript API, not a hosted relay')
require('store.baseline(' in repo and 'store.isNewSinceEnable(' in repo and 'store.accept(profileId, callId)' in repo, 'monitor must skip historical calls and deduplicate live/polled calls')
require('processLocalTranscript(session, id, call.transcript' in repo and 'row.reviewedTranscript' in repo, 'monitor must process both live and delayed transcript text')
require('localTranscriptStore?.alerts(session.profile.id, session.profile.name)' in repo and 'store.addAlert(alert)' in repo, 'locally generated alerts must survive refresh and restarts')
require('session.transcriptMonitorJob?.cancel()' in repo and 'session.profile.pin.isNotBlank()' in repo, 'monitor must stop at disconnect and use authenticated scanner API')
require('store.matches(profileId, transcript)' in repo
        and 'fun matchesPrepared(transcript: String, prepared: List<PreparedRule>)' in local_monitor
        and 'fun matches(transcript: String, rules: List<String>)' in local_monitor
        and 'cachedRules.get(profileId, rawRules(profileId)).prepared' in local_monitor
        and 'cachedRules.clear(profileId)' in local_monitor,
        'local transcript matching must use pre-normalized phrase rules and invalidate edits/deletions')
require('preparedRulesRetainPhraseMatchingAndWordBoundaries' in local_monitor_tests
        and 'cachedRulesReusedAndRepreparedAsSoonAsRuleTextChanges' in local_monitor_tests,
        'prepared local keyword matcher must be covered by equivalence and cache invalidation tests')
require('phrasesMatchRegardlessOfCaseAndSeparator' in local_monitor_tests and 'wordBoundariesAvoidAccidentalMatches' in local_monitor_tests, 'phrase matching regression tests missing')
require('LocalTranscriptAlertStore(getApplication()).clearProfile(profileId)' in viewmodel, 'deleting scanner must erase its local rules and alerts')
require('localTranscriptMonitorStatus' in ui and 'Transcript API unavailable' in repo, 'monitor status and API error visibility missing')
require('fun batterySaver(profileId: String)' in local_monitor and 'battery_saver_$profileId' in local_monitor, 'transcript battery saver preference missing')
require('fun pollIntervalMs(batterySaver: Boolean)' in local_monitor and 'SAVER_POLL_MS = 60_000L' in local_monitor and 'FAST_POLL_MS = 30_000L' in local_monitor, 'transcript saver must have 1-minute cadence with 30-second fallback')
require('fun localTranscriptBatterySaver(' in viewmodel and 'localTranscriptBatterySaver' in ui and 'Battery saver (1-minute checks instead of 30 seconds)' in ui, 'user-selectable saver UI missing')
require('batterySaverHalvesPeriodicNetworkChecks' in local_monitor_tests, 'transcript polling efficiency regression missing')
require('remember(selectedProfileId, server?.systems, server?.hiddenSystemRefs, normalizedQuery, favoritesOnly)' in ui
        and 'remember(selectedProfileId, server?.systems)' in ui
        and 'remember(selectedProfileId, server?.history, normalizedHistoryQuery)' in ui,
        'channel favorites and History filters should only recompute on relevant changes')
aggregation_tests = (ROOT / 'app/src/test/java/dev/scanrelay/app/net/ScannerRepositoryTest.kt').read_text()
require('ScannerStateAggregationPolicy.reuseHistory(' in repo
        and 'ScannerStateAggregationPolicy.reuseAlerts(' in repo
        and 'previous.history' in repo and 'previous.alerts' in repo
        and 'unchangedHistoryAndAlertsAreReusedAcrossStatusAndTranscriptUpdates' in aggregation_tests
        and 'changedArchiveOrAlertsInvalidateOnlyTheirOwnAggregatedCache' in aggregation_tests,
        'archive and alert aggregation must avoid unnecessary re-sorts while invalidating changed lists')
weather = (ROOT / 'app/src/main/java/dev/scanrelay/app/alerts/NwsSevereWeather.kt').read_text()
weather_tests = (ROOT / 'app/src/test/java/dev/scanrelay/app/alerts/NwsSevereWeatherTest.kt').read_text()
require('class NwsAlertPollCache' in weather
        and 'val cycleCache = NwsAlertPollCache()' in weather
        and 'cycleCache.getOrFetch(zip)' in weather
        and 'duplicateWeatherZipsShareAFeedOncePerCycle' in weather_tests
        and 'failedWeatherFetchesCanRetryAndEmptySuccessIsCached' in weather_tests,
        'same-ZIP weather fetches must coalesce only per poll and not cache errors')

# A normal app relaunch must revive previously active sessions without restarting
# still-running scanner sockets, reviving disconnected profiles or using boot receivers.
main_activity = (ROOT / 'app/src/main/java/dev/scanrelay/app/MainActivity.kt').read_text()
restore_policy = (ROOT / 'app/src/main/java/dev/scanrelay/app/playback/SessionRestorePolicy.kt').read_text()
restore_tests = (ROOT / 'app/src/test/java/dev/scanrelay/app/playback/SessionRestorePolicyTest.kt').read_text()
require('ScannerService.resumeActiveConnections(this)' in main_activity, 'app launch must recover explicitly active scanner sessions')
require('ACTION_RESUME_CONNECTIONS -> restoreConnections()' in service and 'fun resumeActiveConnections(context: Context)' in service, 'service resume command missing')
require('if (active.isEmpty()) return' in service and 'getStringSet(KEY_ACTIVE_PROFILES, emptySet())' in service, 'explicit disconnect must not restart scanner')
require('SessionRestorePolicy.missing(validIds, ScannerRepository.state.value.servers.keys)' in service, 'app launch must not restart already active sockets')
require('fun valid(savedIds:' in restore_policy and 'fun missing(validIds:' in restore_policy, 'session recovery reconciliation policy missing')
require('ignoresStaleOrDeletedProfiles' in restore_tests and 'resumesOnlyMissingSessionsWithoutReconnectingWorkingSocket' in restore_tests and 'explicitDisconnectDoesNotReopenAnyScanner' in restore_tests, 'session recovery regression tests missing')
require('BOOT_COMPLETED' not in manifest_text, 'do not start a media playback service from boot on Android 15+')

# User-visible ThinLine parity / enhancements.
require('setMany' in channel_store and 'setSystemEnabled' in repo, 'batched system-level channel update missing')
require('knownKey' in channel_store and 'reconcileChannelSelection' in channel_store, 'newly scoped channel tracking missing')
require('autoEnableNewTalkgroups' in repo and 'channelStore?.apply' in repo, 'server auto-enable-new-talkgroups policy missing')
require('newlyScopedChannelsTurnOnWhenServerPolicyIsEnabled' in (ROOT / 'app/src/test/java/dev/scanrelay/app/data/ChannelStoreTest.kt').read_text(), 'auto-enable regression test missing')
require('baselineMigrationDoesNotBulkEnableCurrentDisabledChannels' in (ROOT / 'app/src/test/java/dev/scanrelay/app/data/ChannelStoreTest.kt').read_text(), 'channel baseline migration regression test missing')
monitoring_tests = (ROOT / 'app/src/test/java/dev/scanrelay/app/data/ChannelStoreTest.kt').read_text()
require('MonitoringOverrides(' in channel_store and 'reconcileMonitoringOverrides(' in channel_store, 'monitoring overrides schema and scope policy missing')
require('monitoringOverrides(profile.id)' in repo and 'savedOverrides.avoided' in repo and 'savedOverrides.hold' in repo, 'explicit scanner reconnect must restore saved holds and avoids')
require('persistMonitoringOverridesLocked(session)' in repo and 'setMonitoringOverrides(' in channel_store, 'hold and avoid changes must be saved locally')
require('scopedOverrides = reconcileMonitoringOverrides(currentOverrides, systems)' in repo and 'avoided = scopedOverrides.avoided' in repo, 'config must prune stale holds and avoids before subscribing')
require('remove(holdChannelKey(profileId))' in channel_store and 'remove(avoidedChannelsKey(profileId))' in channel_store, 'deleting a scanner must clear monitoring overrides')
require('reconnectRetainsAuthorizedHoldAndAvoids' in monitoring_tests and 'scopeChangesRemoveStaleHoldsAndAvoidsWithoutChangingValidSelections' in monitoring_tests and 'systemHoldRemainsWhenAuthorizedButDropsWhenSystemRemoved' in monitoring_tests, 'monitoring override reconnect/scope regression coverage missing')

require('setSystemTalkgroups' in viewmodel and 'setSystemTalkgroups' in ui, 'system-level enable/disable control missing')
require('parseScanLists' in protocol and 'scanLists' in models, 'server scan-list parsing/state missing')
require('setChannelsEnabled' in repo and 'setChannels' in viewmodel, 'batched scan-list channel toggles missing')
require('server.scanLists' in ui and '"Scan Lists"' in ui, 'scan-list UI missing')
require('createScanList' in repo and 'renameScanList' in repo and 'deleteScanList' in repo and 'setScanListChannel' in repo, 'editable Scan List repository actions missing')
require('mergeScanListsIntoSettings' in repo and '/api/settings' in repo and 'Authorization' in repo, 'server-backed Scan List settings sync missing')
require('activeScanListIds' in repo and 'activeScanListId' in repo, 'legacy active Scan List persistence must be cleared on save')
require('scanListSyncing' in models and 'scanListError' in models, 'Scan List sync status missing')
require('createScanList' in viewmodel and 'renameScanList' in viewmodel and 'setScanListChannel' in viewmodel, 'editable Scan List view-model bridge missing')
require('New list name' in ui and 'Edit members' in ui and '+ List' in ui and 'Save name' in ui, 'editable Scan List UI missing')
require('scanListSettingsMergePreservesUnrelatedPreferences' in (ROOT / 'app/src/test/java/dev/scanrelay/app/net/ScannerRepositoryTest.kt').read_text(), 'safe Scan List settings merge regression test missing')
require('scanListSerializationDeduplicatesMembership' in (ROOT / 'app/src/test/java/dev/scanrelay/app/net/ScannerRepositoryTest.kt').read_text(), 'Scan List membership serialization regression test missing')
require('itemsIndexed' in ui and 'systemIndex' in ui and 'talkgroupIndex' in ui, 'channel lazy-list keys must include stable positional disambiguators')
require('Search channels' in ui and 'channelQuery' in ui, 'channel search control missing')
require('favoritesOnly' in ui and 'Favorites only' in ui, 'favorites-only channel filter missing')
require('talkgroup.talkgroupRef.toString().contains(normalizedQuery)' in ui, 'channel search must support TGID lookup')
require('"scan-list-" + server.profile.id + "-" + index + "-" + scanList.id' in ui, 'scan-list lazy keys must tolerate duplicate server IDs')
require('scanListsParseFromUserSettingsWithStringAndNumericRefs' in protocol_tests, 'scan-list parser regression test missing')
require('setPaused' in repo and 'viewModel.setPaused' in ui and 'Resume' in ui and 'Pause' in ui, 'pause/live-feed toggle missing')
require('ACTION_SET_PROFILE_PAUSED' in service and 'pausedProfiles' in service, 'per-profile playback pause suppression missing')
require('ScannerService.setProfilePaused' in repo, 'repository pause must silence queued profile audio')
require('ACTION_FILTER_PROFILE_MEDIA' in service and 'filterProfileMedia' in service, 'playback queue filtering action missing')
require('mediaKind' in service and 'EXTRA_SYSTEM_REF' in service and 'EXTRA_TALKGROUP_REF' in service, 'queued calls must carry live/replay and channel identity')
require('isChannelSubscribed' in repo and 'pruneQueuedLiveCalls' in repo, 'channel filter changes must prune queued live calls')
require('liveFeed = false' in repo, 'archive replay must be protected from live queue filtering')
require('holdSystemRef' in models and 'setSystemHold' in repo and 'viewModel.setSystemHold' in ui, 'system hold parity missing')
require('setHold' in repo and 'avoided' in repo and 'skip' in repo, 'talkgroup hold/avoid/skip support missing')
require('effectiveLivefeedSystems' in repo and 'sendEffectiveLivefeedLocked' in repo, 'hold/avoid must filter the server LFM subscription')
require('talkgroup.key !in state.avoided' in repo, 'avoided channels must be excluded from the server subscription')
require('requestHistory(server.profile.id, false)' in ui and 'historyHasMore' in ui, 'history pagination control missing')
require('LaunchedEffect(server?.profile?.id, server?.status == ConnectionStatus.CONNECTED)' in ui and 'viewModel.refreshHistory(server.profile.id)' in ui, 'opening History must refresh the archive')
require('fun refreshHistory(profileId: String)' in repo and 'preserveFilters = true' in repo and 'historyOffset = 0' in repo, 'History entry refresh must restart from first page without losing filters')
require('fun refreshHistory(profileId: String) = ScannerRepository.refreshHistory(profileId)' in viewmodel, 'History entry view-model bridge missing')
require('Search loaded history' in ui and 'historyQuery' in ui, 'loaded-history search control missing')
require('historySystemRef' in models and 'historyTalkgroupRef' in models, 'active archive server-filter state missing')
require('historyTalkgroupRefs' in models and '"talkgroups", JSONArray(selectedTalkgroups)' in protocol and
        'talkgroupRefs = activeTalkgroupRefs' in repo and 'archiveTalkgroupRefs.toList()' in ui,
        'archive multi-talkgroup selection must survive protocol, pagination and UI')
require('activeSystemRef = session.state.historySystemRef' in repo and 'activeTalkgroupRef = session.state.historyTalkgroupRef' in repo, 'archive pagination must reuse the active server filter')
require('matchesHistoryFilter(session.state, call)' in repo, 'live calls must respect the active archive filter')
require('LiveHistoryMergePolicy.insert(' in repo
        and 'session.state.history, call, newestFirst = session.state.historySort < 0' in repo
        and 'val combined = (session.state.history + calls).associateBy { it.id }.values' in repo
        and 'incrementalHistoryInsertPreservesChronologyBothDirections' in aggregation_tests
        and 'incrementalHistoryDuplicateKeepsStablePositionAmongEqualTimes' in aggregation_tests
        and 'incrementalHistoryMatchesStableSortForLargePagedArchiveAndUpdates' in aggregation_tests,
        'incremental live History must preserve chronological, stable-tie, duplicate-ID and paged batch behavior')
require('internal fun insertionIndex(' in repo
        and 'while (low < high)' in repo.split('internal object LiveHistoryMergePolicy', 1)[1].split('internal object RecentlyPlayedCallsPolicy', 1)[0]
        and 'if (earlier || stableTie) high = mid else low = mid + 1' in repo
        and 'binaryHistoryInsertionScalesLogarithmicallyWithLoadedArchiveSize' in aggregation_tests
        and 'binaryHistoryInsertionKeepsEqualTimestampOrderWhenReplacingAnyPosition' in aggregation_tests,
        'live History lookup must be logarithmic without changing equal-timestamp or replacement order')
require('ScannerStateAggregationPolicy.aggregateHistory(serverMap)' in repo
        and 'if (current.size == 1)' in repo
        and 'if (server.historySort < 0)' in repo
        and 'server.history.size <= 500' in repo
        and 'singleServerNewestFirstAggregateReusesHistoryListWithoutSorting' in aggregation_tests
        and 'singleServerLargeArchiveKeepsNewest500AndDoesNotMutateOriginal' in aggregation_tests
        and 'ascendingAndMultiServerArchivesRetainGlobalDescendingSortAndStableTies' in aggregation_tests,
        'single-server History aggregation must bypass redundant parsing while retaining multi-server sorting')
require('liveCallsRespectActiveHistoryFilter' in (ROOT / 'app/src/test/java/dev/scanrelay/app/net/ScannerRepositoryTest.kt').read_text(), 'archive-filter live-call regression test missing')
require('Current TG' in ui and 'Current SYS' in ui and 'All archive' in ui, 'current-channel/system archive filter controls missing')
require('talkgroupRef: Long? = null' in viewmodel and 'ScannerRepository.requestHistory(profileId, reset, systemRef, talkgroupRef)' in viewmodel, 'archive filter bridge missing from view model')
require('call.sourceDisplay?.lowercase()?.contains(normalizedHistoryQuery)' in ui, 'history search must include unit/talker display')
require('call.transcript?.lowercase()?.contains(normalizedHistoryQuery)' in ui, 'history search must include transcripts')
history_screen = ui.split('private fun HistoryScreen(', 1)[1].split('private fun TranscriptsScreen(', 1)[0]
archive_menus = ui.split('private fun ArchiveMenuButton(', 1)[1].split('private fun HistoryIconAction(', 1)[0]
history_actions = ui.split('private fun HistoryIconAction(', 1)[1].split('private fun HistoryScreen(', 1)[0]
history_call_row = ui.split('private fun CallRow(', 1)[1].split('private fun AlertCard(', 1)[0]
require('OutlinedIconButton(' in archive_menus and 'OutlinedButton(' not in archive_menus
        and 'contentDescription = "$title: $selectedLabel"' in archive_menus
        and 'BadgedBox(' in archive_menus
        and all('icon = R.drawable.ic_fatline_' + name in history_screen
                for name in ('group', 'tag', 'system', 'favorite', 'sort', 'talkgroup')),
        'History filter selectors must be compact icon-only buttons with visible selection badges')
require('"Talkgroups: All"' in history_screen
        and 'talkgroups selected' in history_screen
        and 'archiveTalkgroupMenuExpanded = true' in history_screen
        and 'Badge { Text(archiveTalkgroupRefs.size.toString()) }' in history_screen,
        'talkgroup multiselect must remain reachable with an icon and readable selection count')
require('FilledIconButton(' in history_actions and 'OutlinedIconButton(' in history_actions
        and 'contentDescription = label' in history_actions
        and 'Text(' not in history_actions,
        'History action controls must use icons only with accessible descriptions')
require('LazyRow(' not in history_screen.split('archiveFilterLabel?.let { label ->', 1)[0]
        and all('R.drawable.ic_fatline_' + name in history_screen
                for name in ('refresh', 'talkgroup', 'system', 'more_calls', 'search', 'clear'))
        and 'requestHistory(server.profile.id, false)' in history_screen
        and 'requestHistoryFiltered(' in history_screen
        and 'onClick = { historyQuery = "" }' in history_screen,
        'archive toolbar, search and pagination must stay functional as compact icon actions')
require('OutlinedButton(' not in history_call_row
        and all('R.drawable.ic_fatline_' + name in history_call_row
                for name in ('continue', 'replay', 'download'))
        and 'onClick = onDownload' in history_call_row
        and 'onClick = onReplay' in history_call_row
        and 'onClick = it' in history_call_row
        and 'Modifier.fillMaxWidth()' in history_call_row,
        'History call rows must use right-aligned, icon-only Continue/Replay/Download actions')
require(all((ROOT / ('app/src/main/res/drawable/ic_fatline_' + name + '.xml')).exists()
            for name in ('refresh', 'talkgroup', 'system', 'more_calls', 'search', 'continue',
                         'download', 'group', 'tag', 'favorite', 'sort')),
        'new History icon vector resources are missing')
require('recentCalls' in models and 'session.state.recentCalls' in repo, 'dedicated live recent-call state missing')
require('server.recentCalls' in ui and 'server.history.take(10)' not in ui, 'scanner recent list must not reuse archive history')
channels_tree = ui.split('private fun ChannelsScreen(', 1)[1].split('private data class ArchiveMenuChoice(', 1)[0]
require('expandedSystems by remember(selectedProfileId)' in channels_tree and 'expandedTags by remember(selectedProfileId)' in channels_tree, 'tree expansion state must reset per scanner')
require('if (systemExpanded) {' in channels_tree and 'if (tagExpanded) {' in channels_tree, 'system/tag children must be collapsible')
require('normalizedQuery.isNotEmpty() ||' in channels_tree and 'tagKey in expandedTags' in channels_tree, 'channel search must reveal matching tree branches')
require(channels_tree.count('enabled = normalizedQuery.isEmpty()') >= 2, 'search-revealed tree headers must not offer inert collapse actions')
require('"Expand system"' in channels_tree and '"Collapse system"' in channels_tree and '"Expand tag"' in channels_tree and '"Collapse tag"' in channels_tree, 'tree nodes must expose accessible expand/collapse controls')
require('viewModel.setTalkgroup(' in channels_tree and 'viewModel.setChannels(' in channels_tree and 'setScanListChannel(' in channels_tree, 'tree must retain channel and scan list controls')

require('durationSeconds = payload.optDouble("duration")' in repo, 'live call duration metadata must be preserved')
require('UnitAlias' in models and 'parseUnits' in protocol, 'ThinLine unit alias parsing missing')
require('resolveCallSources' in repo and 'formatUnitDisplay' in repo, 'multi-source unit alias resolution missing')
require('sources: List<CallSource>' in models and 'sourceDisplay' in models, 'ordered call source model/display missing')
require('call.sourceDisplay' in ui and 'call.sources.size > 1' in ui, 'scanner must display all resolved call sources')
require('systemsParseExactAndRangeUnitAliases' in protocol_tests, 'unit alias regression test missing')
require('callSourcesAreOrderedDeduplicatedAndAliasAware' in (ROOT / 'app/src/test/java/dev/scanrelay/app/net/ScannerRepositoryTest.kt').read_text(), 'multi-source parser regression test missing')
require('alertsLoading' in models and 'alertsError' in models and 'stableKey' in models, 'persisted rich-alert state missing')
require('refreshAlerts' in repo and '/api/alerts' in repo and 'parseServerAlerts' in repo, 'server alert-history loading missing')
require('postConnectionLoss' in (ROOT / 'app/src/main/java/dev/scanrelay/app/alerts/AlertNotifier.kt').read_text(), 'connection-loss notification helper missing')
require('RingtoneManager.ACTION_RINGTONE_PICKER' in ui and 'Choose sound' in ui and 'System default' in ui, 'per-scanner alert sound picker missing')
require('ALERT_CHANNEL_PREFIX' in alert_notifier and 'AlertSoundPreferences.get' in alert_notifier, 'profile alert sound channel routing missing')
require('SILENT' in alert_sound and 'displayName' in alert_sound, 'alert sound preference persistence missing')
require('getDisconnect' in alert_sound and 'setDisconnect' in alert_sound and 'displayDisconnectName' in alert_sound, 'disconnect sound preference persistence missing')
require('CONNECTION_CHANNEL_PREFIX' in alert_notifier and 'AlertSoundPreferences.getDisconnect' in alert_notifier, 'disconnect sound channel routing missing')
require('LEGACY_ALERT_CHANNEL_ID' in alert_notifier and 'LEGACY_CONNECTION_CHANNEL_ID' in alert_notifier, 'legacy notification channel cleanup missing')
require('"Disconnect sound: "' in ui and '"FatLine disconnect sound"' in ui, 'disconnect sound picker UI missing')
require('hasConnected' in repo and 'disconnectNotified' in repo and 'notifyConnectionLoss' in repo, 'one-shot disconnect notification state missing')
require('ThinLineProtocol.INCIDENT -> scheduleAlertRefresh(session)' in repo, 'incident updates must refresh persisted alert details')
require('AppTab.Alerts' in ui and 'Search alerts' in ui and 'Replay call' in ui, 'dedicated searchable Alerts screen missing')
require('incidentMapUri' in ui and '"Open map"' in ui and 'Intent.ACTION_VIEW' in ui, 'incident alert map action missing')
require('incidentMappingEnabled' in models and 'incidentMappingEnabled = incidentMappingEnabled' in repo, 'incident mapping master switch state missing')
require('mappingEnabled = server.incidentMappingEnabled' in ui and 'if (!mappingEnabled) return null' in ui, 'incident map UI must honor server master switch')
require('serverMappingSwitchHidesMapAction' in (ROOT / 'app/src/test/java/dev/scanrelay/app/ui/IncidentMapUriTest.kt').read_text(), 'incident mapping switch regression test missing')
require((ROOT / 'app/src/test/java/dev/scanrelay/app/ui/IncidentMapUriTest.kt').exists(), 'incident map URI regression tests missing')
require('refreshAlerts' in viewmodel, 'alert refresh view-model bridge missing')
require('richAlertHistoryParsesAndSortsServerFields' in (ROOT / 'app/src/test/java/dev/scanrelay/app/net/ScannerRepositoryTest.kt').read_text(), 'rich alert parser regression test missing')
require('parseRealtimeAlert' in repo and 'liveAlertPrefersTitleMessageAndCarriesTimestamp' in (ROOT / 'app/src/test/java/dev/scanrelay/app/net/ScannerRepositoryTest.kt').read_text(), 'live ALT payload regression tests missing')
require('SystemHealthAlert' in models and 'systemAlertsLoading' in models and 'canViewSystemAlerts' in models, 'system health alert state missing')
require('refreshSystemAlerts' in repo and 'dismissSystemAlert' in repo and '/api/system-alerts' in repo, 'system health alert repository controls missing')
require('refreshSystemAlerts' in viewmodel and 'dismissSystemAlert' in viewmodel, 'system health alert view-model bridge missing')
require('System health alerts' in ui and 'No active system alerts.' in ui and 'Dismiss' in ui, 'system health alert UI missing')
require('systemAlertsParseAndSortVisibleServerAlerts' in (ROOT / 'app/src/test/java/dev/scanrelay/app/net/ScannerRepositoryTest.kt').read_text(), 'system alert parser regression test missing')
require('systemAlertUrlsUseCanonicalEndpoints' in (ROOT / 'app/src/test/java/dev/scanrelay/app/net/ScannerRepositoryTest.kt').read_text(), 'system alert URL regression test missing')
require('AlertPreference' in models and 'alertPreferencesSaving' in models, 'server-backed alert preference state missing')
require('refreshAlertPreferences' in repo and 'setAlertPreference' in repo and '/api/alerts/preferences' in repo, 'alert preference repository controls missing')
require('.put(serializeAlertPreferences' in repo, 'alert preferences must use ThinLine PUT endpoint')
require('val canonical = fetchAlertPreferences(session)' in repo, 'alert preference saves must reload canonical server state')
require('toneSetSounds' in repo and 'toneSetPagerAlerts' in repo and 'notificationSound' in repo, 'alert preference writes must preserve advanced server fields')
require('refreshAlertPreferences' in viewmodel and 'setAlertPreference' in viewmodel, 'alert preference view-model bridge missing')
require('Alert settings' in ui and 'Search alert channels' in ui and 'Keyword off' in ui, 'per-talkgroup alert preference UI missing')
require('alertPreferencesRoundTripPreservesServerExtras' in (ROOT / 'app/src/test/java/dev/scanrelay/app/net/ScannerRepositoryTest.kt').read_text(), 'alert preference round-trip regression test missing')
require('newAlertPreferenceUsesServerCompatibleDefaults' in (ROOT / 'app/src/test/java/dev/scanrelay/app/net/ScannerRepositoryTest.kt').read_text(), 'new alert preference default regression test missing')
require('AlertKeywordList' in models and 'alertKeywordListsLoading' in models, 'server keyword-list state missing')
require('refreshAlertKeywordLists' in repo and '/api/keyword-lists' in repo and 'parseAlertKeywordLists' in repo, 'server keyword-list loading missing')
require('setAlertKeywordLists' in repo and 'keywordListIds = normalizedIds' in repo, 'per-talkgroup keyword-list selection missing')
require('refreshAlertKeywordLists' in viewmodel and 'setAlertKeywordLists' in viewmodel, 'keyword-list view-model bridge missing')
require('Loading keyword lists' in ui and 'No server keyword lists are available' in ui and 'setAlertKeywordLists' in ui, 'keyword-list selection UI missing')
require('keywordListsParseLabelsDescriptionsAndKeywords' in (ROOT / 'app/src/test/java/dev/scanrelay/app/net/ScannerRepositoryTest.kt').read_text(), 'keyword-list parser regression test missing')
require('alertKeywordListsSaving' in models, 'keyword-list mutation state missing')
require('createAlertKeywordList' in repo and 'updateAlertKeywordList' in repo and 'deleteAlertKeywordList' in repo, 'keyword-list CRUD repository controls missing')
require('.post(body)' in repo and '.put(body)' in repo and '.delete()' in repo, 'keyword-list CRUD HTTP methods missing')
require('createAlertKeywordList' in viewmodel and 'updateAlertKeywordList' in viewmodel and 'deleteAlertKeywordList' in viewmodel, 'keyword-list CRUD view-model bridge missing')
require('Manage keyword lists' in ui and 'Create list' in ui and 'Save changes' in ui and 'Delete' in ui, 'keyword-list manager UI missing')
require('keywordListPayloadTrimsAndDeduplicatesFields' in (ROOT / 'app/src/test/java/dev/scanrelay/app/net/ScannerRepositoryTest.kt').read_text(), 'keyword-list payload regression test missing')
require('keywordListUrlsUseCanonicalEndpoint' in (ROOT / 'app/src/test/java/dev/scanrelay/app/net/ScannerRepositoryTest.kt').read_text(), 'keyword-list URL regression test missing')
require('setAlertKeywords' in repo and 'normalizeAlertKeywords' in repo, 'custom per-talkgroup keyword persistence missing')
require('setAlertKeywords' in viewmodel, 'custom keyword view-model bridge missing')
require('Custom keywords' in ui and 'Save keywords' in ui and 'Separate phrases with commas or new lines' in ui, 'custom keyword editor missing')
require('customAlertKeywordsNormalizeCommaNewlineWhitespaceAndDuplicates' in (ROOT / 'app/src/test/java/dev/scanrelay/app/net/ScannerRepositoryTest.kt').read_text(), 'custom keyword normalization regression test missing')
require('callDownloadUsesDedicatedAuthenticatedAudioEndpoint' in (ROOT / 'app/src/test/java/dev/scanrelay/app/net/ScannerRepositoryTest.kt').read_text(), 'call download URL regression test missing')
require('callDownloadFilenameUsesServerNameAndSanitizesPaths' in (ROOT / 'app/src/test/java/dev/scanrelay/app/net/ScannerRepositoryTest.kt').read_text(), 'call download filename regression test missing')
require('PLAY_FLAG = "p"' in protocol and 'playbackCall' in protocol, 'CAL playback flag support missing')
require('requestPlaybackCall' in socket and 'session.socket?.requestPlaybackCall(callId)' in repo, 'archive replay must send CAL play flag')
require('callFlag == ThinLineProtocol.PLAY_FLAG' in repo, 'CAL play responses must be classified as replay')
require('playbackCallUsesPublicClientPlayFlag' in protocol_tests, 'CAL playback flag regression test missing')
require('continueHistory' in repo and 'continueReplayQueue' in repo and 'requestNextContinueReplay' in repo, 'continuous archive playback engine missing')
require('PlaybackQueuePolicy.removalIndex' in service and 'trimQueueForIncomingCall(liveFeed)' in service, 'playback queue trimming must be media-type aware')
require((ROOT / 'app/src/main/java/dev/scanrelay/app/playback/PlaybackQueuePolicy.kt').exists(), 'playback queue policy missing')
require('liveOverflowRemovesOnlyLiveItems' in (ROOT / 'app/src/test/java/dev/scanrelay/app/playback/PlaybackQueuePolicyTest.kt').read_text(), 'live queue isolation regression test missing')
require('replayOverflowDoesNotDiscardQueuedLiveTraffic' in (ROOT / 'app/src/test/java/dev/scanrelay/app/playback/PlaybackQueuePolicyTest.kt').read_text(), 'replay queue isolation regression test missing')
require('liveOverflowKeepsCurrentPlayingCallSelected' in (ROOT / 'app/src/test/java/dev/scanrelay/app/playback/PlaybackQueuePolicyTest.kt').read_text(), 'live queue overflow must preserve current playback regression missing')
require('continuationCallIds' in repo and 'historyContinueStartsAtSelectedAndMovesTowardNewest' in (ROOT / 'app/src/test/java/dev/scanrelay/app/net/ScannerRepositoryTest.kt').read_text(), 'archive Continue ordering regression missing')
require('continueHistory' in viewmodel and 'label = "Continue history playback from this call"' in ui, 'History Continue UI/view-model bridge missing')
require('AlertToneSet' in models and 'toneDetectionEnabled' in models and 'toneSets' in models, 'talkgroup tone-set metadata missing')
require('parseToneSets' in protocol and 'toneDetectionEnabled = node.optBoolean' in protocol, 'tone-set config parsing missing')
require('setAlertToneSets' in repo and 'normalizeAlertToneSetIds' in repo, 'per-talkgroup tone-set persistence missing')
require('setAlertToneSets' in viewmodel, 'tone-set view-model bridge missing')
require('uiAccentColor' in models and 'options?.optString("uiAccentColor")' in repo, 'server accent configuration missing')
require('userUiAccentColor' in models and 'payload.optJSONObject("userSettings")' in repo, 'per-user accent override configuration missing')
require('resolvedUiAccentColor' in ui and 'server.userUiAccentColor' in ui, 'user accent must override site accent')
require('userAccentOverridesSiteAccent' in (ROOT / 'app/src/test/java/dev/scanrelay/app/ui/UiAccentTest.kt').read_text(), 'user accent precedence regression test missing')
require('blankUserAccentFallsBackToSiteAccent' in (ROOT / 'app/src/test/java/dev/scanrelay/app/ui/UiAccentTest.kt').read_text(), 'blank user accent fallback regression test missing')
require('uiAccentRgb' in ui and 'darkColorScheme(primary = color, secondary = color, tertiary = color)' in ui, 'server accent theme application missing')
require('normalizesThreeAndSixDigitHex' in (ROOT / 'app/src/test/java/dev/scanrelay/app/ui/UiAccentTest.kt').read_text(), 'accent normalization regression test missing')
require('invalidServerAccentFallsBackToThinLineDefault' in (ROOT / 'app/src/test/java/dev/scanrelay/app/ui/UiAccentTest.kt').read_text(), 'accent fallback regression test missing')
require('time12hFormat' in models and 'payload.optBoolean("time12hFormat", false)' in repo, 'server time-format configuration missing')
require('formatServerDateTime' in ui and 'time12hFormat = server.time12hFormat' in ui, 'server-formatted call and alert times missing')
require('formatsArchiveTimestampIn12HourMode' in (ROOT / 'app/src/test/java/dev/scanrelay/app/ui/ServerDateTimeTest.kt').read_text(), '12-hour timestamp regression test missing')
require('formatsArchiveTimestampIn24HourMode' in (ROOT / 'app/src/test/java/dev/scanrelay/app/ui/ServerDateTimeTest.kt').read_text(), '24-hour timestamp regression test missing')
require('showListenersCount' in models and 'listenerCount' in models, 'listener count state missing')
require('ThinLineProtocol.LISTENER_COUNT' in repo and 'parseListenerCount' in protocol, 'listener count protocol handling missing')
require('"Listeners " + server.listenerCount' in ui, 'listener count UI missing')
require('listenerCountAcceptsNumericAndStringPayloads' in protocol_tests, 'listener count regression test missing')
require('parsePinSet' in protocol and 'ThinLineProtocol.PIN_SET -> syncServerPin' in repo, 'server PIN synchronization handling missing')
require('@Volatile var profile: ServerProfile' in repo and 'profileCredentialUpdates' in repo, 'live session PIN replacement/event missing')
require('updatePin' in (ROOT / 'app/src/main/java/dev/scanrelay/app/data/ProfileStore.kt').read_text(), 'secure profile PIN refresh missing')
require('profileCredentialUpdates.collect' in viewmodel, 'profile list must refresh after server PIN synchronization')
require('pinSetAcceptsOnlyNonBlankStringPayloads' in protocol_tests, 'server PIN synchronization regression test missing')
require('Tones all' in ui and 'Leave all unselected to alert on every tone set.' in ui and 'Tone unavailable' in ui, 'tone-set selection UI missing')
require('systemsParseToneDetectionAndToneSets' in (ROOT / 'app/src/test/java/dev/scanrelay/app/net/ThinLineProtocolTest.kt').read_text(), 'tone-set config regression test missing')
require('alertToneSetIdsTrimDropBlanksAndDeduplicate' in (ROOT / 'app/src/test/java/dev/scanrelay/app/net/ScannerRepositoryTest.kt').read_text(), 'tone-set selection normalization regression test missing')
require('PasswordVisualTransformation' in ui, 'PIN field must be visually masked')
require('/api/cm-auth/login' not in viewmodel and '/api/cm-auth/session' not in viewmodel, 'central account credentials must not be sent from FatLine')
require('LazyRow' in ui, 'profile selector should remain scrollable with many servers')
require(re.search(r'tg\.displayName,\s*maxLines\s*=\s*1,\s*overflow\s*=\s*TextOverflow\.Ellipsis', ui) is not None, 'channel names must ellipsize instead of wrapping into narrow vertical strips')
require(re.search(r'Text\(\s*"SYSTEM".*?system\.label', ui, re.S) is not None, 'system sections must be explicitly labeled')
require(re.search(r'Text\(\s*"CHANNEL".*?tg\.displayName,.*?LazyRow\(\s*modifier\s*=\s*Modifier\.fillMaxWidth\(\),\s*horizontalArrangement\s*=\s*Arrangement\.spacedBy\(6\.dp\)', ui, re.S) is not None, 'channel rows must be labeled and their actions must scroll independently')

alert_dismissal = (ROOT / 'app/src/main/java/dev/scanrelay/app/alerts/AlertDismissalStore.kt').read_text()
alert_dismissal_tests = (ROOT / 'app/src/test/java/dev/scanrelay/app/alerts/AlertDismissalPolicyTest.kt').read_text()
scanner_alert_preview = ui.split('if (server.alerts.isNotEmpty()) {', 1)[1].split('// Incoming or queued traffic', 1)[0]
alerts_list = ui.split('itemsIndexed(\n                    visibleAlerts,', 1)[1].split('        item { Spacer(Modifier.height(16.dp)) }', 1)[0]
alert_card = ui.split('private fun AlertCard(', 1)[1].split('internal fun incidentMapUri(', 1)[0]
require('viewModel.playNow(alert.profileId, callId)' in scanner_alert_preview
        and 'onDelete = { viewModel.dismissAlert(alert) }' in scanner_alert_preview,
        'scanner home alerts must have immediate playback and delete')
require('viewModel.playNow(alert.profileId, callId)' in alerts_list
        and 'onDelete = { viewModel.dismissAlert(alert) }' in alerts_list,
        'full Alerts list must have the same playback and delete actions')
require('contentDescription = "Play alert recording"' in alert_card
        and 'contentDescription = "Delete alert"' in alert_card
        and 'AlertDialog(' in alert_card and 'server\'s copy is not deleted' in alert_card
        and (ROOT / 'app/src/main/res/drawable/ic_fatline_delete.xml').exists(),
        'alert actions need accessible icons and deletion confirmation')
require('fun dismissAlert(alert: ScannerAlert)' in repo and 'store.dismiss(alert)' in repo
        and 'alertDismissalStore?.visible(session.profile.id' in repo
        and 'alertDismissalStore?.visible(profile.id' in repo
        and 'fun dismissAlert(alert:' in viewmodel
        and 'AlertDismissalStore(getApplication()).clearProfile(profileId)' in viewmodel,
        'alert local deletion must persist across refresh/reconnect and be cleared with its profile')
require('getSharedPreferences("fatline_dismissed_alerts"' in alert_dismissal
        and 'fun retain(previous:' in alert_dismissal and 'const val LIMIT = 2000' in alert_dismissal
        and 'deletedAlertStaysHiddenAfterHistoryIsReloaded' in alert_dismissal_tests
        and 'deletionDoesNotHideOtherAlertsOrAnotherScanner' in alert_dismissal_tests
        and 'tombstonesAreBoundedAndRedismissedAlertsMoveToNewest' in alert_dismissal_tests,
        'dismissal store and regression tests missing')

probe = (ROOT / 'app/src/main/java/dev/scanrelay/app/playback/PerformanceProbe.kt').read_text()
probe_tests = (ROOT / 'app/src/test/java/dev/scanrelay/app/playback/PerformanceProbeTest.kt').read_text()
require('object PerformanceReader' in probe
        and 'Process.getElapsedCpuTime()' in probe
        and 'Debug.getPss()' in probe
        and 'TrafficStats.getUidRxBytes' in probe
        and 'BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER' in probe
        and 'BatteryManager.BATTERY_PROPERTY_CAPACITY' in probe
        and 'class PerformanceProbe(' in probe,
        'performance probe must capture CPU, PSS, UID network and optional battery counters')
require('private var performanceJob: Job? = null' in service
        and 'delay(10_000L)' in service
        and 'ACTION_START_PERFORMANCE_CAPTURE -> startPerformanceProbe(' in service
        and 'ACTION_STOP_PERFORMANCE_CAPTURE -> stopPerformanceProbe()' in service
        and 'performanceJob?.cancel()' in service
        and 'stopPerformanceProbe()' in service.split('override fun onDestroy()', 1)[1],
        'performance sampling must run only while enabled and stop on service destruction')
require('Text("Performance probe"' in ui
        and 'ScannerService.startPerformanceCapture(context, performanceScenario)' in ui
        and 'ScannerService.stopPerformanceCapture(context)' in ui
        and 'ClipData.newPlainText(' in ui
        and 'No data is uploaded.' in ui,
        'performance measurement needs opt-in, stop and local copy controls')
require('reportSeparatesCpuPssNetworkAndWholePhoneBattery' in probe_tests
        and 'unsupportedCountersAndResetNetworkCountersStayUnavailable' in probe_tests
        and 'pluggingInDisablesBatteryDischargeClaims' in probe_tests
        and 'invalidOutOfOrderSamplesAreIgnoredAndCpuPercentMayExceed100' in probe_tests
        and 'zeroDurationDoesNotDivideByZero' in probe_tests,
        'performance counters require accuracy, unsupported and charging regression coverage')

# Alert badge snapshot reuse and bounded references during normal playback.
require('class AlertKeySnapshotCache' in dot_policy
        and 'cached[profileId]?.alerts === server.alerts' in dot_policy
        and 'cached.clear()' in dot_policy
        and 'val alertKeysCache = remember { AlertKeySnapshotCache() }' in ui
        and 'val currentAlertKeys = alertKeysCache.snapshot(scanner.servers)' in ui
        and 'alertKeysAreReusedAcrossUnrelatedScannerStateUpdates' in dot_tests
        and 'changedAlertListsInvalidateOnlyTheirProfile' in dot_tests
        and 'disconnectedServersReleaseAlertSnapshotsAndCanReconnectCleanly' in dot_tests,
        'alert badge keys should reuse unchanged alert snapshots and release disconnected servers')

# Pending-call records must not survive when Android rejects both ways of
# launching the playback service. Working delivery paths remain unchanged.
require('internal object PendingAudioDispatchPolicy' in queue_policy
        and 'tryForegroundStart' in queue_policy
        and 'onUnrecoverableFailure()' in queue_policy
        and 'PendingAudioDispatchPolicy.dispatch(' in service
        and 'onUnrecoverableFailure = { pendingCalls.remove(token) }' in service
        and 'pendingAudioLaunchKeepsCallWhenNormalServiceStartWorks' in queue_tests
        and 'pendingAudioLaunchKeepsCallWhenForegroundFallbackWorks' in queue_tests
        and 'failedServiceAndFallbackCannotLeaveOrphanedPendingCall' in queue_tests,
        'failed Android service launches must not retain pending call metadata indefinitely')

require('AudioCacheNamePolicy.extension(audioName, mime)' in repo
        and 'AudioCacheNamePolicy.safeProfile(profileId)' in repo
        and 'internal object AudioCacheNamePolicy' in repo
        and 'audioCacheFilenamesPreserveOldAsciiSanitizingRules' in aggregation_tests
        and 'audioCacheExtensionsPreserveOldFilenameAndMimeFallbacks' in aggregation_tests,
        'per-call audio cache filenames must stay safe without compiling regular expressions')

for path in ROOT.glob('app/src/main/java/**/*.kt'):
    text = path.read_text()
    stripped = re.sub(r'""".*?"""', '', text, flags=re.S)
    stripped = re.sub(r'"(?:\\.|[^"\\])*"', '', stripped)
    stripped = re.sub(r'//.*', '', stripped)
    balance = 0
    for ch in stripped:
        if ch == '{': balance += 1
        elif ch == '}': balance -= 1
        if balance < 0:
            errors.append(f'unmatched closing brace in {path.relative_to(ROOT)}')
            break
    if balance != 0:
        errors.append(f'brace imbalance {balance} in {path.relative_to(ROOT)}')

if errors:
    print('VALIDATION FAILED')
    for e in errors: print(' -', e)
    sys.exit(1)

print('VALIDATION PASSED')
print(f'Kotlin source files: {len(list(ROOT.glob("app/src/main/java/**/*.kt")))}')
print('Features: ThinLine wire parity / multi-server / resilient network handoff / pause-live / system+TG hold / batch controls / relay crypto+rotation / ordered calls / paged history / alerts / Media3 Auto')
