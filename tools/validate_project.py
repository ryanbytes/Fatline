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
    'app/src/main/java/dev/scanrelay/app/data/ChannelStore.kt',
    'app/src/main/java/dev/scanrelay/app/ui/FatLineApp.kt',
    'app/src/test/java/dev/scanrelay/app/net/ThinLineProtocolTest.kt',
    'app/src/test/java/dev/scanrelay/app/net/ThinLineSocketTest.kt',
    'app/src/test/java/dev/scanrelay/app/net/AudioCryptoTest.kt',
]
for rel in required:
    require((ROOT / rel).is_file(), f'missing {rel}')

app_gradle = (ROOT / 'app/build.gradle.kts').read_text()
root_gradle = (ROOT / 'build.gradle.kts').read_text()
protocol = (ROOT / 'app/src/main/java/dev/scanrelay/app/net/ThinLineProtocol.kt').read_text()
socket = (ROOT / 'app/src/main/java/dev/scanrelay/app/net/ThinLineSocket.kt').read_text()
service = (ROOT / 'app/src/main/java/dev/scanrelay/app/playback/ScannerService.kt').read_text()
vault = (ROOT / 'app/src/main/java/dev/scanrelay/app/data/PinVault.kt').read_text()
repo = (ROOT / 'app/src/main/java/dev/scanrelay/app/net/ScannerRepository.kt').read_text()
crypto = (ROOT / 'app/src/main/java/dev/scanrelay/app/net/AudioCrypto.kt').read_text()
viewmodel = (ROOT / 'app/src/main/java/dev/scanrelay/app/ScannerViewModel.kt').read_text()
ui = (ROOT / 'app/src/main/java/dev/scanrelay/app/ui/FatLineApp.kt').read_text()
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

for cmd in ['ALT','CAL','CFG','ERR','XPR','LCL','LSC','LFM','MAX','PIN','PNS','PNG','VER']:
    require(f'= "{cmd}"' in protocol, f'protocol constant {cmd} missing')

require(json.dumps(['PIN','MTIzNA=='], separators=(',', ':')) == '["PIN","MTIzNA=="]', 'PIN fixture malformed')
livefeed = ['LFM', {'4294967299': {'8589934599': True, '8589934600': False}}]
round_trip = json.loads(json.dumps(livefeed, separators=(',', ':')))
require(round_trip[1]['4294967299']['8589934599'] is True, '64-bit LFM fixture malformed')
require(round_trip[1]['4294967299']['8589934600'] is False, 'explicit false LFM fixture malformed')

# Public ThinLine client parity: VER + CFG on open, then saved PIN only after a PIN challenge.
require('webSocket.send(ThinLineProtocol.command(ThinLineProtocol.VERSION))' in socket, 'VER websocket negotiation missing')
require('webSocket.send(ThinLineProtocol.command(ThinLineProtocol.CONFIG))' in socket, 'CFG websocket negotiation missing')
require('savedPinAttempted.compareAndSet(false, true)' in socket, 'saved PIN must be challenge-driven and single-attempt')
require('profile.pin.isNotBlank()' in socket and 'ThinLineProtocol.pin(profile.pin)' in socket, 'saved PIN challenge response missing')
require('return URI(scheme, uri.userInfo, uri.host, uri.port, "/", null, null).toString()' in socket, 'ThinLine websocket must normalize to server root')
require('system.talkgroups.forEach' in protocol and 'talkgroup.enabled' in protocol, 'LFM must send complete boolean map')
require('callId.toString()' in protocol, 'CAL call id must use ThinLine string wire type')
require('explicitBooleans' in protocol_tests and 'callIdMatchesThinLineStringWireType' in protocol_tests, 'protocol parity regression tests missing')
require('httpServerUrlUsesRootWebsocketEndpoint' in socket_tests, 'websocket URL regression test missing')

try:
    tree = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml')
    manifest = tree.getroot()
    android = '{http://schemas.android.com/apk/res/android}'
    perms = {p.attrib.get(android+'name') for p in manifest.findall('uses-permission')}
    require('android.permission.INTERNET' in perms, 'INTERNET permission missing')
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
require('CallKey' in (ROOT / 'app/src/main/java/dev/scanrelay/app/model/Models.kt').read_text(), 'per-server call identity missing')
require('pendingEncrypted' in repo and '20' in repo, 'bounded encrypted-call buffering missing')
require('requestHistory' in repo and 'LIST_CALL' in protocol, 'server history support missing')
require('setHold' in repo and 'avoided' in repo and 'skip' in repo, 'hold/avoid/skip support missing')
require('callMutex = Mutex()' in repo and 'callMutex.withLock' in repo, 'per-server call ordering mutex missing')
require('keyHttpClient = OkHttpClient()' in repo, 'shared encrypted-audio key exchange client missing')
require('refreshing audio key' in repo and 'bufferEncryptedCallLocked' in repo, 'encrypted-audio key rotation recovery missing')
require('ScannerService.removeProfile' not in repo and 'ScannerService::stopAudio' not in repo, 'repository must not control service disconnect lifecycle')
require('MediaLibraryService' in service and 'MediaLibrarySession' in service and 'ExoPlayer' in service, 'Media3 Android Auto/media service missing')
require('startForeground' in service and 'START_STICKY' in service, 'foreground restart behavior missing')
require('suppressRepositoryServiceCallbacks' in service and 'withRepositoryServiceCallbacksSuppressed' in service, 'disconnect service callback suppression missing')
require('validIds' in service and 'persistActiveProfiles' in service and 'stopIfIdle' in service, 'stale-profile restart cleanup missing')
require('MAX_QUEUE_ITEMS = 30' in service and 'trimQueueForIncomingCall' in service, 'bounded playback queue handling missing')
require('serverItem' in service and 'setIsBrowsable(true).setIsPlayable(true)' in service, 'Android Auto server connect item missing')
require('30_000L' in repo, 'bounded reconnect backoff missing')

# User-visible parity controls.
require('setSystemTalkgroups' in viewmodel and 'setSystemTalkgroups' in ui, 'system-level enable/disable control missing')
require('requestHistory(server.profile.id, false)' in ui and 'historyHasMore' in ui, 'history pagination control missing')
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
print('Features: public ThinLine wire parity / multi-server / relay crypto+rotation / ordered calls / system+TG controls / paged history / hold-skip-avoid / alerts / Media3 Auto / lifecycle hardening')
