# ADR 0006 — Frame orientation and capture aspect

- **Status:** Accepted
- **Date:** 2026-11-23
- **Phase:** 3 (recording correctness)
- **Depends on:** ADR 0004 (recording), ADR 0005 (colour pipeline)

## Context

Camera buffers arrive in sensor orientation. The first implementation rotated the
picture in two different places:

- the Flutter preview rotated the GL texture with `RotatedBox` and fitted it into
  a 16:9 frame with `BoxFit.cover`;
- the recorder wrote the unrotated buffer and added an MP4 rotation hint.

On both test devices (sensor 90°, landscape display) the encoded buffer was a
16:9 frame containing a sideways scene, while the preview applied the
SurfaceTexture matrix and then another rotation in Flutter. The preview was
stretched and the recording only looked right in players that honoured the
rotation hint; preview and recorded framing did not match.

## Decision

1. **The GL pipeline produces one upright frame.** After fetching the camera
   texture, the shader applies the SurfaceTexture transform plus a rotation
   derived from sensor orientation and current display rotation. This output is
   used for preview, encoder, and analysis; there is no second orientation path.
2. **Encoded pixels are upright.** The MP4 orientation hint is always `0`. A
   player must not need metadata to display the clip the way the viewfinder
   showed it.
3. **Flutter does not rotate the preview.** It renders the texture at its pixel
   aspect and lets the viewfinder letterbox with `BoxFit.contain`. Rotating in
   Dart would double-apply the GL rotation.
4. **The capture aspect is the stream aspect.** Preview buffer, camera stream
   and encoder dimensions are the same size, so the upright frame fills the
   output without stretch. Adding 4:3 means selecting a 4:3 stream size and
   matching preview/encoder dimensions; it is not a post-hoc resize. Changing
   that size must also recreate the output EGL window surface: a
   `SurfaceProducer.setSize` call alone leaves the renderer writing into the
   previous buffer, which shows up as a cropped/squashed image with a black
   band. The bridge forces a new producer surface and calls `updateTarget`.
5. **Rotation policy in landscape:**
   - `ROTATION_90`: rotate by sensor orientation (90° back, 270° front on the
     test devices);
   - `ROTATION_270`: add 180° to that;
   - portrait display is not a supported layout (`sensorLandscape`).

If the source and target aspects ever differ (an aspect preset that cannot get
a matching stream), the fit is resolved in GL with a deliberate crop or
letterbox, never by non-uniform scaling.

## Consequences

- Preview and recording now show the same orientation and aspect. Verified on
  both test devices: raw encoded frames are upright 1920×1080 with no rotation
  metadata.
- Front camera preview is upright but not mirrored. Mirroring is a separate
  preview-only decision and is not implemented yet.
- The rotation table is validated for the two landscape display rotations on
  the test devices. A portrait layout, if ever added, must extend it.
- A recording aspect selector (16:9 / 4:3 / anamorphic) becomes a matter of
  choosing a stream size and encoder dimensions; the transform already preserves
  aspect.
- Preview resizes are explicit: producer surface, EGL window surface, camera
  SurfaceTexture buffer and camera session are updated together. Verified by
  switching FHD -> 4:3 -> 4K -> 4:3 repeatedly on the Pad 3 and POCO.

## Alternatives considered

- **Rotate only in Flutter** — rejected: the recorder would keep writing
  unrotated pixels and preview/recording would diverge.
- **Rely on the MP4 rotation hint** — rejected: players, editors and thumbnails
  disagree, and the user's framing does not match the viewfinder.
- **Stretch the buffer to the target aspect** — rejected: the original bug.
