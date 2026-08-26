# FatLine

Independent Android client for ThinLine Radio and compatible Rdio-style scanner servers.

FatLine is a clean implementation of the public server/client protocol. It does not contain ThinLine mobile-app code, artwork, package names, or branding. The ThinLine server repository and its published API/protocol behavior are used only as interoperability references.

## Current 0.2 feature set

- Android 8.0+ (`minSdk 26`), target/compile SDK 37
- Kotlin + Jetpack Compose
- Multiple scanner servers connected simultaneously
- Multiple saved server profiles
- Open and PIN-protected servers
- Saved PIN encryption with Android Keystore AES-256-GCM
- ThinLine JSON-array WebSocket commands including `VER`, `PIN`, `CFG`, `CAL`, `LCL`, `LFM`, `ALT`, `ERR`, `XPR`, and `MAX`
- Persistent per-profile channel selections
- Persistent favorites
- New profiles default to all server-authorized talkgroups enabled; an intentional `None` selection stays empty
- Live call queue with per-server call identity (`profileId + callId`)
- Server archive/history via `LCL`, with `CAL` retrieval for playback
- Hold, avoid, and skip controls
- Transcript display
- Server alerts plus local Android alert notifications
- Bounded independent reconnect backoff per server
- Foreground scanner service that restores all active server connections
- Media3 1.11.0 / ExoPlayer playback
- Media3 `MediaLibraryService` surface for Android Auto
- Android Auto declaration and browser compatibility interface
- Favorites exposed as the initial Android Auto browse surface

## ThinLine encrypted audio

FatLine implements the current published ThinLine relay audio-key scheme:

1. Generate an ephemeral P-256 ECDH key pair.
2. POST the uncompressed public point to `/api/audio/key-exchange` with the client bearer token supplied in server `CFG`.
3. Derive the wrapping key using HKDF-SHA256 with info `tlr-audio-key-wrap-v1`.
4. AES-256-GCM decrypt the relay-wrapped 32-byte master key.
5. Decrypt each `EncryptedBuffer` call, whose wire audio is Base64 `nonce || ciphertext`.

Encrypted calls received while the key exchange is still running are buffered (bounded to 20 calls) and processed once the key is available.

## Multi-server behavior

Each active profile owns independent:

- WebSocket
- reconnect state/backoff
- server configuration and selected talkgroups
- encryption master key
- history pagination state
- hold channel
- avoid set

Call IDs are not assumed globally unique. History, replay, and audio cache entries are keyed by both server profile and call ID.

## Build

Recommended: JDK 21 and Android SDK 37.

```bash
python3 tools/validate_project.py
gradle test assembleDebug
```

The repository includes a GitHub Actions workflow that installs Android API 37, runs static validation and unit tests, builds the debug APK, and uploads `FatLine-debug` as a workflow artifact.

Debug APK path:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Verification

Local verification that does not require Android SDK:

- project/manifest/static validator
- RFC 5869 HKDF-SHA256 vector
- bidirectional P-256 ECDH derived-key agreement
- AES-256-GCM nonce-prefixed audio decryption smoke test
- 64-bit radio reference protocol fixtures/unit tests

The authoritative Android compile result is the GitHub Actions `Android CI` workflow.

## Security decisions

- PINs are not stored in plaintext preferences.
- TLS certificate verification uses Android/OkHttp defaults; there is no trust-all or certificate-bypass code.
- Cleartext HTTP remains allowed for self-hosted LAN scanner deployments, with an explicit warning in the UI.
- No advertising SDK, analytics SDK, or mandatory Google Play Services dependency is included.
- Relay audio master keys remain in process memory and are not persisted.

## Android Auto status

FatLine uses the current Media3 `MediaLibraryService`/`MediaLibrarySession` architecture. Saved scanner profiles are browsable; connected-profile favorites are exposed as channel items. Selecting a favorite issues a FatLine hold operation for that channel. Scanner calls themselves are the actual playable media items queued into ExoPlayer.

Android Auto support needs device/desktop-head-unit runtime testing after CI compile; source-level support does not prove host compatibility.
