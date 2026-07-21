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

## Remaining issues

### High priority: playback limitations

- [ ] **OPEN-001 - Cross-origin embed telemetry.** Browser same-origin policy prevents the app
  from reading the actual `<video>` inside many provider iframes. Progress, resume, intro/outro,
  natural end, speed, and caption control remain unavailable there. A reliable solution needs a
  provider `postMessage` contract, a native stream, or an app-controlled player page.

- [ ] **OPEN-002 - Generic web fallback identity.** The fallback site can select a different
  episode inside its page, so the app cannot safely attribute progress to the route that opened it.
  It needs an episode-aware bridge or must remain explicitly unmanaged playback.

- [ ] **OPEN-003 - Cast durability outside Watch.** Progress persistence, completion, and episode
  navigation still live in the Watch UI. They stop when that UI is disposed even if remote Cast
  playback continues. This needs a service-owned playback/history coordinator.

- [ ] **OPEN-004 - Protected Cast sources and subtitles.** Sender-only `Referer`, `Origin`,
  playlist decryption, and subtitle headers are not reproduced by a stock Cast receiver. Some
  protected media therefore cannot play remotely without a custom receiver or authenticated proxy.

- [ ] **OPEN-005 - Embed video selection heuristic.** Generic scripts use the first accessible
  `<video>`, which can be a preroll or advertisement. Terminal filtering reduces false autoplay,
  but progress and resume can still bind to the wrong media. Provider-specific selectors or a
  stable-content heuristic are required.

- [ ] **OPEN-006 - Optimistic manual embed controls.** Auto-skip now waits for JavaScript success,
  but several manual seek/play controls still update their local UI after the command was queued,
  not after the page confirmed it. A shared asynchronous command/acknowledgement layer is needed.

- [ ] **OPEN-007 - Ended non-final episode policy.** With autoplay disabled, a naturally ended
  episode that has a Next episode remains in history at its full duration. Reopening it can start at
  the end. Moving Continue Watching to an unplayed Next episode would conflict with the rule that
  history is created only after confirmed playback, so this needs an explicit product decision.

- [ ] **OPEN-008 - Decoder fallback choice.** The retry caps the same stream at 720p; it does not
  switch to a distinct lower-resolution URL when the manifest/decoder itself is defective.

### Medium priority: application state and platform behavior

- [ ] **OPEN-009 - Reminder cold-start delivery race.** App initialization can reschedule a
  persisted alarm before the receiver removes the record, producing a duplicate notification.

- [ ] **OPEN-010 - Reminder request-code collisions.** `31 * mediaId + episode` is not unique and
  a changed airing timestamp can leave an older alarm armed.

- [ ] **OPEN-011 - Notification delivery accounting.** Only eight new AniList notifications are
  displayed, but all fresh IDs are currently recorded as delivered.

- [ ] **OPEN-012 - Batch cache coherence.** `AppCache.putBatch()` updates Room without replacing
  matching in-memory entries, so the process can continue reading stale data.

- [ ] **OPEN-013 - Write ordering.** Independent DataStore settings writes and rapid remote
  watchlist toggles can complete in an order different from the last UI action.

- [ ] **OPEN-014 - Release-worker settings barrier.** The worker can read default notification
  settings before DataStore has finished loading.

- [ ] **OPEN-015 - Picture-in-Picture entry.** PiP is declared and observed, but the app does not
  call `enterPictureInPictureMode` or configure Android 12 auto-enter behavior.

- [ ] **OPEN-016 - Release metadata ambiguity.** A successful release response with missing
  metadata can still be treated as definitive absence; the sync needs an explicit completeness
  signal from every source.

## Superseded branches intentionally not merged

These early candidates remain as local branches for audit history, but merging them would restore
bugs that their replacements fixed:

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
- [x] Run the complete `testDebugUnitTest` suite on the final merged tree (`BUILD SUCCESSFUL`,
  July 21, 2026).
- [ ] Build the final debug APK with SDK 36 and verify its output name.
- [ ] Validate rapid Watch A -> B transitions, embed/native handoff, pause/seek/exit resume,
  intro/outro, an airing show's latest episode, the actual series finale, Cast, renderer loss,
  account replacement, and provider exhaustion on a real device or emulator.

The last item remains mandatory because JVM tests cannot exercise real Media3 renderers, WebView
process death, cross-origin provider pages, Cast receivers, Android TV Watch Next, or alarm delivery.
