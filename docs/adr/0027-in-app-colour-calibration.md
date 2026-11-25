# ADR 0027 — In-app ColorChecker calibration

- **Status:** Accepted
- **Date:** 2026-11-25
- **Phase:** 5.5 / 7 (colour, device database)
- **Depends on:** ADR 0016 (colour architecture), ADR 0018 (scene-linear
  shader), ADR 0003 (modules)

## Context

The per-lens input profile (`InputProfile`, `CalibrationEntry`) and the
`ColorCalibration` solver shipped, but the database had no measured matrices and
there was no way to produce one on the device. The solver also documented its
samples as display-encoded, while ADR 0018 moved the matrix application into
linear Rec.2020 — a matrix fitted in one domain cannot be applied in the other.
Finally, a camera app can only match sensors if device normalization is separate
from the creative look and can be measured per lens without a rebuild.

## Decision

1. **Measure through a bypass, not the displayed look.** `GlPreviewRenderer`
   gains a calibration capture path that runs the input EOTF and gamut
   conversion, then stops before the input profile, creative look, LUT and
   overlays. It writes display-encoded BT.709 into a temporary FBO and returns
   upright RGBA. The host decodes it with the same BT.709 EOTF, so the solver
   sees the linear signal the input matrix will act on.
2. **Sample in Flutter, solve in Kotlin.** The screen shows the frozen frame and
   a draggable 6×4 perspective quad. It averages a small disc inside each patch
   and sends 24 encoded RGB triples. Kotlin converts them to linear Rec.2020,
   supplies the published ColorChecker Classic references, and fits with
   `ColorCalibration`.
3. **White balance is part of the fit.** Neutral patches set `wb_r_offset` /
   `wb_b_offset` before the CCM and the exposure trim after it. The solver
   reports mean/max ΔE2000 before and after, so the user can judge the
   measurement instead of trusting a matrix blindly.
4. **Rejected fits cannot be saved.** If the matrix is implausible (no chart,
   glare, black frame), `accepted = false`, the screen explains why and the save
   action is disabled. Saving writes to `files/device-database.json` keyed by the
   probe-stable lens key, bumps the entry version and reloads/applies the input
   profile without restarting the camera.
5. **The camera stays open.** Calibration is a route over the live preview;
   recording must be stopped first. The frame is one still from the normal
   camera → GL path, so there is no second capture pipeline.
6. **No device-specific code.** Everything is gated by the active camera and
   saved as data; a new phone/lens is supported by measuring it.

## Consequences

- The remaining Phase 5.5 gap is data and coverage, not plumbing: shipping
  matrices still need to be captured for the test devices and any supported
  phone.
- A single 3×3 matrix cannot undo an ISP tone curve, local tone mapping or
  vendor AWB. Expect "close and neutral", not identical output across brands.
- The captured frame is RGBA8888, so shadow precision is 8-bit. Lifting it to a
  16-bit readback is future work if the ΔE floor proves too high.
- One matrix per lens is illuminant-specific. Calibrating under D65 and shooting
  tungsten relies on AWB plus the neutral-patch WB offsets; CCT-indexed matrices
  or interpolation are backlog items.
- SDR and HLG share one entry today. Separate entries per transfer function are
  backlog work once HLG calibration is verified on device.
- The screen requires a physical chart and even lighting; chart auto-detection
  and corner detection are not implemented.

## Alternatives considered

- **Fit against the live preview** — simpler, but the preview includes the
  current input profile and creative look, so the matrix would absorb the look
  and change when the user switches profiles.
- **Solve in Dart** — keeps one language, but colour science belongs in the
  testable Kotlin core and the device database already lives in `:core:device`.
- **Author matrices externally only** — no on-device workflow, and every user
  would need a desktop tool; this is why the feature existed only as a schema.
- **Per-device LUTs** — hides sensor errors inside a creative object, breaks
  under new lighting and cannot be verified with ΔE. The shared look stays
  device-independent.
