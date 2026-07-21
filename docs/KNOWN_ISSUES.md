# Known Issues and Fix Tracker

Last updated: July 21, 2026.

This is the canonical audit tracker for the current repository tree. A checked item is implemented
and merged into the integration history. An unchecked item is still open, even when the limitation
comes from a provider, browser security boundary, Cast receiver, or missing device validation.

## Completed fixes

### Playback ownership and lifecycle

| ID | Status | Fix | Topic branch |
| --- | --- | --- | --- |
| PLAY-001 | [x] | Give each Watch destination a process-wide owner generation. A disposed or outgoing screen can no longer prepare, control, pause, stop, or publish callbacks into the new native player. | `fix/watch-playback-owner-generation` |
| PLAY-002 | [x] | Stop and flush the outgoing embed/native owner before publishing the next Watch owner, preventing audible overlap during navigation transitions. | `fix/embed-owner-handoff` |
| PLAY-003 | [x] | Use an explicit stop barrier when switching between native, embed, loading, error, and terminal player modes. | `fix/player-mode-handoff` |
| PLAY-004 | [x] | Keep the hidden Flixcloud resolver from overlapping the visible player and preserve direct HLS results. | `fix/flixcloud-player-overlap` |
| PLAY-005 | [x] | Prevent a late Cast disconnect from restarting local audio after the Watch UI has gone away. | `fix/cast-background-disconnect` |
| PLAY-006 | [x] | Recreate Embed, Pipe, and Login WebViews after renderer loss, while keeping timer and lifecycle changes instance-local. | `fix/webview-renderer-recreation`, `fix/webview-local-lifecycle` |
| PLAY-007 | [x] | Scope decoder retries and native Compose state to a logical playback session instead of a reusable URL. | `fix/player-decoder-retry-per-playback`, `fix/native-playback-session-state` |
| PLAY-008 | [x] | Prevent background SUB/DUB validation from opening hidden media pages; remove and stop the outgoing player before replacement resolution; silence and blank reused embeds; pause and reversibly hide reachable overlapping long-form media; and never synthesize an unacknowledged Kiwi fallback tap. | `fix/player-transition-isolation` |
| PLAY-009 | [x] | Preserve the last verified native/embed position across player teardown, restore the previous source/audio routing after a failed user transition, and never resurrect a stream that already failed during automatic recovery. | `fix/player-transition-isolation` |

### Episode and source resolution

| ID | Status | Fix | Topic branch |
| --- | --- | --- | --- |
| SOURCE-001 | [x] | Reject canceled or superseded resolution results before they can replace the current episode. | `fix/playback-resolution-generation` |
| SOURCE-002 | [x] | Build navigation from the union of provider catalogs; never replace a missing episode with index zero. | `fix/episode-spine-union` |
| SOURCE-003 | [x] | Publish late Miruro/Anivexa catalogs atomically and retry direct-link episodes that were absent from the fast catalog. | `fix/late-catalog-spine-publication` |
| SOURCE-004 | [x] | Keep SUB/DUB changes on the provider that is actually playing instead of falling back to a stale preference. | `fix/category-provider-continuity` |
| SOURCE-005 | [x] | Make episode transitions identity-bound and idempotent, including rapid or duplicate Next events. | `fix/episode-transition-idempotence` |
| SOURCE-006 | [x] | After the five-provider fast pass and late catalog jobs, try every remaining provider once. | `fix/exhaustive-provider-fallback` |

### History, resume, completion, and Watch Next

