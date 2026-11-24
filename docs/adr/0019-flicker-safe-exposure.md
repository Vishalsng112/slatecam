# ADR 0019 — Flicker-safe manual exposure grid

- **Status:** Accepted
- **Date:** 2026-11-24
- **Phase:** 5 (exposure)
- **Depends on:** ADR 0011 (metering)

## Context

Camera2 applies `CONTROL_AE_ANTIBANDING_MODE` only while auto-exposure owns the
sensor. M and S fix `SENSOR_EXPOSURE_TIME`, so the HAL stops choosing
flicker-safe exposures and the shutter slider could land on any value. Under
50 Hz lighting the visible banding then appears at 100 Hz; under 60 Hz at
120 Hz.

## Decision

1. **Snap in Kotlin, not Flutter.** `FlickerGrid` in `:core:common` owns the
   grid, and `CameraController` applies it in `setShutterNs`,
   `setShutterAngle`, `setExposureMode` (when leaving P), `setAntibanding` and
   the per-lens capability refresh. Flutter renders the resulting value and
   step; it does not decide exposure.
2. **Whole flicker periods.** 50 Hz uses 10 ms multiples, 60 Hz uses 1/120 s
   multiples. Half periods are not used even though a symmetric fluorescent
   waveform integrates evenly over them, because LED and ballast waveforms are
   not guaranteed symmetric.
3. **`auto` falls back to the region's mains frequency.** The detected 50/60 Hz
   value is not readable once AE is off. A 60 Hz country list is used, with
   50 Hz as the default for unknown or empty regions. This is environment data,
   not a per-model branch.
4. **`off` stays continuous.** No grid is imposed when the user explicitly
   disables anti-banding.
5. **The slider is quantised.** `CameraState.shutterStepNs` exposes the step so
   the control has discrete positions and a hint (`Flicker-safe 50 Hz · 1/100`)
   instead of snapping only after release. In P the slider keeps its continuous
   range because the HAL owns exposure.
6. **Angle presets are snapped too.** A chosen angle is converted to exposure
   and then to the nearest safe grid value; the UI shows the resulting angle.
   This may differ from the nominal preset (for example 180° at 30 fps on a
   50 Hz grid becomes 20 ms / 216°).

## Consequences

- Manual exposure no longer flickers on the test devices at 50 Hz, 60 Hz or
  auto, and switching anti-banding re-snaps the held shutter immediately.
- Faster-than-1/100 (or 1/120) shutters are not available while the grid is
  active. Daylight shooting needs ND rather than a short shutter; this is the
  trade-off the flicker-free requirement asks for.
- `auto` in a 60 Hz region depends on a correct device region. A wrong locale
  picks the wrong grid; an explicit 50/60 Hz selection always wins.
- No extra camera session or pixel path is introduced.

## Alternatives considered

- **Leave the shutter continuous and rely on the HAL** — rejected: the HAL
  ignores anti-banding in M/S.
- **Quantise only in the Flutter slider** — rejected: commands can arrive
  without the UI, and Kotlin owns state.
- **Snap to half flicker periods** — rejected: not safe for non-symmetric LED
  waveforms.
- **Require the user to pick 50/60 Hz explicitly** — kept as the precise mode,
  but `auto` still needs a usable default for manual exposure.
