# ADR 0009 — Lens-shading (vignette) correction

- **Status:** Accepted
- **Date:** 2026-11-24
- **Phase:** 3 (recording correctness) / 5.5 (per-lens shoot sets)
- **Depends on:** ADR 0005 (colour pipeline), ADR 0007 (shoot profiles)

## Context

Camera2 exposes `SHADING_MODE` (`OFF`, `FAST`, `HIGH_QUALITY`) to compensate
for the lens/sensor vignette measured by the module vendor. The controller
previously forced `SHADING_MODE_OFF` whenever the camera advertised it, alongside
hot-pixel, aberration and distortion off. That made the raw vignette visible in
the viewfinder and in recorded clips: in a fixed 50%-grey test the corner/centre
ratio was 0.28 with correction off.

Every sensor has a different shading map and a different rest of the pipeline.
A single global switch would also be wrong for shoot sets that alternate lenses.

## Decision

1. **Correction is on by default.** If the sensor advertises `HIGH_QUALITY` or
   `FAST`, the request uses the quality-ordered best mode. `OFF` is only sent
   when the operator disables correction, or when the sensor offers nothing
   better.
2. **The toggle is per sensor.** The UI writes the choice for the active camera
   ID; the bridge persists disabled IDs in `shoot-profile` prefs. Switching to a
   different lens restores that lens's choice. New sensors start corrected.
3. **The choice is a capture request, not a shader effect.** Lens shading is a
   measured per-module map; the HAL must apply it before the frame leaves the
   camera. The renderer never invents a vignette correction.
4. **Other processing remains explicit.** Hot-pixel, aberration and distortion
   stay off unless a later pass exposes their own toggles. Vignette correction
   does not silently change them.
5. **Unsupported sensors stay honest.** If a camera only offers `OFF`, the
   Processing card says correction is not offered rather than showing a control
   that cannot work.

## Consequences

- With correction on, the Pad 3 corner/centre luma ratio rose from 0.284 to
  0.455 in the same exposure; `dumpsys media.camera` reports
  `android.shading.mode [HIGH_QUALITY]`. The POCO reports the same mode.
- The preference survives process restart because it is stored by camera ID.
- The existing first-`CaptureResult` check now verifies the requested shading
  mode and logs `HAL ignored SHADING_MODE=...` if a vendor drops it.
- `ShadingCorrection` is a pure helper with unit tests; controller integration
  is verified on hardware because it is a Camera2 request.

## Alternatives considered

- **Leave `SHADING_MODE` unset and rely on the HAL default** — rejected: vendor
  defaults differ and the capture request dump would no longer explain the
  image. An explicit request is auditable.
- **Correct vignetting in the GL shader** — rejected: no per-lens measured
  shading map, and it would double-correct when the HAL also applies its map.
- **One global toggle** — rejected: shoot sets switch between physically
  different lenses, each with its own module calibration.