| ID | Status | Fix | Topic branch |
| --- | --- | --- | --- |
| HISTORY-001 | [x] | Create history only after native or observable embed playback is confirmed, not when an episode is merely selected. | `fix/history-on-confirmed-playback` |
| HISTORY-002 | [x] | Attribute progress, errors, and terminal events to the exact anime, episode, generation, media item, and native playback ID. | `fix/native-event-playback-identity`, `fix/native-playback-terminal-state` |
| HISTORY-003 | [x] | Flush the last confirmed native position before route, episode, source, category, or failover transitions. | `fix/native-transition-progress-flush` |
| HISTORY-004 | [x] | Commit native and embed natural completion durably before autoplay and reject duplicate/stale terminal events. | `fix/native-completion-finalization`, `fix/embed-terminal-progress-commit` |
| HISTORY-005 | [x] | Hide a completed final episode from Continue Watching and Watch Next while retaining it in history. | `fix/completed-final-episode-history` |
| HISTORY-006 | [x] | Require a known series episode total and an exact final-episode match before marking a title completed. The latest released episode of an airing series remains resumable. | `fix/final-episode-completion-evidence` |
| HISTORY-007 | [x] | Key Watch Next throttling by episode/source context, use a monotonic clock, and remove completed titles. | `fix/watch-next-content-aware-throttle` |
| HISTORY-008 | [x] | Persist sparse per-episode device progress and render only episodes actually played. Opening episode 90 no longer marks episodes 1-89 complete or fills their thumbnail bars. | `fix/per-episode-watch-progress` |

### Intro, outro, embed, and autoplay

| ID | Status | Fix | Topic branch |
| --- | --- | --- | --- |
| SKIP-001 | [x] | Merge provider and AniSkip intro/outro fields independently; a provider marker always wins for the field it supplied. | `fix/skip-marker-field-merge` |
| SKIP-002 | [x] | Keep a slow AniSkip lookup alive after the 2.5-second startup budget and publish it only into the still-matching playback generation. | `fix/late-aniskip-publication` |
| SKIP-003 | [x] | Keep Skip Outro independent from autoplay, and never auto-skip while playback is paused. | `fix/outro-skip-policy` |
| SKIP-004 | [x] | Mark an embed auto-skip handled only after JavaScript confirms the seek; failed seeks are retried under the current navigation generation. | `fix/embed-seek-result-ack` |
| EMBED-001 | [x] | Authenticate bridge callbacks and isolate every embed navigation so an old page cannot update the new episode. | `fix/webview-bridge-capabilities`, `fix/embed-navigation-generation` |
| EMBED-002 | [x] | Retry resume until a same-origin video is ready, including accessible iframes, and preserve pause state during seeks. | `fix/embed-resume-readiness`, `fix/embed-seek-preserves-pause` |
| EMBED-003 | [x] | Accept natural embed completion only after content-like playback samples, commit it before autoplay, and advance at most once. | `fix/embed-safe-natural-end-autoplay`, `fix/embed-terminal-progress-commit` |

### Pipe, account, synchronization, and update safety

| ID | Status | Fix | Topic branch |
| --- | --- | --- | --- |
| PIPE-001 | [x] | Bind Pipe requests/readiness to a WebView and document generation; cancel displaced requests and reject stale callbacks. | `fix/pipe-request-lifecycle` |
| PIPE-002 | [x] | Let requests wait for delayed WebView attachment and restart readiness safely after navigation or renderer replacement. | `fix/pipe-request-lifecycle-reviewed` |
| AUTH-001 | [x] | Bind MAL authorization codes and verifier snapshots to the correct login attempt. | `fix/mal-auth-session-race`, `fix/mal-auth-code-snapshot` |
| AUTH-002 | [x] | Make AniList expiry checks and OAuth callback claims atomic, rejecting stale, concurrent, or post-logout token writes. | `fix/anilist-token-generation` |
| AUTH-003 | [x] | Reject profile, watchlist hydration, refresh, and delayed login errors after logout, account replacement, or a newer load. | `fix/profile-session-generation` |
| SYNC-001 | [x] | Replace release alarms only from a complete successful release snapshot. | `fix/release-sync-atomic-snapshot` |
| UPDATE-001 | [x] | Point release checks and repository links to `nassim-arifette/anilili`. | `fix/fork-update-source` |
| UPDATE-002 | [x] | Give the fork its own Android identity and add a stable-key GitHub Release workflow so future AniLili+ APKs can update existing installations. | `feature/anilili-plus-auto-updates` |
| UPDATE-003 | [x] | Restore the Gradle wrapper executable bit so the Linux GitHub Actions runner can build releases. | `fix/gradlew-executable` |
| UPDATE-004 | [x] | Recompute deterministic asset names in the publish job instead of passing outputs that GitHub redacts after a secret-bearing signing job. | `fix/release-output-taint` |
| UPDATE-005 | [x] | Distinguish the workflow's no-replacement policy from GitHub's separate repository-enforced immutable-release feature. | `fix/release-immutability-claim` |
| UPDATE-006 | [x] | Keep the repository-administration immutability check as an operator preflight because `GITHUB_TOKEN` cannot read it; require `immutable: true` from the published release and roll back a mutable publication and its generated tag. | `fix/release-immutability-token-scope` |
| BRAND-001 | [x] | Replace the inherited anime-head launcher artwork, monochrome icon, and TV banner mark with a fork-specific AniLili+ orbit-and-plus identity. | `fix/distinct-rebrand-artwork` |

