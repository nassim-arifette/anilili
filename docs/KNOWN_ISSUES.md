# Known Issues and Fix Tracking

Last static review: July 20, 2026.

This document is the central tracking list for the audit. Checked boxes indicate that a fix was
implemented and committed on a dedicated local branch; they do not mean that the branch has
already been merged into `main`. Unchecked items still need to be addressed.

The issues below have concrete execution paths in the code, but could not be validated on a real
device because no Android SDK was installed in the audit environment.

**Current status:** 5 fixes implemented, 14 code bugs still open, and 2 documentation fixes still
required.

## Fixes implemented on local branches

Each branch contains a single commit based directly on `main`.

- [x] Authenticate WebView bridge callbacks — `fix/webview-bridge-capabilities`
  (`c0a4ea0`).
- [x] Prevent an old MAL request from overwriting the current session —
  `fix/mal-auth-session-race` (`dbb2f4d`).
- [x] Replace release alarms only with a complete release snapshot —
  `fix/release-sync-atomic-snapshot` (`c577e21`).
- [x] Keep WebView timer lifecycles local to each player —
  `fix/webview-local-lifecycle` (`e99549d`).
- [x] Prevent the hidden Flixcloud resolver from overlapping the active player —
  `fix/flixcloud-player-overlap` (`90c97da`).

## Open bug TODO list

- [ ] KI-001 — Recreate WebViews after their renderer is lost.
- [ ] KI-002 — Isolate embed callbacks and state for each navigation.
- [ ] KI-003 — Prevent cancelled resolutions from publishing stale playback state.
- [ ] KI-004 — Invalidate profile requests on logout or account changes.
- [ ] KI-005 — Protect the AniList token from concurrent writes.
- [ ] KI-006 — Prevent an alarm from being rescheduled while it is being delivered.
- [ ] KI-007 — Point the updater and repository links to the fork.
- [ ] KI-008 — Make the Pipe request lifecycle reliable and cancellable.
- [ ] KI-009 — Give reminders a stable, collision-free identity.
- [ ] KI-010 — Mark only notifications that were actually displayed as delivered.
- [ ] KI-011 — Synchronize the in-memory cache after `putBatch()`.
- [ ] KI-012 — Serialize settings and remote watchlist writes.
- [ ] KI-013 — Wait for preferences to load in the release worker.
- [ ] KI-014 — Implement actual Picture-in-Picture entry.
- [ ] DOC-001 — Correct the documented SDK version.
- [ ] DOC-002 — Correct the documented APK name and path.

## Integration and validation TODO list

- [ ] Integrate the five fix branches into the selected target branch.
- [ ] Explicitly resolve overlaps between branches that modify the same WebView files.
- [ ] Build the app and run unit tests with Android SDK API 36 configured.
- [ ] Validate player changes, renderer loss, alarms, and account changes on a real device.

## High priority

### KI-001 — Dead WebViews are not recreated

- **Impact:** permanently black player, unavailable Pipe resolver until the activity restarts, or
  process termination during OAuth login.
- **Evidence:** `EmbedWebView.onRenderProcessGone()` and `PipeBridge.onRenderProcessGone()` return
  `true`, but their `AndroidView` instances retain the WebView whose renderer died.
  `LoginWebView` does not handle this callback at all. Update and release paths then keep calling
  methods on those invalid instances.
- **Files:** `ui/watch/EmbedWebView.kt`, `data/remote/PipeBridge.kt`,
  `ui/PipeWebView.kt`, `ui/profile/LoginWebView.kt`.
- **Suggested branch:** `fix/webview-renderer-recreation`.

### KI-002 — Callbacks from an old embed modify the new episode

- **Impact:** progress saved against the wrong episode, false playback errors, or controls and
  timestamps that stop updating after changing episode or quality.
- **Evidence:** the same `AndroidView` is reused across URLs. `WebProgressBridge` is constructed
  only once and captures the first states created by `remember(url)`, while late ticks and errors
  use the current callbacks and URL. An event from A can therefore be dropped or attributed to B.
- **Files:** `ui/watch/EmbedWebView.kt`, `ui/watch/WatchScreen.kt`.
- **Suggested branch:** `fix/embed-navigation-generation`.

### KI-003 — A cancelled resolution can publish stale playback state

- **Impact:** quickly changing episode or server can jump back to the old episode, incorrectly
  report that no source exists, or mix two provider catalogs.
- **Evidence:** `MiruroRepository.resolveSources()` wraps suspending calls in `runCatching`
  without rethrowing `CancellationException`. `WatchViewModel.launchAnivexaMerge()` does the same,
  then still writes to `spine`, `mergedIncludesAnivexa`, and global state without checking an ID
  or request generation.
- **Files:** `data/MiruroRepository.kt`, `ui/watch/WatchViewModel.kt`.
- **Suggested branch:** `fix/playback-resolution-generation`.

### KI-004 — A profile request can complete after logout

- **Impact:** the private profile and remote watchlist can reappear after logout or an account
  change.
- **Evidence:** `ProfileViewModel.loadIfLoggedIn()` neither retains nor cancels its `Job`, and it
  does not verify the session before `hydrateWatchlistFromAniList()` or writing `_profile`.
  `logout()` only sets `_profile` to `null`.
- **File:** `ui/profile/ProfileViewModel.kt`.
- **Suggested branch:** `fix/profile-session-generation`.

### KI-005 — The AniList expiry check can erase a new token

- **Impact:** immediate, intermittent logout just after a successful authentication.
- **Evidence:** `AuthManager.current()` reads an old expired token and then calls `clearToken()`
  without a lock or generation check. A concurrent `setToken()` between those operations is then
  erased.
