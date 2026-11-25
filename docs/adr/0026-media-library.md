# ADR 0026 — Media library and playback

- **Status:** Accepted
- **Date:** 2026-11-25
- **Phase:** 8 (media workflow)
- **Depends on:** ADR 0003 (modules), ADR 0024 (state channels)

## Context

Recording writes finished clips through MediaStore into `Movies/SlateCam`, but
there was no way to review or manage them in the app: `:feature:playback` was a
declared empty module and the UI had no clips screen. A camera app without a
library, playback or delete/share is not usable as a production tool.

MediaStore access must live in `:core:storage` per ADR 0003 (MediaStore, free
space). The bridge is the only module that may touch Flutter, so it owns the
Pigeon media API. The playback UI itself is Flutter, and the app already depends
on Flutter plugins for the host app.

## Decision

1. **One media owner.** `:core:storage` gets `MediaLibrary`: query
   `Movies/SlateCam`, newest first, with id/name/uri/duration/size/resolution/date;
   lazy cached JPEG thumbnails; delete (and gyro sidecar); share intent; and
   free/total storage. The app owns the clips it creates, so direct MediaStore
   delete/share is used.
2. **Pigeon contract.** HostApi gains `listMediaClips`, `mediaStorageInfo`,
   `mediaClipThumbnail`, `deleteMediaClip`, `shareMediaClip` with `MediaClip` and
   `MediaStorageInfo` DTOs. Listing is metadata-only; thumbnails load on demand
   so opening the library stays quick.
3. **Camera closed during review.** The new Clips action stops the preview
   (recording must already be stopped), pushes the clips route, and reopens the
   camera on return. Playback never competes with the camera session.
4. **Flutter playback.** The official `video_player` plugin (ExoPlayer on
   Android) renders the clip from its MediaStore content URI. The screen adds
   play/pause, scrub, mute, time, share and delete with confirmation. The plugin
   subproject is an explicit module-graph allowlist entry.
5. **No silent data changes.** Delete asks for confirmation and removes the
   MediaStore row plus `files/gyro/<clip>.gyro.json`; the library refreshes.
   Share uses the system chooser with a temporary URI grant.

## Consequences

- Clips are reviewable, playable, shareable and deletable without leaving the app.
- The library is the MediaStore view, so clips recorded before the feature or
  synced externally appear automatically (if under the SlateCam folder).
- Thumbnail generation and MediaStore queries currently run on the Pigeon
  platform thread. They are fast enough for the current library, but should move
  to a worker before very large libraries.
- Playback uses a Flutter plugin rather than a native Media3 platform view; the
  visual player is simpler and camera state is unaffected. A native view can be
  introduced later if frame-accurate scrubbing or external monitor output needs it.
- Media management remains single-clip for now: batch delete, rename, albums,
  proxies and SD/USB-C destination selection are backlog items.

## Alternatives considered

- **Native Media3 player inside `:feature:playback`** — more control (frame step,
  native scopes), but adds a platform view and a second playback state owner.
- **Flutter-only MediaStore access via a plugin** — avoids the Pigeon API but
  moves media ownership outside `:core:storage`, against ADR 0003.
- **Open clips without closing the camera** — risks camera/audio contention and
  leaves an idle camera session running during review.