## Follow-up issue checklist

### High priority: playback limitations

- [ ] **OPEN-001 - Cross-origin embed telemetry.** Browser same-origin policy prevents the app
  from reading the actual `<video>` inside many provider iframes. Progress, resume, intro/outro,
  natural end, speed, and caption control remain unavailable there. A reliable solution needs a
  provider `postMessage` contract, a native stream, or an app-controlled player page. The app also
  cannot enumerate or pause two independent media elements inside an active cross-origin iframe;
  only whole-WebView pause and blanking during transitions are guaranteed.

- [x] **OPEN-002 - Generic web fallback identity.** Generic fallback pages are now explicitly
  unmanaged: they cannot write route-owned progress/history, and cross-origin controls expose only
  a safe handoff to the provider's real controls instead of inventing a playback state or sending a
  blind synthetic play/pause tap.

- [ ] **OPEN-003 - Cast auto-next after Watch disposal.** The playback service now owns Cast
  progress, durable completion, account sync, identity restoration after service recreation, and
  single-writer handoff. It can also navigate while the exact Watch catalogue is alive. If Watch is
  fully destroyed, it cannot safely resolve the next provider URL, so remote natural completion is
  saved but does not auto-load the next episode.

- [x] **OPEN-004 - Protected Cast sources and subtitles.** Cast now rejects embeds, local playlist
  keys, sender-only headers, non-HTTP media, and side-loaded subtitles whose request metadata cannot
  survive receiver transfer. It selects the closest public alternate when one exists; otherwise it
  disables direct Cast and offers protected screen mirroring instead of sending a broken item.

- [x] **OPEN-005 - Embed video selection heuristic.** Accessible videos are scored by duration,
  dimensions, visibility, readiness, and source stability. Ads, prerolls, and sub-two-minute media
  are rejected. Once a candidate is observed playing, its identity is sticky for telemetry, resume,
  controls, and competing-player suppression; a later higher-resolution DUB cannot steal the lock.

- [ ] **OPEN-025 - Ambiguous first embed sample.** If two same-origin, equally plausible long-form
  videos are already playing before the app's first sample, there is no provider-neutral signal that
  proves which layer the viewer intended. The app keeps one stable choice and avoids later switching,
  but exact disambiguation needs provider-specific player metadata or a messaging contract.

- [ ] **OPEN-026 - Separate synchronized embed audio.** A rare provider may intentionally drive a
  standalone `<audio>` element alongside its video. Generic competing-media protection can classify
  that track as background media and pause it; reliable pairing requires provider metadata or a
  player messaging contract.

- [x] **OPEN-006 - Optimistic manual embed controls.** Seek, play/pause, speed, volume, resume, and
  skip commands now carry a generation, command ID, and media identity. UI state changes only after
  a matching acknowledgement; superseded, rejected, stale, and timed-out commands cannot commit.

