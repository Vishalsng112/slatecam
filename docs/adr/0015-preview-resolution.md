# ADR 0015 — Independent viewfinder resolution

- **Status:** Accepted
- **Date:** 2026-11-24
- **Phase:** 2/3 (preview and recording)
- **Depends on:** ADR 0006 (orientation/aspect), ADR 0004 (recording)

## Context

The preview output surface, camera stream and encoder all used the recording
mode size. On a 4K mode the viewfinder therefore paid 4K GPU and display cost
even when the panel or operator did not need it. The recording still needs the
full mode size.

## Decision

1. **Three sizes, one pixel path.** The camera stream and encoder use the
   recording mode (`captureWidth/Height`); the preview surface uses a separate
   `previewWidth/Height`. The GL renderer draws the captured texture into the
   preview viewport and the encoder viewport at their own sizes.
2. **Preview quality is a height limit.** `full` uses the mode size; `1080` and
   `720` cap the preview height and preserve the mode aspect (for example a
   4096×3072 4:3 mode becomes 1440×1080 or 960×720).
3. **Changing preview quality never reopens the camera.** It resizes the
   producer surface, retargets the EGL window surface and updates the preview
   viewport. The camera session and encoder surface are untouched, so it is
   safe while recording.
4. **The state exposes both sizes.** `previewWidth/Height` remain the recording
   mode for the HUD and AF-region mapping; `previewOutputWidth/Height` drive the
   Flutter Texture and its aspect box. `previewQuality` is persisted.
5. **Viewfinder card.** Full / 1080 / 720 chips and a line showing the preview
   output versus the recording stream make the separation explicit.

## Consequences

- Pad 3: selecting 720 on a 1920×1080 mode produced
  `output retargeted 1280x720` while a clip recorded immediately afterwards was
  still 1920×1080 Main10 HLG. The camera stream stayed at 1920×1080.
- The preview is a GL downsample; there is no second camera stream and no
  second encoder path.
- The preview output must preserve the capture aspect; the helper derives the
  width from the height limit.
- The tap-to-focus mapping is unchanged because it uses normalised viewfinder
  coordinates and the GL transform, not the output pixel count.

## Alternatives considered

- **Open a second low-resolution camera stream** — rejected: two streams add
  bandwidth and session complexity, and the one pixel path is a project rule.
- **Scale in Flutter** — rejected: the GPU would still render 4K and the
  preview surface would still be 4K.
- **Lower the camera stream and upscale the encoder** — rejected: recording
  quality must stay at the selected mode.
