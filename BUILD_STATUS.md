# FatLine build status

GitHub Actions is the authoritative Android build environment.

## Verified baseline

The API 36 project baseline has produced green CI builds including:

1. structural/project validation
2. JVM unit tests
3. `assembleDebug`
4. upload of `app/build/outputs/apk/debug/app-debug.apk`

## Current parity / network-handoff pass

The current branch adds public ThinLine wire parity, scanner pause/resume, system hold, batched per-system selection, Android default-network handoff detection, stale-socket generation guards, a config/auth handshake watchdog, offline retry suspension, and immediate route-restoration reconnects.

This file is not proof that those newest changes compile until the workflow run for the exact current commit is green. It is also not proof of real-device network handoff behavior: deliberate Wi-Fi/cellular/VPN switching still requires runtime testing on Android with a real scanner server.
