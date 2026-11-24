# ADR 0013 — Viewfinder gestures and tap-to-focus

- **Status:** Accepted
- **Date:** 2026-11-24
- **Phase:** 2/5 (preview and focus)
- **Depends on:** ADR 0006 (orientation/aspect), ADR 0012 (software focus)

## Context

The viewfinder only offered the zoom slider and the focus card. Operators expect
a tap on the preview to focus at that point and a two-finger pinch to zoom.

The hard part is tap-to-focus: the displayed viewfinder is upright, but the
camera buffer has the sensor transform, the stream is a centred crop of the
active array, and the current zoom shrinks that crop. Guessing a sensor rotation
would put the AF region in the wrong place.

## Decision

1. **Map the tap through the live GL transform.** The renderer keeps a snapshot
   of the SurfaceTexture transform and the display rotation. A tap in viewfinder
   coordinates is passed through the same transform the shader applies, yielding
   the camera-buffer coordinate. The bridge then maps the buffer coordinate to
   the active array using the stream-aspect crop and the current zoom.
2. **Hardware AF gets a real region and a trigger.** `CONTROL_AF_REGIONS` is set
   to a 12% metering rectangle with weight 1000; a one-shot
   `CONTROL_AF_TRIGGER_START` is captured and the repeating request returns to
   `IDLE`. The region is cleared when switching to manual or SAF and when the
   camera changes.
3. **SAF uses viewfinder coordinates directly.** The software focus ROI is a
   normalised centre in the upright analysis frame, so a tap simply moves the
   ROI and resets the software search. No sensor mapping is needed.
4. **Pinch is a Flutter scale gesture.** `onScaleStart` captures the current
   ratio, `onScaleUpdate` clamps `start * scale` to the camera's zoom range and
   calls `setZoom`, and the existing zoom plugin remains as a precise fallback.
5. **Tap and pinch share one gesture area.** The detector wraps only the active
   image (the AspectRatio box), not the letterbox bars, HUD or console. A tap
   shows a short-lived focus reticle so the operator sees where the AF point
   landed.
6. **No face or eye tracking.** Tap-to-focus is a point, not a tracked subject.

## Consequences

- Pad 3: tapping the cup in SAF moved the ROI, the search re-ran and the lens
  moved from 5.59 D to 2.19 D with a higher score; the reticle appeared at the
  tap. In CAF the same tap produced
  `CONTROL_AF_REGIONS [2882 1829 3162 2109] [1000]` and an AF trigger.
- The tap mapping now follows the same transform as the preview, so it remains
  correct after resolution/aspect changes. The buffer-to-active-array crop is
  the only approximation: the device may use a slightly different sensor ROI
  for a stream than the ideal centred crop.
- Pinch zoom is implemented through the standard Flutter scale recognizer. The
  test harness can inject single taps but not multi-touch, so the device
  evidence covers tap-to-focus; pinch is verified by the gesture wiring and the
  existing zoom state/slider.
- Tapping while in MF does nothing: there is no depth information to turn a 2D
  tap into a manual diopter value.

## Alternatives considered

- **Hard-code the sensor rotation** — rejected: would break on other
  orientations and front cameras.
- **Use Camera2 AE/AF regions only** — cannot serve SAF, which is software
  contrast AF.
- **Wrap the whole screen in a gesture detector** — would steal taps from the
  console and HUD.
- **Track the tap as a subject** — out of scope; no face/eye tracking by design.
