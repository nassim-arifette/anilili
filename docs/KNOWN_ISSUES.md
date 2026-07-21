# Known Issues and Fix Tracking

Last updated: July 21, 2026.

This is the audit's source of truth. Checked items have an implementation on a dedicated branch
and have been integrated into `integration/player-core-fixes`. Unchecked items are still open.
Passing JVM tests does not replace validation on a real phone, Android TV device, and Cast target.

## Validation status

- [x] Android SDK API 36 and Build Tools 35.0.1 configured for the audit.
- [x] `testDebugUnitTest` passes on the integrated player tree.
- [x] Kotlin production and unit-test sources compile together after conflict resolution.
- [ ] Build the final debug APK from the final merged tree.
- [ ] Run the player regression matrix on a phone and a low-memory Android TV device.
- [ ] Validate Cast behavior against a physical receiver.

## Fixed and integrated

### Playback core

- [x] **KI-002 - Stale embed callbacks can mutate the next episode.** Navigation tokens and an
  immutable playback key now travel with progress, error, previous, next, and completion events.
  Branches: `fix/embed-navigation-generation` (`3de7291`) and
  `fix/embed-callback-playback-identity` (`7f6191a`).
- [x] **KI-003 - Cancelled source resolution can publish stale playback state.** Every catalog and
  source request is generation-scoped and cancellation is rethrown. Branch:
  `fix/playback-resolution-generation` (`e1c5f52`).
- [x] **KI-001 - WebViews survive renderer loss only as dead instances.** Embed, Pipe, and login
  hosts now detach the invalid renderer, invalidate its callbacks, and construct a fresh WebView.
  Branch: `fix/webview-renderer-recreation` (`9c39793`).
- [x] **KI-015 - Provider catalogs can hide valid episodes or substitute episode zero.** Episode
  navigation uses the ordered union of provider catalogs and a missing episode has no fallback
  index. Branch: `fix/episode-spine-union` (`d89d6c1`).
- [x] **KI-016 - Opening or resolving an episode overwrites Last Watched before playback.** History
  is created only after a validated native `isPlaying` callback, validated embed playing tick, or
  validated terminal event. Branch: `fix/history-on-confirmed-playback` (`a80fba5`).
- [x] **KI-017 - Native progress can be attributed to the wrong episode.** Media3 items carry the
  anime, episode, source generation, and concrete media ID; stale callbacks are rejected. Branch:
  `fix/history-on-confirmed-playback` (`a80fba5`).
- [x] **KI-018 - A partial provider skip object suppresses AniSkip data.** Intro and outro ranges
  are normalized and merged independently. Branch: `fix/skip-marker-field-merge` (`015ad71`).
- [x] **KI-019 - Skip Outro incorrectly depends on autoplay.** Auto-skip seeks past the outro when
  autoplay is off or no next episode exists, advances only when both conditions allow it, and does
  nothing while paused. Branch: `fix/outro-skip-policy` (`76fb9f4`).
- [x] **KI-020 - Observable embeds never advance on natural completion.** Natural end now requires
  a credible content duration, multiple playing observations, current navigation identity, and an
  exactly-once gate shared with outro autoplay. The verified terminal position is durably committed
  before Next. Branches: `fix/embed-safe-natural-end-autoplay` (`94429d0`) and
  `fix/embed-terminal-progress-commit` (`0bf832d`).
- [x] **KI-021 - Watch Next can publish episodes out of order.** Per-anime monotonic tickets and a
  serialized provider mutation prevent an older worker from winning; the throttle is content-aware
  and advances only after success. Branch: `fix/watch-next-content-aware-throttle` (`673ca2e`).
- [x] **KI-022 - A decoder fallback decision leaks into another media item.** Retry state is scoped
  to the actual playback item and quality candidate. Branch:
  `fix/player-decoder-retry-per-playback` (`e867640`).
- [x] **KI-023 - Double Next and stale player navigation can skip episodes.** Navigation events
  carry the current playback identity and duplicate resolutions are idempotent. Branch:
  `fix/episode-transition-idempotence` (`fc9a491`).
- [x] **KI-025 - Seeking an embed forces a paused video to play.** The injected seek script no
  longer calls `play()`. Branch: `fix/embed-seek-preserves-pause` (`be7b44a`).
- [x] **KI-026 - A late Cast disconnect can revive local background audio.** Local playback owner
  tokens prevent a disposed UI from being resumed. Branch:
  `fix/cast-background-disconnect` (`5085e56`).
- [x] **KI-027 - Native completion can autoplay before its final position is persisted.** The
  terminal Media3 item is validated, synchronously persisted, and committed exactly once before
  Next is allowed. Branch: `fix/native-completion-finalization` (`cd2a9ef`).
