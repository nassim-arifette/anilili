# Feature Ideas

This is a product backlog for improvements that are not bug fixes. Items are ordered by user
impact, with playback reliability and continuity first. Bugs and technical defects belong in
[`KNOWN_ISSUES.md`](KNOWN_ISSUES.md).

## P0 - Playback and continuity

- [ ] Add a service-owned playback coordinator so native playback, embeds, Cast, history, and
  episode navigation share one durable session identity.
- [ ] Add an explicit playback queue with Up Next, previous/next episode previews, and an autoplay
  countdown that can be cancelled.
- [ ] Show resume choices when reopening an episode: continue, restart, or clear the saved
  position.
- [ ] Let users edit or report incorrect intro/outro markers and remember per-series skip choices.
- [ ] Switch quality or provider without losing position, subtitle selection, audio track, speed,
  or paused state.
- [ ] Display provider capabilities before playback (native/embed, resume support, skip support,
  Cast compatibility, subtitles, and available qualities).
- [ ] Add a provider health panel with recent success rate, startup latency, and a one-tap source
  retry when a server fails.
- [ ] Add a custom Cast receiver or authenticated relay so protected streams, subtitles, progress,
  and episode queues work on the television.
- [ ] Synchronize precise watch position across devices through an optional account-backed store;
  keep local-only mode available.

## P1 - Library and discovery

- [ ] Add Continue Watching controls to mark completed, restart, hide, or remove an item.
- [ ] Add library filters for status, format, genre, release season, dubbed/subbed availability,
  and unwatched episodes.
- [ ] Add a release calendar with timezone-aware airing times and per-series notification rules.
- [ ] Add user profiles with independent history, watchlists, subtitle defaults
- [ ] Add related-season navigation and an ordered franchise view for sequels, prequels, specials,
  and movies.
- [ ] Add smart search suggestions, recent searches, typo tolerance, and provider availability in
  results.
- [ ] Add import/export for local history, watchlist, settings, and blocked providers.
- [ ] Add optional offline downloads for sources that explicitly permit it, with storage and
  expiry controls.

## P1 - Player experience

- [ ] Split the current safe auto-skip preference into independent opening and ending toggles while
  keeping mixed themes and recaps manual by default.
- [ ] Add optional AnimeThemes credits and previews to episode/theme discovery without using
  theme media as episode-specific skip timing.
- [ ] Add real Picture-in-Picture entry with media actions and automatic aspect-ratio updates.
- [ ] Add per-series defaults for sub/dub, audio language, subtitle language, playback speed, and
  preferred provider.
- [ ] Add subtitle font, size, color, background, edge, vertical position, and safe-area controls.
- [ ] Add frame stepping, configurable seek intervals, chapter navigation, and playback-speed
  presets.
- [ ] Add a compact diagnostics sheet showing stream type, resolution, codec, dropped frames,
  buffering, headers required, and the active playback identity.
- [ ] Add a structured "Report playback problem" flow that exports redacted diagnostics without
  tokens, cookies, or full private URLs.

## P2 - TV, accessibility, and polish

- [ ] Add a TV-focused Up Next overlay and predictable focus restoration after dialogs,
  fullscreen, source changes, and errors.
- [ ] Add screen-reader labels, scalable controls, high-contrast mode, reduced motion, and
  color-blind-safe status indicators.
- [ ] Add configurable home rails and the ability to pin genres, lists, providers, or seasonal
  collections.
- [ ] Add theme choices including OLED black, dynamic color, and separate phone/TV density.
- [ ] Add release notes and a migration summary after updates, including changes to playback and
  saved data.
- [ ] Add an in-app status page for provider outages and degraded features so failures are easier
  to distinguish from device problems.

## Delivery notes

- [ ] Define acceptance tests for every playback feature on phone and TV before implementation.
- [ ] Keep provider-specific behavior behind capability interfaces instead of adding provider
  checks directly to the UI.
- [ ] Treat session identity, cancellation, and exactly-once terminal events as requirements for
  every feature that can change episodes or players.
- [ ] Keep privacy-sensitive features opt-in and document what is stored locally or synchronized.
