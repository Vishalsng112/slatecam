# ADR 0011 — Exposure metering patterns and response speed

- **Status:** Accepted
- **Date:** 2026-11-24
- **Phase:** 5 (advanced capture)
- **Depends on:** ADR 0005 (colour pipeline), ADR 0010 (focus/stabilisation)

## Context

The app exposed P/S/M but used only the HAL's default AE in P and a fixed
histogram-percentile loop in S. That cannot express the metering patterns a
mirrorless camera offers (multi, centre, spot, average, highlight) and gives no
control over how quickly exposure follows a change.

Camera2's `CONTROL_AE_REGIONS` is a single weighted rectangle. It cannot express
five patterns, highlight priority, or a response curve, and its coordinate space
is the sensor array rather than the viewfinder. The GPU already reads a 160×90
upright analysis frame at roughly 5 Hz for the histogram and waveform.

## Decision

1. **Metering is a pure core module.** `:core:camera` owns `MeteringMode`,
   `MeterSample`, `ExposureMeter` and `ExposureController`. They contain no
   Android types and are covered by JVM tests.
2. **The GPU adds a luma grid to the existing analysis pass.** `:core:gpu`
   accumulates a row-major 32×18 normalised luma grid from the same readback
   that produces the histogram and waveform. There is no second camera path and
   no extra readback.
3. **P and S apply the same meter differently.**
   - P keeps the HAL AE and adds an EV bias through
     `CONTROL_AE_EXPOSURE_COMPENSATION`; the meter decides the bias, the HAL
     keeps control of shutter and ISO.
   - S keeps shutter priority and applies the EV correction to ISO.
   - M leaves exposure untouched.
4. **Patterns are spatial weights, not device modes.**
   - Multi: reject the extreme 5% of cells, then a mild centre-weighted average.
   - Center: Gaussian centre-weighted average.
   - Spot: small Gaussian spot.
   - Average: unweighted mean of the whole grid.
   - Highlight: the 90th percentile, protect-only. The exposure is pulled down
     only when that highlight exceeds 0.85; a flat scene produces zero
     correction instead of being brightened to 0.85.
5. **Speed is a documented curve.** The 0–1 value sets both proportional gain
   (0.2→1.0) and the per-second EV limit (0.4→4.0 EV/s), with a 0.06 EV dead
   band. A slow setting glides; a fast setting snaps. P compensation uses the
   same curve but converts to the camera's compensation step, accumulating
   fractional steps so a slow setting can still move.
6. **Flutter only renders state.** The bridge persists `metering_mode` and
   `metering_speed` in the shoot prefs and pushes the controller's state; the
   console shows chips and a speed slider in the Exposure card.

## Consequences

- Metering works identically on every camera; there is no brand/model branch.
- Measured on the Pad 3: Multi went from 0.506 luma (EV −0.17, compensation 1)
  to 0.449 (compensation 2) and settled at 0.467 (inside the dead band). In S,
  the same meter raised ISO from 100 toward 1600 within three seconds.
- Highlight is intentionally protect-only; it cannot brighten a flat scene.
- P is limited by the camera's AE compensation range (typically ±3 EV). S is
  limited by the ISO range, which is visible in the existing readout.
- The meter applies to the *analysis frame*, which is upright and 16:9. For 4:3
  modes the grid is sampled from the same analysis viewport; centre/spot remain
  centred and the unit tests define the spatial behaviour.
- Monitoring overlays still use the original histogram/waveform; `FrameAnalysis`
  carries all three products in one callback. Metering uses that same analysis
  frame, so a very strong LUT/log profile can bias the target; a pre-look
  metering pass is recorded in the backlog.

## Alternatives considered

- **Camera2 AE regions only** — rejected: one weighted rectangle cannot express
  five patterns or highlight protection.
- **Replace HAL AE with a full software exposure loop in P** — rejected: it
  would fight auto-flicker, face metering and vendor AE convergence, and risks
  visible pumping. A bias loop keeps the HAL in charge.
- **Compute metering in Flutter** — rejected: pixels and exposure state belong
  in Kotlin, and it would require a second pixel path.
- **Per-device metering curves** — rejected: the patterns are scene weights, not
  hardware behaviour; the response speed is already user-adjustable.