- [x] **KI-032 - A completed final episode remains in Continue Watching.** Terminal native and
  embed events now archive the final episode without erasing viewing history, remove Android TV
  Watch Next state, and restart from zero when replayed. Branch:
  `fix/completed-final-episode-history` (`8408ba5`).
- [x] **KI-033 - Wall-clock rollback can freeze progress and Watch Next throttles.** Process-local
  throttles now use `SystemClock.elapsedRealtime()`. Branches:
  `fix/history-on-confirmed-playback` (`a80fba5`) and
  `fix/watch-next-content-aware-throttle` (`673ca2e`).
- [x] **KI-036 - Native and embed players can briefly overlap during a mode change.** A render
  barrier stops Media3 before composing WebView/terminal content, and a surface lease rejects late
  controller connections and `prepare()` calls. Branches:
  `fix/player-mode-exclusive-handoff` (`2345f68`) and
  `fix/native-playback-terminal-state` (`2a5ae73`).
- [x] **KI-037 - Native audio can survive Loading, Error, No Source, or web fallback.** Terminal
  watch states explicitly stop and clear service playback. Branch:
  `fix/native-playback-terminal-state` (`2a5ae73`).
- [x] **KI-038 - A late native error/end event can target a replacement item.** Terminal errors,
  completion, and navigation now carry and validate the MediaItem playback identity. Branch:
  `fix/native-event-playback-identity` (`478aded`).
- [x] **KI-039 - Episode/provider transitions can lose the final native progress sample.** A
  validated transition snapshot is synchronously flushed before resolution replaces its owner.
  Branch: `fix/native-transition-progress-flush` (`39a094e`), with integration follow-up `fda9087`.
- [x] **KI-040 - Two overlapping WatchScreen instances can both control the service.** Service,
  surface, callbacks, controls, and teardown are gated by a monotonic Watch owner token. Branch:
  `fix/watch-playback-owner-generation` (`2a66c3a`).
- [x] **KI-041 - Embed resume seek can run before the video element is ready.** Resume injection
  retries for the current navigation only and stops after success or replacement. Branch:
  `fix/embed-resume-readiness` (`4d83d71`).
- [x] **KI-042 - A late provider catalog can remain invisible during active resolution.** The
  validated catalog union is published without letting stale work replace the current episode.
  Branch: `fix/late-catalog-spine-publication` (`6917173`).
- [x] **KI-043 - Switching SUB/DUB can silently return to a failed preferred provider.** Category
  changes keep the provider that is actually playing. Branch:
  `fix/category-provider-continuity` (`6095d07`).
- [x] **KI-044 - Native state from one item can leak into the next item.** Playing, terminal, and
  progress state is now scoped to the concrete playback session. Branch:
  `fix/native-playback-session-state` (`cc56fe0`).

### Security, authentication, and background work

- [x] Authenticate native WebView bridge callbacks with unguessable capabilities and trusted
  origins - `fix/webview-bridge-capabilities` (`c0a4ea0`).
- [x] Keep WebView timer/lifecycle control local to each player -
  `fix/webview-local-lifecycle` (`e99549d`).
- [x] Serialize and fully tear down the hidden Flixcloud resolver before exposing its result -
  `fix/flixcloud-player-overlap` (`90c97da`).
- [x] Bind MAL session writes and PKCE callbacks to the initiating authentication session -
  `fix/mal-auth-session-race` (`dbb2f4d`) and `fix/mal-auth-code-snapshot` (`57dc5cb`).
- [x] **KI-005 - AniList expiry cleanup or OAuth callbacks can overwrite a newer login.** Token
  replacement, expiry, callback claim, and publication are generation-checked atomically.
  Branch: `fix/anilist-token-generation` (`d9b38ce`, `fe8f550`).
- [x] **KI-004 - A profile response can complete after logout or account replacement.** Loads are
  cancelled and generation-gated before private profile or watchlist publication. Branch:
  `fix/profile-session-generation` (`953ff04`).
- [x] **KI-008 - Pipe readiness, navigation, and request cancellation are not instance-safe.**
  Fetches wait for delayed attachment, reset per document, drain cancelled work, and reject stale
  renderer/navigation callbacks. Branch: `fix/pipe-request-lifecycle` (`cc8d347`) plus reviewed
  follow-up `fix/pipe-request-lifecycle-reviewed` (`b8b05a5`).
- [x] **KI-007 - Update and repository links target upstream instead of this fork.** Release checks
  and Settings now point to `nassim-arifette/anilili`. Branch:
  `fix/fork-update-source` (`94713a6`).
