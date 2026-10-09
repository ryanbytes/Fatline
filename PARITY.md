# FatLine Android client parity tracker

This is a **functional client parity** checklist, not a mandate to simulate physical radio scanner hardware.
The baseline is the ThinLine Android app's **useful network, playback, channel, history, alert and account features**. A handheld-style scanner facade is optional and not a parity gate. Verify each relevant behavior on an Android device
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
| Live audio, background media service, audio mixing | Implemented, including persistent Play/Stop audio without stopping monitoring | Screen-off playback alongside music without ducking; Stop audio keeps transcript alerts active and resumes cleanly on Play |
| Independent saved scanner output volume | Implemented | Confirm slider gain without altering other apps' media output |
| Channel/system enablement, local favorites, system/tag/channel tree | Implemented | Mobile screen and subscription state vs. actual server |
| Scan lists and server-backed list membership/reordering | Implemented | Synchronization and conflict/reconnect tests |
| Hold system/talkgroup, avoid/unavoid, Pause, Skip | Implemented | Verify selection/resume behavior on a real server |
| Recent live calls, tap to play, **Replay last** | Implemented | Correct most recent call with and without active playback |
| Archive/history filters, replay, continue, downloads | Implemented, including multi-talkgroup server filter and refresh on History entry | Verify on-entry first-page refresh, preserved active filters, one/multiple TG searches, pagination, replay and downloads against a real server |
| Queue count, preview, clear, durable recovery, de-duplication | Implemented | Android service restart and Wi-Fi/cellular handoff tests |
| Reopen-app recovery of previously active scanner sessions | Implemented | Kill the foreground service, reopen app, and verify old connections resume without disrupting existing ones; no boot auto-start |
| Server alert preferences, keyword/tone matching, alert listing | Implemented | Validate against authorized self-hosted server responses |
| On-device transcript keyword/phrase alerts | Implemented (opt-in) | Verify 30-second fast vs. 1-minute battery-saver polling while paused, immediate live CALL matching, deduplication and late transcripts on-device |
| Transcripts, filtering and pagination | Implemented | Server-provided transcript and date edge cases |
| Account login, password/email changes, server account details | Implemented | Privacy-safe endpoint and authentication tests |
| Android Auto MediaLibrary browse/play | Implemented | Test with an Android Auto host |
| Custom per-scanner sounds, connection-loss and local severe-weather alerts | Implemented | Notification permission and real-device audio behavior |

## Functional and usability gaps (open)

| Area | Status | Work |
| --- | --- | --- |
| Compact, truthful playback and connection status | **Implemented; device-unverified** | Removed the large duplicate Now Playing card, retained compact HUD and moved hold/avoid/replay into Call actions; verify legibility and usability on-device |
| Automatic server-assigned feed sync | **Not implemented** | Only explore privacy-preserving sync directly with the user's own scanner server; never call ThinLine-hosted account/telemetry endpoints |
| Mobile app usability | **Partially improved; device-unverified** | Bottom navigation now shows Scanner, Channels, History and Transcripts, with Alerts, Weather and Settings in More; scanner playback actions fit in two non-scrolling rows; confirm narrow-screen labels, pop-up reachability, accessibility and layout on an actual phone |
| Runtime interoperability | **Unverified** | Test phone process death, encrypted/audio relay, rapid reconnects, Android Auto, queue limits, background mixing, and resume behavior |

## Deliberately excluded by user request

- **Physical scanner keypad, simulated signal meter and scanning sweep**: not required; this is a streaming client, not a hardware scanner emulator.
- **Push alerts** and native remote-push registration: not required for this FatLine parity target. Local notifications from received or polled transcript text are allowed and replace server-alert notifications when explicitly enabled.
- Server-URL validation during setup and a dedicated AI-summary UI are not required.
- ThinLine subscription/advertising flows, duplicate ads, or app-side analytics/tracking: not added.
- ThinLine-hosted account or relay calls: blocked by existing privacy policy.

## Shipping gate

Every parity PR must pass structural validation, unit tests, APK build and stable signing in
GitHub Actions, then be merged. All assertions about **actual Android behavior** additionally
require physical-device and compatible-server verification; keep those marked unverified until tested.
