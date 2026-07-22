# Player API Evaluation

Last reviewed: July 22, 2026.

This note records the external APIs considered for player-critical features. A provider is not
added merely because it exposes more endpoints: playback continuity must keep working without a
new hosted dependency, and skip data must be bound to the media duration and active playback
identity before it can drive an automatic seek.

## AniSkip v2: selected for skip segments

AniLili+ uses the official AniSkip v2 API for community skip markers. The API defines five segment
types: `op`, `ed`, `mixed-op`, `mixed-ed`, and `recap`. It does **not** define a separate prologue
type. Requests include the MAL anime ID, decimal episode number, all five requested types, and the
measured episode duration.

The player applies these safety rules:

- Pure `op` and `ed` markers may follow the user's auto-skip setting.
- `mixed-op`, `mixed-ed`, and `recap` remain explicit manual actions because story content may be
  present.
- Provider intro/outro markers stay manual while AniSkip is loading and become fallback data only
  when AniSkip did not identify that segment family.
- A lookup is published only for the still-active episode, source generation, concrete media
  identity, and duration bucket. Cross-origin embeds without observable duration do not trigger a
  guessed lookup.

Primary references:

- [AniSkip API documentation](https://api.aniskip.com/api-docs)
- [AniSkip v2 segment types](https://github.com/aniskip/aniskip-api/blob/main/src/skip-times/skip-times.types.ts)
- [Official extension player behavior](https://github.com/aniskip/aniskip-extension/blob/main/src/players/base-player.ts)

## Miruro API v3: evaluated, not integrated

The community Miruro API v3 project is a Python/FastAPI proxy around Miruro and AniList. Its own
README requires running it locally or on a VPS and warns that Cloudflare rejects common hosted
platform and datacenter traffic. Integrating it directly would therefore add another service to
operate and another availability/privacy boundary without removing Miruro's upstream fragility.

AniLili+ already sends the Miruro secure-pipe envelope from the device and decodes the response on
device; see [PIPE_PROTOCOL.md](PIPE_PROTOCOL.md). That path keeps provider resolution local to the
app and avoids making playback depend on a separately hosted community proxy. Miruro API v3 can be
reconsidered if it gains a stable, versioned operational contract that provides a capability the
on-device client cannot provide.

Primary reference:

- [Community Miruro API v3 repository](https://github.com/walterwhite-69/Miruro-API)

## AnimeThemes: useful metadata, not skip timing

AnimeThemes maintains a structured opening/ending theme archive and API. It may be useful later
for theme credits, previews, and discovery, but it is not a replacement for episode-encode skip
intervals. Adding it belongs in the product backlog rather than the playback-critical skip path.

Primary reference:

- [AnimeThemes server and API](https://github.com/AnimeThemes/animethemes-server)
