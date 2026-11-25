# ADR 0010 — Stabilisation and focus modes

- **Status:** Accepted
- **Date:** 2026-11-24
- **Phase:** 5.5 (shoot profiles) / 3 (recording correctness)
- **Depends on:** ADR 0006 (aspect), ADR 0009 (lens shading)
- **Updated by:** ADR 0031 (one focus interface, CAF default)

## Context

The console had lost focus controls, and stabilisation was not exposed at all.
Camera2 separates three independent concepts:

- `LENS_OPTICAL_STABILIZATION_MODE` — mechanical OIS, `OFF`/`ON`, per lens;
- `CONTROL_VIDEO_STABILIZATION_MODE` — electronic stabilisation, `OFF`/`ON`
  (some devices also expose `PREVIEW_STABILIZATION`);
- `CONTROL_AF_MODE` — `OFF`, `AUTO`, `MACRO`, `CONTINUOUS_VIDEO`,
  `CONTINUOUS_PICTURE`, `EDOF`, depending on the module.

The devices differ: the Pad 3 advertises OIS `off` only, while the POCO X6 Pro
advertises `off`/`on`. Focus support also differs per lens (the front cameras
offer `off` only). A global switch would be wrong and a model list would violate
the probe rule.

## Decision

1. **OIS is per sensor and on by default where advertised.** If `on` is in the
   available OIS modes, the request uses it unless the operator turns it off.
   A sensor that only offers `off` hides the control; no vendor/model branch.
2. **EIS is on by default where advertised, with an off switch.** Electronic
   stabilisation crops and can introduce wobble, so a cinema operator must be
   able to disable it. It is a separate request from OIS.
3. **Both preferences persist per camera ID.** The bridge stores disabled IDs in
   `shoot-profile` prefs, the same mechanism as lens-shading correction. A
   different lens restores its own state.
4. **Focus mode is the camera's own enum.** The UI lists every mode the active
   camera reports, labelled for operators: `CAF`, `CAF-P`, `AF`, `MACRO`,
   `EDOF`, `MF`, plus the app's `SAF` when the lens has a focus range
   (ADR 0031). Continuous video AF is the default when available; otherwise the
   policy falls back to continuous picture, single AF, macro, EDOF, then
   manual.
5. **Manual focus is a consequence, not a separate concept.** Selecting `MF`
   (`off`) reveals the focus-distance slider. Moving the slider also switches to
   `MF`. Automatic modes never send `LENS_FOCUS_DISTANCE`.
6. **Stabilisation stays in the camera HAL.** The GL pipeline does not add a
   second stabiliser; OIS/EIS geometry is already applied by the camera before
   the single pixel path reaches the renderer.

## Consequences

- Pad 3: OIS control is hidden (only `off` advertised); EIS is ON by default and
  toggles. POCO X6 Pro: OIS and EIS are both ON by default; `dumpsys` reports
  `android.lens.opticalStabilizationMode [ON]` and
  `android.control.videoStabilizationMode [ON]`.
- Focus verification: `CAF → CONTINUOUS_VIDEO`, `AF → AUTO`, `MF → OFF` in the
  repeating request. The front lens with only `off` shows `MF` alone.
- Per-sensor persistence is verified by turning OIS off, restarting, observing
  `OFF`, then restoring `ON`.
- OIS and EIS are requested together where both exist. A vendor that cannot
  combine them will ignore one; the first-`CaptureResult` check reports the
  mismatch instead of silently pretending it was applied.

## Alternatives considered

- **One global stabilisation switch** — rejected: shoot sets alternate lenses
  with different OIS hardware.
- **Default EIS off** — rejected: the default should be the safer handheld image;
  the operator can turn it off when the crop/wobble is undesirable.
- **Synthetic force `OFF` for OIS on unsupported lenses** — unnecessary; the
  request is simply omitted when the sensor does not advertise it.
- **Keep only a boolean auto-focus control** — rejected: it cannot express the
  difference between single AF, continuous video AF and manual focus.