- **File:** `data/auth/AuthManager.kt`.
- **Suggested branch:** `fix/anilist-token-generation`.

### KI-006 — An alarm can be rescheduled during its own delivery

- **Impact:** two notifications and two sounds for the same release when an alarm cold-starts the
  process.
- **Evidence:** `MiruroApp.onCreate()` initializes the managers before the receiver runs. Their
  `init()` reloads and reschedules the still-persisted alarm for `now + 1s`; only afterward does
  the receiver remove the record, without cancelling the alarm that was just recreated. The
  scenario affects both manual and automatic reminders.
- **Files:** `MiruroApp.kt`, `data/reminder/ReminderManager.kt`,
  `data/reminder/AutomaticReleaseManager.kt`.
- **Suggested branch:** `fix/reminder-cold-start-delivery`.

### KI-007 — The fork updater still downloads upstream releases

- **Impact:** the app offers an APK that does not belong to the fork and that Android may reject
  because it has a different signature.
- **Evidence:** the Git remote is `nassim-arifette/anilili`, but `RELEASES_LATEST` and the Settings
  link still point to `kompoti121/anilili`.
- **Files:** `data/update/UpdateManager.kt`, `ui/settings/SettingsScreen.kt`.
- **Suggested branch:** `fix/fork-update-source`.

## Medium priority

### KI-008 — The Pipe request lifecycle is unreliable

- **Impact:** waits of roughly 25 seconds even with a short timeout, orphaned requests, and a
  false “ready” state after WebView recreation.
- **Evidence:** `PipeBridge.fetch()` ignores a `false` or `null` result from the `ready` wait and
  then starts a second wait. Cancellation does not consistently remove the request from
  `pending`. Finally, the delayed `onPageFinished()` callback is tied to neither an instance nor a
  generation, so an old WebView can complete the new `ready` signal.
- **File:** `data/remote/PipeBridge.kt`.
- **Suggested branch:** `fix/pipe-request-lifecycle`.

### KI-009 — Different reminders share the same Android identity

- **Impact:** one reminder can replace or cancel another anime's reminder.
- **Evidence:** `requestCode = 31 * mediaId + episode` is not injective: `(100, 32)` and `(101, 1)`
  both produce `3132`. In addition, the functional identifier includes `airingAt`; if the schedule
  changes, the old reminder becomes invisible from the new row and remains armed.
- **File:** `data/reminder/ReminderManager.kt`.
- **Suggested branch:** `fix/reminder-stable-identity`.

### KI-010 — AniList notifications after the eighth are lost

- **Impact:** when more than eight new unread notifications exist, the remaining ones are never
  displayed by a later sync.
- **Evidence:** `notifyUnread()` displays only `freshUnread.take(8)`, but still records every ID in
  `freshUnread` as already delivered.
- **File:** `data/reminder/AniListNotificationPushManager.kt`.
- **Suggested branch:** `fix/notification-delivery-accounting`.

### KI-011 — `putBatch()` leaves stale values in the in-memory cache

- **Impact:** expired season chains are recomputed on every access in the same process despite
  being correctly rewritten on disk.
- **Evidence:** `AppCache.putBatch()` writes only through `dao.putAll()` without replacing or
  invalidating matching entries in `memory`; `read()` always prefers memory.
- **Files:** `data/cache/AppCache.kt`, `data/MiruroRepository.kt` (`animeSeries`).
- **Suggested branch:** `fix/cache-batch-memory-coherence`.

### KI-012 — Fire-and-forget writes can complete in the wrong order

- **Impact:** after rapid changes, the setting restored on the next launch or the remote watchlist
  state can be the opposite of the last choice shown in the UI.
- **Evidence:** `SettingsStore` setters launch independent `store.edit` calls on `Dispatchers.IO`,
  so their arrival order is not guaranteed. `LibraryStore.toggleWatchlist()` also starts one
  remote job per toggle: the mutex serializes execution but does not guarantee that the job for
  the older state acquires the lock first.
- **Files:** `data/settings/SettingsStore.kt`, `data/library/LibraryStore.kt`.
- **Suggested branch:** `fix/serialized-state-writes`.

### KI-013 — The release worker reads preferences before they load

- **Impact:** at startup, a user who disabled notifications can briefly run a synchronization with
  the default value of `true` and recreate alarms.
- **Evidence:** `SettingsStore.init()` loads DataStore in a coroutine, then
  `ReleaseSyncScheduler.schedule()` immediately starts the worker. `ReleaseSyncWorker.doWork()`
  reads `releaseNotifications.value` without calling `SettingsStore.awaitLoaded()`.
- **Files:** `MiruroApp.kt`, `data/settings/SettingsStore.kt`,
  `data/reminder/AutomaticReleaseManager.kt`.
- **Suggested branch:** `fix/release-worker-settings-barrier`.

### KI-014 — Picture-in-Picture is declared but cannot start

- **Impact:** leaving the app pauses playback instead of opening the mini-player.
- **Evidence:** the manifest enables `supportsPictureInPicture` and the activity observes PiP mode,
  but the code contains no call to `enterPictureInPictureMode`, no parameter configuration, and
  no Android 12 auto-enter setup.
- **Files:** `AndroidManifest.xml`, `MainActivity.kt`.
- **Suggested branch:** `fix/picture-in-picture-entry`.

## Build documentation

- **DOC-001 — Required SDK:** the README says API 35, while `compileSdk = 36`.
- **DOC-002 — APK output:** the README says `app-debug.apk`, but Gradle renames every output to
  `anilili.apk`.
