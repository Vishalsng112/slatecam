# ADR 0014 — Live colour-science trim

- **Status:** Superseded by ADR 0020
- **Date:** 2026-11-24
- **Phase:** 4/5 (colour pipeline)
- **Superseded by:** ADR 0020 (picture profiles)
- **Depends on:** ADR 0005 (colour pipeline), ADR 0009 (lens shading)

## Context

Picture profiles supplied a fixed base look (standard, SlateLog, mono, warm LUT),
and white balance was a Camera2 mode. Operators had no live colour-science
controls between the base profile and the LUT: exposure trim, contrast,
saturation, and a warm/cool + green/magenta trim.

The renderer already applies a 3×3 input calibration, exposure, contrast,
saturation, log/mono and a 3D LUT in one shader. Adding a manual trim there keeps
the single pixel path and bakes the same look into preview and recording.

## Decision

1. **A pure `ColorScienceTuning` value in `:core:profile`.** It holds exposure
   in stops, contrast and saturation multipliers, and normalized temperature and
   tint. All ranges and clamps live in core and are unit-tested.
2. **Tuning is applied on top of the profile.** The bridge composes:
   `profile.exposure × 2^EV`, `profile.contrast × trim.contrast`,
   `profile.saturation × trim.saturation`, then the profile's log/mono/LUT.
   LUT profiles get the same trim before the LUT.
3. **Temperature/tint is a three-channel shader trim.** Warm raises red and
   lowers blue; positive tint adds magenta and removes green. The gains are
   normalised so overall luma stays close to the input. The input calibration
   matrix runs before this trim.
4. **The trim is global, live and persisted.** It is not a new profile; named
   profile editing remains a separate future feature. Values persist in the
   shoot prefs and are restored on launch.
5. **Flutter only renders state.** The Colour science card exposes five sliders
   and a reset. It does not touch pixels.

## Consequences

- The preview and the encoded clip receive the same trim because both are drawn
  through the same shader.
- The trim also affects the analysis frame (histogram/waveform/metering/focus);
  it is an operator-visible look, not a hidden calibration. A pre-look metering
  pass remains a backlog item.
- Pad 3: a warm temperature drag moved the persisted value to 0.636 and the
  preview visibly warmed; reset restored neutral.
- Named profile saving/editing is still a separate backlog item.

## Alternatives considered

- **Save each trim as a new profile** — premature; live trim and named profiles
  are different workflows.
- **Apply the trim only to the preview** — rejected: recorded clips would not
  match the viewfinder.
- **Use Camera2 colour transforms** — not portable and not per-frame tunable.
