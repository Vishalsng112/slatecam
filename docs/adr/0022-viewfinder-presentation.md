# ADR 0022 — Viewfinder presentation and resolution control

- **Status:** Accepted
- **Date:** 2026-11-24
- **Phase:** 5 (viewfinder)
- **Depends on:** ADR 0006 (single pixel path), ADR 0020 (picture profiles)

## Context

The viewfinder felt laggy while clips recorded through the same GL pipeline
looked smooth. Because both outputs are drawn in one `drawFrame`, a genuine
render bottleneck would also drop encoder frames, so the suspicion was the
presentation path rather than the pixel path.

Measured on the Pad 3 (build 2094, preview 1280×720 and 1920×1080):

- The camera delivers exactly **30.000 fps** (33.333 ms between frames) and the
  GL thread never queues: the frame-available callback posts a draw within
  **0.04 ms**.
- A preview draw costs **1.3–1.5 ms**; with the analysis pass it averaged
  **6.4–7.9 ms**. The analysis pass is a `glReadPixels` readback, and it was
  costing **~9 ms every second frame** — a synchronous GPU sync on the render
  thread.
- A screen recording with a per-produced-frame colour flag showed irregular
  holds at the display (frames held ~1 or 3–7 refreshes, some never composited)
  even though production was exact. A time-based flag cycled cleanly through the
  same recording, so the recording itself was not dropping frames.

The preview is not drawn by SurfaceView: it is an OES texture rendered into
Flutter's `SurfaceProducer`, then composited by the Flutter engine. That hop is
what separates the recorded file (every camera frame, correct timestamps) from
what the eye sees (whatever the compositor latches). It adds latency and is not
synchronised with the 30 fps producer.

A pixel-pack buffer (PBO) would make the analysis readback asynchronous, but
Android's Java `GLES20.glReadPixels` rejects a null pixel buffer, so a PBO
readback cannot be issued through the Java bindings. That path was tried and
reverted.

## Decision

1. **Present on vsync.** The camera listener marks a frame pending; one
   `Choreographer` callback draws it on the next display refresh. A draw is never
   queued twice for the same pending frame, so a slow frame is coalesced instead
   of accumulating latency.
2. **Analyse at 10 Hz.** `ANALYSIS_INTERVAL` is 3 frames. Metering, histogram and
   waveform are published by the bridge at 5 Hz, so 15 Hz was paying the readback
   stall on frames with no consumer. Software AF still gets ten scores per second.
3. **The resolution control lives in the viewfinder.** A `VF Full / 1080 / 720`
   pill floats at the preview's top-right and opens a menu with the exact preview
   output; the console's Viewfinder card is removed. The choice is a preview-only
   height cap: the camera stream, encoder and file stay at the recording mode.
   Keeping it out of the console also stops a preview-only setting from reading as
   a recording setting.

## Consequences

- Preview presentation is aligned to a display refresh and never runs behind a
  backlog of pending draws.
- The render-thread stall drops from 50% of frames to 33% and the average draw
  cost from ~7 ms to ~5 ms at 720p; the encoder path is unchanged (verified at
  30 encoded frames/s while recording).
- The intrinsic latency of camera → SurfaceTexture → Flutter texture → compositor
  → panel remains. Removing it needs a native preview surface (or an FBO split
  where the encoder renders every camera frame and the preview is blitted on
  vsync), which is tracked in the backlog.
- 30 fps live content on a 120 Hz panel will still look choppier than the same
  clip played back at its native rate. That is the capture rate, not a dropped
  frame.

## Alternatives considered

- **PBO readback** — the correct fix for the analysis stall, but not reachable
  from the Java GLES bindings; would need JNI.
- **Native SurfaceView preview** — removes the Flutter hop and its latency, but
  requires punching a hole through the Flutter view and re-homing the HUD; large
  change, deferred.
- **Lower analysis resolution** — the stall is a pipeline sync, not a transfer
  cost, so shrinking 320×180 would not pay for itself.
- **Rendering only when the encoder is attached** — recording already needs every
  frame; the preview is the cheaper of the two outputs.
