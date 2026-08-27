# FatLine build status

GitHub Actions is the authoritative Android build environment for this repository.

## Verified build baseline

The Android API 36 configuration has completed successful CI runs with:

- Python structural/project validation
- XML manifest validation
- deterministic ThinLine protocol unit tests
- P-256 / ECDH / HKDF-SHA256 / AES-GCM crypto unit tests
- `testDebugUnitTest`
- `assembleDebug`
- upload of `app/build/outputs/apk/debug/app-debug.apk` as the **FatLine-debug** artifact

The runtime-hardening branch was also compiled successfully after adding foreground-service lifecycle cleanup, bounded playback-queue behavior, per-server ordered call processing, and encrypted-audio key-rotation recovery.

## CI checks

`Android CI` runs:

1. Android SDK 36 installation
2. `python3 tools/validate_project.py`
3. `gradle testDebugUnitTest assembleDebug --stacktrace`
4. upload of `app/build/outputs/apk/debug/app-debug.apk`

## What CI does not prove

A green build does not by itself prove interoperability with every live ThinLine/Rdio-style server, relay-encryption deployment, Android Auto head unit, or vendor-specific Android background-management behavior. Those paths still require runtime testing against real deployments.