- [x] **OPEN-007 - Ended non-final episode policy.** A naturally completed non-final episode keeps
  its honest history record but stores an explicit continuation target. Continue Watching opens the
  next episode at zero without pretending that the next episode has already been played.

- [x] **OPEN-008 - Decoder fallback choice.** Decoder recovery now exhausts distinct lower-quality
  URLs in order, preserves the confirmed position, avoids retry loops, and only then performs the
  final same-manifest 720p cap.

### Medium priority: application state and platform behavior

- [x] **OPEN-009 - Reminder cold-start delivery race.** Receivers durably claim and remove the exact
  record before posting. Startup restoration cannot re-arm a claimed delivery, and stale broadcasts
  become harmless no-ops.

- [x] **OPEN-010 - Reminder request-code collisions.** Alarms use stable full record identities,
  cancel legacy identities, and reconcile replacements so timestamp changes cannot leave an older
  alarm armed.

- [x] **OPEN-011 - Notification delivery accounting.** Only IDs successfully posted in the current
  eight-item batch are recorded. Remaining unread items stay eligible for the next synchronization,
  and account-generation checks reject results from a replaced session.

- [x] **OPEN-012 - Batch cache coherence.** Batch writes now update the in-memory and Room cache as
  one coherent operation, so later reads cannot return entries predating the batch.

- [x] **OPEN-013 - Write ordering.** Settings use an ordered pending overlay over DataStore, and
  watchlist mutations are serialized with account/session checks. The last accepted UI action is
  now the state that survives asynchronous persistence.

- [x] **OPEN-014 - Release-worker settings barrier.** Workers and reschedule receivers await the
  loaded settings snapshot. Disabled release notifications also durably cancel existing work and
  alarms before returning.

- [x] **OPEN-015 - Picture-in-Picture entry.** Native playback now publishes its exact surface
  bounds and aspect ratio, uses legacy user-leave entry where required, and configures Android 12+
  auto-enter while excluding remote Cast and non-native playback.

- [x] **OPEN-016 - Release metadata ambiguity.** Release and AniList-list fetches now distinguish
  present data, definitive absence, and incomplete/malformed responses. Destructive alarm
  reconciliation runs only after every source produced a complete snapshot.

- [ ] **OPEN-017 - Physical-device update validation.** The updater and signed release pipeline
  have automated coverage, but a complete older-APK to newer-APK install should still be verified
  on at least one phone and one Android TV device before treating the distribution path as proven.

- [ ] **OPEN-018 - Rebrand screenshots.** Launcher, monochrome, and TV artwork are now distinct,
  but the repository's six device captures still show the pre-rebrand interface. Replace them with
  captures from the final build after physical-device validation.

- [ ] **OPEN-019 - Independent OAuth registrations.** AniLili+ still uses the inherited AniList and
  MyAnimeList client registrations. Register and test fork-owned clients so login does not depend on
  credentials that the upstream owner can rotate or revoke.

- [ ] **OPEN-020 - Upstream-data migration.** The fork-specific Android application id allows
  side-by-side installation but cannot read the original app's private history, settings, tokens,
  reminders, or TV rows. Add an explicit encrypted export/import flow before promising migration.

- [ ] **OPEN-021 - Legacy provider request identity.** Flixcloud playback still sends
  `X-Requested-With: com.miruronative`. It may be required by the provider rather than represent the
  installed application id. Test both identities against real streams before changing it, because
  an unverified rename could break playback.

- [x] **OPEN-022 - GitHub Actions Node runtime migration.** Checkout, Java, Gradle, Android SDK,
  upload, and download actions are pinned to reviewed exact releases whose manifests use Node 24.

- [x] **OPEN-023 - GitHub-enforced release immutability.** Repository release immutability is
  enabled. The workflow uploads and verifies both assets on a draft before publishing, requires the
  release response to be immutable, and `v0.2.1` has a valid GitHub release attestation. Historical
  `v0.2.0` remains mutable because the setting only applies to future releases.

