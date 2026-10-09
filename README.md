# FatLine

Independent Android client for ThinLine Radio and compatible Rdio-style scanner servers.

FatLine is a clean implementation of the public server/client protocol. It does not contain ThinLine mobile-app code, artwork, package names, or branding.

See [PARITY.md](PARITY.md) for a feature-by-feature Android parity matrix, outstanding mobile HUD/keypad and device-test work, and the user's explicit push-alert exclusion.

## Current feature set

- Android 8.0+ (`minSdk 26`), compile/target SDK 36
- Android system status bar stays visible: light time, network and battery indicators over the dark scanner UI, including edge-to-edge Android versions; no immersive fullscreen
- Five-item bottom navigation with Scanner, Channels, History, and Transcripts directly accessible; Alerts, Weather, and Settings remain under More
- Scanner home removes the redundant FatLine title/subtitle and places a live local clock in the scanner status card beside the connection controls, following Android 12/24-hour settings; disconnected and unconfigured states also show the clock; the separate profile-selector pill above the card is removed, and tapping the scanner name in the card switches servers when multiple are configured
- More shows a notification dot for new alert entries received since the screen was last viewed in the current app session; opening Alerts clears it. Android launcher notification dots come from posted alert/connection/weather notifications, if allowed by system and launcher settings
- Four **icon-only** playback controls fit in one non-scrolling row: audio Play/Stop, Replay, Skip and Clear hold. A Resume-only icon appears solely if a scanner was previously left paused, so older saved pause states cannot strand monitoring. There is no Pause action on the main card. TalkBack labels remain accessible
- Playback foreground notifications use a new Android notification channel with launcher badges disabled (required to update the channel on existing installations); actual alert notifications can still show launcher dots if Android permits them
- History tab refreshes the first archive page when opened or when the selected scanner reconnects, preserving the currently applied server-side archive filters
- History uses compact icon-only toolbar, filter menus, search/reset and per-call Continue/Replay/Download actions; active filters have visual badge markers and descriptive TalkBack labels, keeping the dropdown option names and archive behavior intact
- Compact scanner HUD shows current playback without a separate large Now Playing card; hold, avoid and replay actions remain in a small Call actions menu
- Persistent Play audio / Stop audio switch silences incoming scanner traffic without disconnecting background transcript and alert monitoring; explicit replay resumes audio and buffered queue playback intent survives service recreation
- Kotlin + Jetpack Compose
- Multiple scanner servers connected simultaneously
- Open and PIN-protected servers; PINs stored with Android Keystore AES-GCM and masked in the UI
- Account profile details from `/api/account`, including verification, group, and subscription/access status
- In-app password reset by emailed code for existing scanner accounts
- In-app account password changes using an emailed verification code
- Required account password changes when a ThinLine administrator flags an account for reset
- In-app account email changes with current-address verification and a new-address confirmation link
- Playback notification shows queued-call count and offers Skip and Clear queue controls
- Buffered current and waiting calls are journaled locally, preserving queue order and resume intent across socket reconnects and foreground-service restarts; the cache pruner protects their local audio files
- Duplicate live calls re-sent during reconnect are ignored (including recently played calls while the service remains active) so they cannot displace other buffered calls; manual Replay remains available
- Restoring playback skips missing cached files and audio from scanners that were explicitly disconnected, deleted, or paused; explicit Clear queue and Disconnect all still clear playback
- Server-issued `PNS` PIN updates replace the live reconnect credential and refresh the encrypted saved PIN
- ThinLine-compatible WebSocket commands including `VER`, `PIN`, `CFG`, `CAL`, `LCL`, `LFM`, `ALT`, `ERR`, `XPR`, and `MAX`
- Public-client wire parity for root WebSocket URL, `CAL` string IDs, complete `LFM` boolean maps, challenge-driven PIN authentication, and bare `LFM` live-feed pause
- Persistent per-server channel selections and favorites
- New server profiles default to all authorized talkgroups enabled; an intentional **None** selection stays empty
- Call and alert timestamps follow ThinLine's server-provided 12/24-hour format in the device's local timezone
- The selected scanner uses ThinLine's server-provided UI accent color, with the authenticated user's accent override taking precedence
- Newly authorized/re-scoped talkgroups follow ThinLine's `autoEnableNewTalkgroups` policy without disturbing existing saved selections
- Per-talkgroup and per-system All/None controls; system bulk changes persist in one batch and emit one live-feed update
- Pause / resume live scanning without losing channel selections; per-server Pause state persists through explicit reconnects and foreground-service restarts
- Talkgroup hold and system hold, saved per scanner across explicit reconnects and foreground-service restarts
- Avoid / unavoid, clear avoids, and skip current audio; avoided channels persist per scanner and stale references are removed when the server changes its authorized scope
- Server archive/history through `LCL`, paged results, `CAL` replay, and continuous archive playback from any loaded call toward live
- Archive filtering supports selecting multiple talkgroups from one system via the server's `LCL` `talkgroups` array, maintaining the same selection across paginated results
- Authenticated archived-call audio downloads to `Downloads/FatLine` on modern Android
- Local scanner-alert notifications and transcript display when supplied by the server
- Opt-in per-scanner background transcript monitor: checks the scanner's authenticated transcript API every 30 seconds while the foreground scanner service is active, including when playback is paused; matches whole words or phrases locally and sends device notifications without server-originated alerts, relay telemetry, or push registrations
- Local transcript alerts keep a 500-call deduplication window and 100 saved alerts per scanner; the first fetch establishes a baseline so older transcripts do not generate a flood of notifications
- Interactive NWS radar centered on the saved ZIP location, plus Severe and extreme NWS warning notifications, with a separate per-server sound preference, while the scanner foreground service is active
- Per-scanner Android notification sounds, including system-default, installed custom sounds, and silent alerts
- Independent per-scanner disconnect notification sounds with the same default/custom/silent choices
- Incident alerts can open mapped coordinates or geocoded addresses in the installed maps app
- One-shot scanner connection-loss notifications after a previously healthy connection drops
- Per-server ordered call processing while different servers remain concurrent
- Encrypted-audio compatibility for non-ThinLine-hosted relays using P-256 ECDH, HKDF-SHA256 (`tlr-audio-key-wrap-v1`), and AES-256-GCM; key exchange to `thinlineradio.com` is blocked for privacy
- Bounded encrypted-call buffering while relay key exchange is pending
- Automatic relay-key refresh after encrypted-audio authentication/decrypt failure
- Media3 1.11.0 ExoPlayer foreground-service background playback and `MediaLibraryService` Android Auto surface
- Audio-focus-free scanner mixing: FatLine does not request focus, so music and podcasts can normally continue at their own volume while scanner calls play (Android telephony, exclusive audio paths, and third-party players may still impose interruptions)
- Saved 0–100% FatLine output volume slider using ExoPlayer gain, separate from Android's music/media output volume
- Compact scanner HUD with actual live, scan, RX/playback, hold, pause indicators and optional tag-color backlight; no fabricated signal readings
- Pausing a scanner suppresses only live traffic, preserving user-requested replay audio
- Android Auto browse tree: profiles → favorited talkgroups; server items are browsable/playable, selecting a favorite sets a talkgroup hold, and subscribed browsers are refreshed when favorite-channel metadata changes
- Efficiency: Android Auto favorites are cached across unrelated call, alert, transcript and queue state changes; changing favorites, system configuration, or hidden systems still refreshes subscribers
- Efficiency: playback audio cache cleanup runs on the first write and then every 16 writes per scanner rather than sorting the directory after every call; all persisted queued/playing audio remains protected at every cleanup
- Efficiency: cached scanner history and alerts avoid re-sorting on unrelated status/transcript updates, while changed feeds still refresh correctly
- Efficiency: redundant playback foreground-service posts and identical notifications are skipped, including while retaining visible queue changes
- Efficiency: NWS alerts for multiple scanners in the same ZIP share one successful public weather lookup per five-minute polling cycle; failures retry and every new cycle fetches fresh alerts
- Efficiency: channel tree, favorite-key and History text filters recompute only when their underlying lists, filters or profile change
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

