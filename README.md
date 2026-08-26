# FatLine

Independent Android client for ThinLine Radio and compatible Rdio-style scanner servers.

FatLine is a clean implementation of the public server/client protocol. It does not contain ThinLine mobile-app code, artwork, package names, or branding.

## Current 0.2 feature set

- Android 8.0+ (`minSdk 26`), compile/target SDK 37
- Kotlin + Jetpack Compose
- Multiple scanner servers connected simultaneously
- Open and PIN-protected servers; PINs stored with Android Keystore AES-GCM
- ThinLine WebSocket commands including `VER`, `PIN`, `CFG`, `CAL`, `LCL`, `LFM`, `ALT`, `ERR`, `XPR`, and `MAX`
- Persistent per-server channel selections and favorites
- New server profiles default to all authorized talkgroups enabled; an intentional **None** selection stays empty
- Per-server call identity, history, hold, avoid, reconnect state, and audio-encryption key
- Server archive/history through `LCL` with `CAL` retrieval for replay
- Hold / avoid / skip controls
- Local scanner-alert notifications
- ThinLine relay encrypted audio: P-256 ECDH, HKDF-SHA256 (`tlr-audio-key-wrap-v1`), AES-256-GCM
- Bounded encrypted-call buffering while a relay key exchange is pending
- Media3 1.11.0 ExoPlayer playback and `MediaLibraryService` Android Auto surface
- Android Auto browse tree: profiles → favorited talkgroups; selecting a favorite sets a talkgroup hold

## Build

GitHub Actions is the authoritative Android build environment:

```text
.github/workflows/android.yml
```

It installs Android API 37, runs the static validator and unit tests, builds the debug APK, and uploads the **FatLine-debug** artifact.

Local commands with JDK 21, Android SDK 37, and Gradle 9.4.1:

```bash
python3 tools/validate_project.py
gradle testDebugUnitTest assembleDebug
```

## Security

- Saved PINs are encrypted with per-profile keys in Android Keystore.
- HTTPS/WSS uses normal Android/OkHttp certificate validation; no trust-all or pin-bypass code exists.
- Cleartext HTTP remains allowed for self-hosted LAN scanner deployments and the UI warns when it is used.
- Relay audio master keys are held in memory only and zeroed when a scanner session is removed.
- No ad or analytics SDK is included.

## Verification status

Static validation can run without the Android SDK. The Android compile and unit-test result is determined by the repository's `Android CI` workflow; runtime testing with a real ThinLine server and Android Auto host is still required even after CI is green.