- [x] Replace release alarms only from a complete remote snapshot -
  `fix/release-sync-atomic-snapshot` (`c577e21`).
- [x] Preserve direct Flixcloud HLS capture while combining bridge security and resolver teardown -
  integration fix `608bb7b`.
- [x] Correct the documented SDK level and generated debug APK path.

## Remaining TODO

### High priority

- [ ] **KI-024 - Paused embed seek/teardown does not flush the exact final position.** Periodic
  ticks are sent only while playing. A seek while paused followed by exit can therefore save the
  last playing tick instead of the visible position. Any final callback must carry the immutable
  `EmbedPlaybackKey`; the old `fix/embed-progress-finalization` candidate does not and is unsafe.
- [ ] **KI-028 - Cross-origin iframe embeds expose no playback telemetry.** Browser isolation
  prevents reliable resume, skip, progress, and natural-end detection. This requires a cooperative
  `postMessage` protocol, provider API/native stream, or an app-controlled player page.
- [ ] **KI-029 - Generic web fallback has no trustworthy episode identity.** It opens an anime info
  page and episode selection happens inside the remote site, so app-managed history/progress can be
  absent or misattributed. Use an episode-specific authenticated bridge or label the fallback as
  external/untracked.
- [ ] **KI-030 - Cast cannot reproduce all protected-source headers and external subtitles.** A
  receiver fetches media independently from the sender's Media3 data source. Use a custom receiver,
  authenticated relay, or explicitly advertise only Cast-compatible sources.
- [ ] **KI-031 - Cast progress, Next, and autoplay stop when Watch UI is disposed.** Move durable
  playback identity, persistence, and queue policy into a service-owned coordinator.
- [ ] **KI-034 - Embed telemetry can attach to a preroll/ad video.** The DOM bridge observes the
  first accessible `<video>` and can save its position/duration against the episode. Natural-end
  autoplay is protected by duration/sample thresholds, but progress/history still needs content
  media selection or an explicit provider handshake.

### Medium priority

- [ ] **KI-006 - A reminder can be rescheduled while its cold-start delivery is running.** Remove
  or mark the persisted record before manager initialization can arm it again.
- [ ] **KI-009 - Reminder request codes can collide and schedule changes orphan old alarms.** Use a
  stable persisted identifier instead of arithmetic on media/episode/time fields.
- [ ] **KI-010 - AniList notifications after the first displayed batch are marked delivered.** Mark
  only notifications actually shown, then schedule the remainder.
- [ ] **KI-011 - `AppCache.putBatch()` leaves stale in-memory values.** Replace or invalidate the
  same keys after the database transaction.
- [ ] **KI-012 - Fire-and-forget settings/watchlist writes can complete out of order.** Serialize by
  key or use a latest-state actor rather than independent IO launches.
- [ ] **KI-013 - The release worker can read default preferences before DataStore loads.** Await the
  settings barrier in worker execution.
- [ ] **KI-014 - Picture-in-Picture is declared but has no entry path.** Add parameters, actions,
  Android 12 auto-enter behavior, and lifecycle tests.
- [ ] **KI-035 - AniSkip markers arriving just after the 2.5 second startup budget are discarded.**
  Apply late markers to the same validated playback generation without restarting the player.

## Rejected or superseded candidates

These branches remain local for audit history but must not be merged:

- `fix/history-on-playback-start` (`3caa052`) records presentation rather than confirmed playback.
- `fix/embed-natural-end-autoplay` (`c7d83fc`) can advance when a short ad video ends.
- `fix/embed-progress-finalization` (`a0246e0`) emits final callbacks without playback identity.
- `fix/native-progress-playback-identity` (`5c14b2f`) is superseded by the safer combined history
  branch (`a80fba5`).
- Original Watch Next candidate `c311513` allowed provider writes to finish out of order and is
  superseded by `673ca2e`.

## Device regression checklist

- [ ] Rapidly select A -> B -> C and verify no state, audio, progress, or error returns to A/B.
- [ ] Switch native -> embed -> native and confirm there is never overlapping audio.
- [ ] Leave during controller connection, Loading, Error, No Source, and fallback web playback.
- [ ] Pause, seek, change quality/provider, leave, and verify the exact resume position.
- [ ] Verify intro/outro with autoplay on/off, paused playback, no next episode, and last episode.
- [ ] End native and same-origin embed episodes; verify one terminal write and one Next action.
- [ ] Force decoder failure and confirm one bounded retry for the current media only.
- [ ] Connect/disconnect Cast before and after leaving Watch; verify local audio never revives.
- [ ] Kill a WebView renderer and verify Embed, Pipe, and login recovery.
