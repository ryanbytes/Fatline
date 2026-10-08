# FatLine Android client parity tracker

This is a **feature and behavior parity** checklist, not a claim of visual or protocol equivalence.
The baseline is the publicly advertised ThinLine Android app plus the October 6, 2026
ThinLine v26.10.01 HUD release. Verify every claimed behavior on an Android device
against a third-party/self-hosted compatible scanner; a passing JVM/build CI job is
**not** an end-to-end test.

References (public feature descriptions only; FatLine does not transmit analytics,
account information, or encryption keys to ThinLine-hosted services):
- [ThinLine Android Play listing](https://play.google.com/store/apps/details?id=com.thinlinedynamicsolutions.ohiorsn)
- [ThinLine v26.10.01 release description](https://newreleases.io/project/github/Thinline-Dynamic-Solutions/ThinLineRadio/release/v26.10.01)
- [ThinLine mobile overview](https://www.thinlineradio.com/mobile)

## Core listener parity

| Area | FatLine code status | Remaining acceptance gate |
| --- | --- | --- |
| Concurrent scanner connections, open/PIN access | Implemented | Exercise two real third-party scanners over network handoffs |
| Live audio, background media service, audio mixing | Implemented | Screen-off playback alongside music without ducking |
| Independent saved scanner output volume | Implemented | Confirm slider gain without altering other apps' media output |
| Channel/system enablement, local favorites, system/tag/channel tree | Implemented | Mobile screen and subscription state vs. actual server |
| Scan lists and server-backed list membership/reordering | Implemented | Synchronization and conflict/reconnect tests |
| Hold system/talkgroup, avoid/unavoid, Pause, Skip | Implemented | Verify selection/resume behavior on a real server |
| Recent live calls, tap to play, **Replay last** | Implemented | Correct most recent call with and without active playback |
| Archive/history filters, replay, continue, downloads | Implemented | Long-history pagination, queued playback, authenticated downloads |
| Queue count, preview, clear, durable recovery, de-duplication | Implemented | Android service restart and Wi-Fi/cellular handoff tests |
| Server alert preferences, keyword/tone matching, alert listing | Implemented | Validate against authorized self-hosted server responses |
| Transcripts, filtering and pagination | Implemented | Server-provided transcript and date edge cases |
| Account login, password/email changes, server account details | Implemented | Privacy-safe endpoint and authentication tests |
| Android Auto MediaLibrary browse/play | Implemented | Test with an Android Auto host |
| Custom per-scanner sounds, connection-loss and local severe-weather alerts | Implemented | Notification permission and real-device audio behavior |

## Visual and functional gaps (open)

| Area | Status | Work |
| --- | --- | --- |
| ThinLine v26.10.01 mobile scanner HUD | **Not matched** | Tag-colored glass display, truthful LIVE/SCAN/RX/HOLD SYS/HOLD TG/PAUSE annunciators, system/tag/TGID/unit text, and compact mobile behavior |
| Signal meter / scanning sweep | **Not matched** | Implement only if there is a real signal/scanning state; never synthesize signal data or fake active transmissions |
| Scanner keypad | **Not matched** | Identify actual public-client commands/operations, implement real actions, and test them; no decorative or nonfunctional controls |
| Automatic server-assigned feed sync | **Not implemented** | Only explore privacy-preserving sync directly with the user's own scanner server; never call ThinLine-hosted account/telemetry endpoints |
| Full native app visual parity | **Unverified** | Compare on-device layouts, hierarchy, font sizes, status flags, icons and accessibility to current mobile screenshots |
| Runtime interoperability | **Unverified** | Test phone process death, encrypted/audio relay, rapid reconnects, Android Auto, queue limits, background mixing, and resume behavior |

## Deliberately excluded by user request

- **Push alerts** and native remote-push registration: not required for this FatLine parity target.
- ThinLine subscription/advertising flows, duplicate ads, or app-side analytics/tracking: not added.
- ThinLine-hosted account or relay calls: blocked by existing privacy policy.

## Shipping gate

Every parity PR must pass structural validation, unit tests, APK build and stable signing in
GitHub Actions, then be merged. All assertions about **actual Android behavior** additionally
require physical-device and compatible-server verification; keep those marked unverified until tested.
