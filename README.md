# FatLine

Independent Android client for ThinLine Radio and compatible Rdio-style scanner servers.

FatLine is a clean implementation of the public server/client protocol. It does not contain ThinLine mobile-app code, artwork, package names, or branding.

## Current feature set

- Android 8.0+ (`minSdk 26`), compile/target SDK 36
- Kotlin + Jetpack Compose
- Multiple scanner servers connected simultaneously
- Open and PIN-protected servers; PINs stored with Android Keystore AES-GCM and masked in the UI
- ThinLine-compatible WebSocket commands including `VER`, `PIN`, `CFG`, `CAL`, `LCL`, `LFM`, `ALT`, `ERR`, `XPR`, and `MAX`
- Public-client wire parity for root WebSocket URL, `CAL` string IDs, complete `LFM` boolean maps, challenge-driven PIN authentication, and bare `LFM` live-feed pause
- Persistent per-server channel selections and favorites
- New server profiles default to all authorized talkgroups enabled; an intentional **None** selection stays empty
- Per-talkgroup and per-system All/None controls; system bulk changes persist in one batch and emit one live-feed update
- Pause / resume live scanning without losing channel selections
- Talkgroup hold and system hold
- Avoid / unavoid, clear avoids, and skip current audio
- Server archive/history through `LCL`, paged results, and `CAL` replay
- Local scanner-alert notifications and transcript display when supplied by the server
- Per-server ordered call processing while different servers remain concurrent
- ThinLine relay encrypted audio: P-256 ECDH, HKDF-SHA256 (`tlr-audio-key-wrap-v1`), AES-256-GCM
- Bounded encrypted-call buffering while relay key exchange is pending
- Automatic relay-key refresh after encrypted-audio authentication/decrypt failure
- Media3 1.11.0 ExoPlayer background playback and `MediaLibraryService` Android Auto surface
- Android Auto browse tree: profiles → favorited talkgroups; server items are browsable/playable and selecting a favorite sets a talkgroup hold
- Foreground-service restore cleanup for deleted/stale profiles and bounded playback queue handling

## Connection-loss and network-switch recovery

FatLine treats an Android route change differently from an ordinary scanner-server outage.

- Watches Android's default network while the foreground scanner service is active.
- Detects default-network identity changes, including ordinary Wi-Fi/cellular/VPN handoffs, and immediately replaces scanner sockets instead of waiting for a long TCP timeout.
- Uses a short loss grace period to tolerate Android callback ordering during a handoff before declaring the device offline.
- Suspends reconnect timers while Android reports no default network, then reconnects immediately when a route returns.
- Gives each socket a generation number; callbacks from an obsolete socket cannot knock down a replacement connection.
- Uses a config/auth handshake watchdog: retry `CFG`, then replace a socket that opened but never completed negotiation.
- Uses 15-second WebSocket pings as a fallback for dead paths that Android does not explicitly report.
- Keeps ordinary server failures on bounded exponential reconnect backoff with small per-profile jitter, avoiding synchronized reconnect storms across multiple servers.
- Preserves selections, favorites, holds, avoids, history, and pause state across reconnects.

## Build

GitHub Actions is the authoritative Android build environment:

```text
.github/workflows/android.yml
```

It installs Android API 36, runs the structural validator and JVM unit tests, builds the debug APK, and uploads the **FatLine-debug** artifact.

Local commands with JDK 21, Android SDK 36, and Gradle 9.4.1:

```bash
python3 tools/validate_project.py
gradle testDebugUnitTest assembleDebug
```

## Security

- Saved PINs are encrypted with per-profile keys in Android Keystore.
- HTTPS/WSS uses normal Android/OkHttp certificate validation; no trust-all or pin-bypass code exists.
- Cleartext HTTP remains allowed for self-hosted LAN scanner deployments and the UI warns when it is used.
- Relay audio master keys are held in memory only, cleared when scanner sessions are removed, and refreshed when encrypted audio indicates a stale key.
- No ad or analytics SDK is included.

## Verification status

The network-hardened Android code has produced a green CI build including structural validation, JVM unit tests, `assembleDebug`, and debug-APK upload. CI proves the project compiles and its deterministic tests pass; real-device interoperability still needs deliberate testing against real ThinLine servers, especially Wi-Fi/cellular/VPN handoffs, encrypted relay deployments, and Android Auto hosts.