- [ ] **OPEN-024 - Sparse remote-list progress.** AniList and MyAnimeList expose an aggregate
  integer episode count rather than a sparse per-episode ledger. Local thumbnails now remain honest,
  but syncing out-of-order playback such as episode 90 still uses the remote service's sequential
  `progress = 90` semantics. Avoiding that inference requires a product policy for non-contiguous
  playback (for example, syncing only the highest contiguous locally completed episode).

## Superseded candidates retired without tree changes

These early candidate heads are recorded as parents of an `ours` merge for complete branch history,
but none of their obsolete tree content was imported because it would restore bugs fixed by their
replacements:

- `fix/history-on-playback-start` (`3caa052`) was replaced by
  `fix/history-on-confirmed-playback`.
- `fix/embed-natural-end-autoplay` (`c7d83fc`) was replaced by
  `fix/embed-safe-natural-end-autoplay` plus durable terminal commit handling.
- `fix/embed-progress-finalization` (`a0246e0`) was replaced by identity-bound embed progress and
  terminal commit handling.
- `fix/native-progress-playback-identity` (`5c14b2f`) was replaced by the stricter native session,
  media-item, error, and terminal identity fixes.

## Validation checklist

- [x] Focused unit tests for each topic branch passed before integration.
- [x] Run the complete `testDebugUnitTest` suite on the final merged `v0.2.1` tree: 385 tests,
  zero failures/errors, and two skipped tests (July 21, 2026).
- [x] Build the final debug APK with SDK 36 and verify
  `app/build/outputs/apk/debug/anilili-plus-debug.apk` with Android's APK signer.
- [x] Run `testDebugUnitTest`, `lintRelease`, and `assembleRelease` using the same release tasks as
  GitHub Actions (`BUILD SUCCESSFUL`; lint reported zero fatal/error findings, July 21, 2026).
- [x] Build the locally signed AniLili+ release APK and verify application id, version `0.2.1`
  (`versionCode 30`), 16 KiB ZIP alignment, one signer, and the pinned release certificate. Local
  SHA-256: `bff9225fe4c822c0d9808112089d3eac033f8edaf70db5c175edf8fba87ac6d1`.
- [x] Run a clean non-incremental `testDebugUnitTest`, `lintRelease`, `assembleDebug`, and
  `assembleRelease` on the merged `v0.2.2` tree: 419 tests, zero failures/errors, two skipped tests,
  zero fatal/error lint findings, and `BUILD SUCCESSFUL` (July 21, 2026).
- [x] Verify the locally signed AniLili+ `v0.2.2` APK: application id
  `com.nassimarifette.anililiplus`, `versionCode 31`, 16 KiB ZIP alignment, one signer, and the
  pinned release certificate. Local SHA-256:
  `a131a746387f21bc722930d807fd0063e919e79abef9511ce908675abaf24f44`.
- [x] Publish and download GitHub Release `v0.2.0`, then independently verify its sidecar and API
  SHA-256 (`3c0897f11fb5763cf5eb71d51043321fb56b11835d8a5a719ed7e9fd9b45f6ad`),
  package metadata, 16 KiB alignment, and pinned signer (July 21, 2026).
- [x] Publish immutable GitHub Release `v0.2.1`, download both assets, and independently verify
  their release attestation, API digests, sidecar, package metadata, 16 KiB alignment, and pinned
  signer. Published APK SHA-256:
  `a426088687a4c70ddbeb791cd051ae808426aa76e155e6c9a385c0ef7a36bfd5` (July 21, 2026).
- [ ] Validate rapid Watch A -> B transitions, embed/native handoff, pause/seek/exit resume,
  intro/outro, an airing show's latest episode, the actual series finale, Cast, renderer loss,
  account replacement, and provider exhaustion on a real device or emulator.

The last item remains mandatory because JVM tests cannot exercise real Media3 renderers, WebView
process death, cross-origin provider pages, Cast receivers, Android TV Watch Next, or alarm delivery.
