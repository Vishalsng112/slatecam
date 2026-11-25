# ADR 0021 — Manual white balance

- **Status:** Accepted
- **Date:** 2026-11-24
- **Phase:** 5 (white balance)
- **Depends on:** ADR 0016 (staged colour), ADR 0020 (picture profiles)

## Context

The camera exposed only the AWB presets the HAL advertises. There was no way to
set a colour temperature, so mixed lighting and matching between lenses were
left entirely to the device's auto white balance. The device database already
carries per-lens `wb_r_offset` / `wb_b_offset`, but those are fixed multipliers,
not a user control.

Camera2 offers three ways to take white balance over: `COLOR_CORRECTION_GAINS`
with `TRANSFORM_MATRIX`, a complete `COLOR_CORRECTION_TRANSFORM`, or the newer
`COLOR_CORRECTION_MODE_CCT` with `COLOR_CORRECTION_COLOR_TEMPERATURE` /
`COLOR_CORRECTION_COLOR_TINT`.

The OnePlus Pad 3 pass (build 2092) measured what is actually usable:

- `COLOR_CORRECTION_AVAILABLE_MODES` is `[0, 1, 2]` — TRANSFORM_MATRIX, FAST
  and HIGH_QUALITY. **CCT mode (3) is not offered**, and there is no
  `COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE`, so the new API is unavailable.
- `CONTROL_AWB_MODE_OFF` plus `COLOR_CORRECTION_GAINS` **is honoured**: forcing
  `(4, 1, 0.25)` turned the preview strongly red. So the request keys work.
- But gains derived only from a black-body model (green-normalised, near 1 at
  5600 K) leave a **strong green cast**, because they correct the illuminant and
  not the sensor's native white point. The sensor's white point is what the AWB
  loop and the CCM normally remove.
- Feeding the reported `SENSOR_COLOR_TRANSFORM1/2` back as the CCM also fails.
  The SDK documents those as XYZ → sensor, and the inverse composition with the
  calibration transform collapses to a near-diagonal gain matrix, so no usable
  CCM for an arbitrary temperature can be derived from public metadata.
- The HAL's own presets (daylight, shade, …) produce correct colour.

## Decision

1. **A HAL preset owns the sensor colour science.** Manual white balance selects
   the AWB preset nearest the requested temperature and lets the device do the
   white point and CCM. Nothing is fed back into `COLOR_CORRECTION_*`.
2. **The kelvin/tint residual is applied in the shader.** `ManualWhiteBalance`
   computes the small per-channel scale between the preset's nominal CCT and the
   requested temperature (`I(preset) / I(target)`, green-normalised) plus the
   tint. `GlPreviewRenderer.setManualWhiteBalance` multiplies it into the
   existing scene-linear `uInputWb` stage, next to the per-lens input
   calibration. The residual is bounded to 0.25–4.
3. **Manual is gated on preset availability.** `presetFor(kelvin, awbModes)`
   returns `null` when the sensor offers no standard preset; the Manual chip is
   then not built. A sensor with AWB `off` but no presets gets no dead control.
4. **The preset and the sliders describe one state.** Selecting a preset sets
   the Temperature/Tint controls to that preset's nominal CCT and tint 0, so the
   slider position and the active chip never disagree. The sliders stay visible
   for presets and Auto; touching either calls `setManualWhiteBalance` and the
   mode becomes manual.
5. **The preset follows the slider.** Crossing a preset boundary switches the
   AWB mode in the same request (`setManualWhiteBalance` → `applyAndEmit`).
   Kelvin/tint changes inside a preset only update the shader, so dragging is
   cheap and does not rebuild the capture request.
6. **`Off` is no longer offered separately.** AWB off with no gains is an
   unbalanced green cast; manual owns that state. A persisted `off` from an
   older build migrates to manual.
7. **The maths is pure and tested.** `ManualWhiteBalance` in `:core:camera` owns
   clamping, preset selection, the black-body approximation and the residual.
   `SlateCamBridge` only maps state to renderer uniforms; the camera module never
   imports GPU code.
8. **Persistence lives in `shoot-profile`.** Mode, kelvin and tint are stored
   with the other shoot preferences and restored before the camera opens.

## Consequences

- The user gets a continuous 2000–10000 K control and a −100…+100 tint, with the
  viewfinder reading the value (`3500K`).
- Absolute accuracy equals the accuracy of the HAL preset's nominal CCT plus the
  black-body approximation. Measured per-lens calibration remains the path to
  exactness; the tint is the manual escape hatch until then.
- The residual is a gain on already-developed pixels, so extreme temperatures
  enlarge noise and can clip a channel. The preset anchor keeps the residual
  small (roughly ±0.7 stop between neighbouring presets).
- Colour is applied once per frame in the existing shader; no second pixel path,
  and the encoder and analysis frame see the same balance.

## Alternatives considered

- **Camera2 gains only** — verified working as requests, but produced a strong
  green cast without the sensor CCM; rejected as incorrect.
- **Camera2 `TRANSFORM_MATRIX` with derived CCM** — the device's reported sensor
  transforms do not compose to a usable CCM; rejected as unverifiable.
- **`COLOR_CORRECTION_MODE_CCT`** — the right API, but not advertised by the
  device and absent below Android 15; kept as a future path if a probe verdict
  ever reports it.
- **Quantising to the presets only** — no continuous control, and the preset
  names do not expose their CCT; rejected for UX.
- **A kelvin-only control without tint** — rejected: tint is what makes manual
  white balance usable under mixed lighting and between lenses.
