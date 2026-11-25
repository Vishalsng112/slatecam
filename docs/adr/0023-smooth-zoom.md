# ADR 0023 — Frame-driven smooth zoom

- **Status:** Accepted
- **Date:** 2026-11-25
- **Phase:** 5 (capture / lens control)
- **Depends on:** ADR 0006 (single pixel path)

## Context

The zoom slider and the pinch gesture both called `setZoomRatio` on every input
event. `CameraController` applied the new `CONTROL_ZOOM_RATIO` (or crop region)
in one repeating request, so each event landed as a discrete crop jump. In a
clip that reads as a snap, especially when the slider moves in coarse steps or
the HAL re-crops a logical camera at a lens-switch boundary.

A recording-only GL zoom would animate more often than the camera stream, but it
would create a second zoom path and would not be the crop the sensor actually
recorded. Zoom belongs in the camera request, where the preview, encoder and
analysis frame all see the same image.

## Decision

1. **Target plus ramp in `core:camera`.** `setZoomRatio` only stores a target.
   `SmoothZoom` chases it on the camera handler thread, and each applied step
   goes through the normal repeating request. The slider and pinch remain dumb
   input sources; they never animate anything themselves.
2. **Exponential chase in log space.** `value += (target - value) * (1 - e^(-dt/τ))`
   with `τ = 90 ms`, so perceived speed is uniform across the range. A 90 %
   response for a small adjustment lands in about 200 ms.
3. **Log-space velocity cap.** Large jumps are capped at 6 natural-log units per
   second, so 1× → 10× takes about 0.38 s instead of arriving in a couple of
   frames. The cap also prevents the initial step of a long slider drag from
   looking like a cut.
4. **One step per camera frame.** The ramp ticks every 33 ms, matching the 30 fps
   stream. Every recorded frame during the move therefore carries an
   intermediate zoom.
5. **No new UI state.** The existing `zoomRatio` state field carries the applied
   value, so the slider readout and the zoom thumb show the real camera crop as
   it eases. `zoomMin` / `zoomMax` clamp both target and value.
6. **Ramp lifecycle is explicit.** Opening or switching a camera snaps to the
   clamped value and stops the ramp; closing the camera cancels it. Reopening for
   a resolution / bit-depth change holds the current value instead of resuming an
   old target.
7. **A zero-length first step is not "settled".** The ramp is posted one tick
   after a target is set, and `advance(0)` keeps the ramp alive. A single tap
   therefore animates like a drag; only reaching the target stops the ramp.

## Consequences

- Slider and pinch moves are continuous in preview and in the saved clip.
- A zoom during recording is visible in the file; no separate preview-only zoom.
- The final value always settles exactly on the target (epsilon snap), so the
  UI cannot get stuck between steps.
- The ramp uses the existing `applyAndEmit` path, which emits state every 33 ms
  during a move. This matches the focus ramp; if Pigeon traffic becomes a
  problem, the zoom tick can be split into a request-only path with a throttled
  emit.
- Optical lens hand-off is still the HAL's behaviour. The ramp makes the request
  smooth, but a vendor that hard-cuts between physical cameras can still show a
  transition at a switch point; that needs per-device probe evidence, not a
  branch.