It installs Android API 36, runs the structural validator and JVM unit tests, builds the debug APK, verifies its signing certificate, and uploads the **FatLine-debug** artifact. CI builds use one dedicated FatLine **test-only** signing key and a monotonically increasing CI version code so successive APKs can be installed as updates instead of requiring an uninstall.

Local commands with JDK 21, Android SDK 36, and Gradle 9.4.1:

```bash
python3 tools/validate_project.py
gradle testDebugUnitTest assembleDebug
```

## Security

The CI debug signing key is intentionally repository-visible and is only for personal/test builds of `dev.scanrelay.app`; it must never be reused for a production/release package. Its purpose is stable sideload updates across GitHub Actions runners.

- Saved PINs are encrypted with per-profile keys in Android Keystore.
- HTTPS/WSS uses normal Android/OkHttp certificate validation; no trust-all or pin-bypass code exists.
- Cleartext HTTP remains allowed for self-hosted LAN scanner deployments and the UI warns when it is used.
- Relay audio master keys are held in memory only, cleared when scanner sessions are removed, and refreshed when encrypted audio indicates a stale key.
- No ad or analytics SDK is included.
- Direct requests to vendor-hosted `thinlineradio.com` or `thinlineds.com` (including subdomains) are blocked for scanner, account, and relay traffic; HTTP/WebSocket redirects are disabled to prevent credential forwarding
- Encrypted audio that depends on ThinLine's hosted relay is unavailable; compatible relays under user-controlled domains can still be used

## Verification status

The network-hardened Android code has produced a green CI build including structural validation, JVM unit tests, `assembleDebug`, and debug-APK upload. CI proves the project compiles and its deterministic tests pass; real-device interoperability still needs deliberate testing against real ThinLine servers, especially Wi-Fi/cellular/VPN handoffs, encrypted relay deployments, and Android Auto hosts.
