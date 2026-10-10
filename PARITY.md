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
| Recently played live calls, tap to play, **Replay last** | Implemented; Recent remains hidden until a live call naturally finishes **with observed media-position progress**; mere receipt, queued, unrendered, skipped and replay-only calls are excluded | Verify empty Recent section, completed call ordering, skips, manual Stop, queue backlog, replays and final item completion on Android device |
| Archive/history filters, replay, continue, downloads | Implemented, including incremental stable live-History insertion, unchanged paged merging, multi-talkgroup server filtering, entry refresh and compact icon-only archive/filter/call actions | Verify stable order for equal timestamps, duplicate call metadata, both sort directions, filter preservation, pagination, replay and continuation against live server |
| Queue count, preview, clear, durable recovery, de-duplication | Implemented | Android service restart and Wi-Fi/cellular handoff tests |
| Reopen-app recovery of previously active scanner sessions | Implemented | Kill the foreground service, reopen app, and verify old connections resume without disrupting existing ones; no boot auto-start |
| Server alert preferences, keyword/tone matching, alert listing | Implemented, including immediate play and locally persistent delete on scanner home and full Alerts screen | Validate playback for alerts with linked call IDs, disabled/unavailable playback for alerts without recordings, local deletion/reload/reconnect and server history remaining unchanged |
| On-device transcript keyword/phrase alerts | Implemented (opt-in), with cached pre-normalized phrase rules invalidated on settings changes | Verify identical live/delayed phrase matches, rule edits, 30-second vs. 1-minute polling, deduplication and timely notifications on-device |
| Transcripts, filtering and pagination | Implemented | Server-provided transcript and date edge cases |
| Account login, password/email changes, server account details | Implemented | Privacy-safe endpoint and authentication tests |
| Android Auto MediaLibrary browse/play | Implemented; favorites cache reused across unrelated scanner-state changes while configuration changes still notify connected browsers | Test browse, profile changes and favorite edits with an Android Auto host |
| Custom per-scanner sounds, connection-loss and local severe-weather alerts | Implemented | Notification permission and real-device audio behavior |


**Recent playback gate:** The UI reads only `recentCalls`; socket CALL receipt changes archive and `lastCall`, never Recent. Media3 must see the item start and its position advance before an automatic transition/end can add it. A quick device acceptance check is to accumulate queued calls while another call is playing: pending calls must not appear in Recent until each actually plays through. Test again with audio stopped and then resumed; the list must not grow just from received calls. The service does not persist Recent across process recreation.

## Functional and usability gaps (open)

| Area | Status | Work |
| --- | --- | --- |
| Compact, truthful playback and connection status | **Implemented; device-unverified** | Removed the large duplicate Now Playing card, retained compact HUD and moved hold/avoid/replay into Call actions; verify legibility and usability on-device |
| Automatic server-assigned feed sync | **Not implemented** | Only explore privacy-preserving sync directly with the user's own scanner server; never call ThinLine-hosted account/telemetry endpoints |
| Mobile app usability | **Partially improved; device-unverified** | Bottom navigation now shows Scanner, Channels, History and Transcripts, with Alerts, Weather and Settings in More; scanner playback actions fit in one row of four icon-only accessible buttons, with conditional Resume for a formerly paused scanner (no new Pause action); the top scanner title is removed and a live Android 12/24-hour clock is integrated into the scanner card; the redundant profile pill above the card is removed, with switching available from the scanner name in the card; More unread-alert dot added, and old media notification channel replaced to avoid permanent launcher badges; status-bar visibility over dark edge-to-edge backgrounds fixed; confirm narrow-screen reachability, accessibility and the absence of idle media badges on a device |
| Runtime interoperability | **Unverified** | Redundant queue-journal writes removed without deferring changed snapshots; allocation-free per-call channel lookup, single-pass playback queue projection with lazy overflow/duplicate checks, logarithmic archive timestamp insertion for live calls, no re-sorting for a single already-newest-first scanner archive, cached state aggregation, notification deduplication, same-ZIP weather coalescing and Compose list memoization. Verify queue recovery after process death, live call routing, cache churn, encrypted audio, reconnects, Android Auto and background mixing on-device |

**Memory/allocation audit:** Cached alert badge keys are reused while immutable alert lists remain unchanged and disconnected profiles are released from the cache. Audio-cache path naming now uses ASCII checks instead of per-call regex compilation. Pending call metadata is removed when Android rejects both normal and foreground service starts. These reduce allocations/exceptional retention without altering call, alert, playback or archive limits. The user's 44.4-minute representative capture averaged 3.7% CPU of one core; PSS went from 174.2 to 192.9 MiB (205.7 MiB peak). This is not proof of a leak or of a measured improvement.\n\n**Performance acceptance:** The playback-service playlist is projected with one media-ID lookup per item rather than repeated full-list allocations; queue overflow and reconnect duplicate checks read media IDs lazily. Unit tests compare queue contents, playing item, overflow index and deduplication against independent pre-change logic. This is a structural reduction in traversals/allocations, **not** a measured battery or CPU improvement. Confirm no regressions on a physical Android device with long replay queues, background playback, reconnects, skips and recovery.

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
