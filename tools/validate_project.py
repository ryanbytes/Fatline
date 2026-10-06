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
    'app/src/main/java/dev/scanrelay/app/ui/FatLineApp.kt',
    'app/src/test/java/dev/scanrelay/app/net/ThinLineProtocolTest.kt',
    'app/src/test/java/dev/scanrelay/app/net/ThinLineSocketTest.kt',
    'app/src/test/java/dev/scanrelay/app/net/AudioCryptoTest.kt',
    'app/src/test/java/dev/scanrelay/app/net/ScannerRepositoryTest.kt',
    'app/src/test/java/dev/scanrelay/app/data/ChannelStoreTest.kt',
    'app/src/test/java/dev/scanrelay/app/playback/PlaybackQueuePolicyTest.kt',
]
for rel in required:
    require((ROOT / rel).is_file(), f'missing {rel}')

app_gradle = (ROOT / 'app/build.gradle.kts').read_text()
root_gradle = (ROOT / 'build.gradle.kts').read_text()
manifest_text = (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
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
require('GITHUB_RUN_NUMBER' in app_gradle and '10_000 + it' in app_gradle, 'monotonic CI version code missing')
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
require('downloadCall' in viewmodel and 'onDownload' in ui and 'Text("Download")' in ui, 'call download UI bridge missing')
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
require('ConcurrentHashMap' in repo and 'sessions' in repo, 'multi-server session map missing')
require('CallKey' in models, 'per-server call identity missing')
require('pendingEncrypted' in repo and '20' in repo, 'bounded encrypted-call buffering missing')
require('requestHistory' in repo and 'LIST_CALL' in protocol, 'server history support missing')
require('callMutex = Mutex()' in repo and 'callMutex.withLock' in repo, 'per-server call ordering mutex missing')
require('keyHttpClient = OkHttpClient()' in repo, 'shared encrypted-audio key exchange client missing')
require('refreshing audio key' in repo and 'bufferEncryptedCallLocked' in repo, 'encrypted-audio key rotation recovery missing')
require('ScannerService.removeProfile' not in repo and 'ScannerService::stopAudio' not in repo, 'repository must not control service disconnect lifecycle')

# Network handoff / connection-loss hardening.
require('pingInterval(15' in socket and 'terminalDelivered' in socket and 'fun abort()' in socket, 'fast terminal socket handling missing')
require('registerDefaultNetworkCallback' in service, 'Android default-network callback missing')
require('network.networkHandle' in service and 'NETWORK_LOSS_GRACE_MS = 650L' in service, 'network handoff identity/grace handling missing')
require('networkUnavailable()' in repo and 'networkChanged(' in repo, 'network-aware repository recovery missing')
require('socketGeneration' in repo and 'isCurrent(session, generation)' in repo, 'stale socket callback suppression missing')
require('handshakeJob' in repo and 'armHandshakeWatchdog' in repo and 'Handshake stalled; reconnecting' in repo, 'CFG/auth handshake watchdog missing')
require('Waiting for network' in repo, 'offline retry suspension state missing')
require('30_000L' in repo, 'bounded reconnect backoff missing')

# Background playback / Auto / service lifecycle.
require('MediaLibraryService' in service and 'MediaLibrarySession' in service and 'ExoPlayer' in service, 'Media3 Android Auto/media service missing')
require('startForeground' in service and 'START_STICKY' in service, 'foreground restart behavior missing')
require('suppressRepositoryServiceCallbacks' in service and 'withRepositoryServiceCallbacksSuppressed' in service, 'disconnect service callback suppression missing')
require('validIds' in service and 'persistActiveProfiles' in service and 'stopIfIdle' in service, 'stale-profile restart cleanup missing')
require('MAX_QUEUE_ITEMS = 30' in service and 'trimQueueForIncomingCall' in service, 'bounded playback queue handling missing')
require('serverItem' in service and 'setIsBrowsable(true).setIsPlayable(true)' in service, 'Android Auto server connect item missing')

# User-visible ThinLine parity / enhancements.
require('setMany' in channel_store and 'setSystemEnabled' in repo, 'batched system-level channel update missing')
require('knownKey' in channel_store and 'reconcileChannelSelection' in channel_store, 'newly scoped channel tracking missing')
require('autoEnableNewTalkgroups' in repo and 'channelStore?.apply' in repo, 'server auto-enable-new-talkgroups policy missing')
require('newlyScopedChannelsTurnOnWhenServerPolicyIsEnabled' in (ROOT / 'app/src/test/java/dev/scanrelay/app/data/ChannelStoreTest.kt').read_text(), 'auto-enable regression test missing')
require('baselineMigrationDoesNotBulkEnableCurrentDisabledChannels' in (ROOT / 'app/src/test/java/dev/scanrelay/app/data/ChannelStoreTest.kt').read_text(), 'channel baseline migration regression test missing')
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
require('Search loaded history' in ui and 'historyQuery' in ui, 'loaded-history search control missing')
require('historySystemRef' in models and 'historyTalkgroupRef' in models, 'active archive server-filter state missing')
require('activeSystemRef = session.state.historySystemRef' in repo and 'activeTalkgroupRef = session.state.historyTalkgroupRef' in repo, 'archive pagination must reuse the active server filter')
require('matchesHistoryFilter(session.state, call)' in repo, 'live calls must respect the active archive filter')
require('liveCallsRespectActiveHistoryFilter' in (ROOT / 'app/src/test/java/dev/scanrelay/app/net/ScannerRepositoryTest.kt').read_text(), 'archive-filter live-call regression test missing')
require('Current TG' in ui and 'Current SYS' in ui and 'All archive' in ui, 'current-channel/system archive filter controls missing')
require('talkgroupRef: Long? = null' in viewmodel and 'ScannerRepository.requestHistory(profileId, reset, systemRef, talkgroupRef)' in viewmodel, 'archive filter bridge missing from view model')
require('call.sourceDisplay?.lowercase()?.contains(normalizedHistoryQuery)' in ui, 'history search must include unit/talker display')
require('call.transcript?.lowercase()?.contains(normalizedHistoryQuery)' in ui, 'history search must include transcripts')
require('recentCalls' in models and 'session.state.recentCalls' in repo, 'dedicated live recent-call state missing')
require('server.recentCalls' in ui and 'server.history.take(10)' not in ui, 'scanner recent list must not reuse archive history')
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
require('continuationCallIds' in repo and 'historyContinueStartsAtSelectedAndMovesTowardNewest' in (ROOT / 'app/src/test/java/dev/scanrelay/app/net/ScannerRepositoryTest.kt').read_text(), 'archive Continue ordering regression missing')
require('continueHistory' in viewmodel and 'Text("Continue")' in ui, 'History Continue UI/view-model bridge missing')
require('AlertToneSet' in models and 'toneDetectionEnabled' in models and 'toneSets' in models, 'talkgroup tone-set metadata missing')
require('parseToneSets' in protocol and 'toneDetectionEnabled = node.optBoolean' in protocol, 'tone-set config parsing missing')
require('setAlertToneSets' in repo and 'normalizeAlertToneSetIds' in repo, 'per-talkgroup tone-set persistence missing')
require('setAlertToneSets' in viewmodel, 'tone-set view-model bridge missing')
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
require('LazyRow' in ui, 'profile selector should remain scrollable with many servers')

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
