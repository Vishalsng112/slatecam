# ADR 0034 — Unified exposure modes and a 48 fps shutter cap

- **Status:** Accepted
- **Date:** 2026-11-26
- **Phase:** 3 (recording correctness) / 5 (advanced capture)
- **Depends on:** ADR 0011 (metering), ADR 0019 (flicker-safe exposure)

## Context

Exposure had three operator modes (P/S/M) plus a separate "return to AE"
button, and the shutter could stretch to one display frame at 24 fps. Long
exposures produce the ghosting the operator sees on moving subjects.

The request is one exposure interface with four settings:

- AE — stock HAL auto exposure, the default;
- P — program auto;
- S — shutter priority;
- M — manual.

## Decision

1. **Four modes in `ExposureMode`.** Wire values are `AE`, `P`, `S`, `M`;
   `fromWire` falls back to AE. AE and P use HAL AE; S and M use AE OFF with
   the software exposure loop.
2. **AE is pure HAL.** The metering loop does nothing in AE until the cap
   forces a takeover. P keeps the software EV bias on top of HAL AE.
3. **Shutter cap in core.** `ExposureLimits.MAX_EXPOSURE_NS` is `1 s / 48`.
   `shutterMaxNs` is clamped to it, so the shutter slider, angle presets and
   manual/S requests cannot go slower than 1/48 s.
4. **HAL AE respects the cap by takeover.** If HAL AE reports an exposure
   longer than the cap, the controller switches that take to AE OFF at the
   capped shutter and lets the ISO loop keep the image exposed. The operator
   stays in AE/P; the cap is not silently broken.
5. **One UI row.** AE, P, S and M are chips beside metering; the old P/S/M
   circles and the separate return-to-AE button are removed.

## Consequences

- Default launch is AE. The shutter slider is disabled in AE/P and the card
  prints the 1/48 s cap.
- S and M snap the shutter to the flicker grid inside the cap; with 50 Hz
  anti-banding the slowest reachable grid value is 1/50 s, which is faster
  than the 1/48 s limit.
- Low light in AE/P may transition to software exposure without changing the
  selected mode; ISO then carries the exposure.
- The EV bias remains a P-only control; AE is deliberately untouched.

## Alternatives considered

- **Set SENSOR_EXPOSURE_TIME while AE is ON** — ignored by Camera2 HALs.
- **Rely on `CONTROL_AE_TARGET_FPS_RANGE`** — the stream rates are 24/25/30,
  so it cannot express a 1/48 s maximum.
- **Make AE software AE** — contradicts the request that the default is the
  HAL's AE.
- **Remove P** — P is the existing metered program mode and remains useful
  for matching a look across cameras.
