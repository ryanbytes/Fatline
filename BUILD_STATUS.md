# FatLine build status

This tree is intended to be built by GitHub Actions because the ChatGPT execution container does not have a complete Android SDK/Gradle dependency cache.

## Locally checked

- Python structural/project validator
- XML manifest parse
- ThinLine JSON-array protocol fixtures
- 64-bit system/talkgroup references
- source presence for multi-server sessions, history, relay crypto, favorites, hold/avoid/skip, alerts, and Media3

## CI checks

`Android CI` runs:

1. Android SDK 37 installation
2. `python3 tools/validate_project.py`
3. `gradle testDebugUnitTest assembleDebug --stacktrace`
4. upload of `app/build/outputs/apk/debug/app-debug.apk`

Do not treat this file as proof of a successful Android compile. The workflow run is authoritative.
