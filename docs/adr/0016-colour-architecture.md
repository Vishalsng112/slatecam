# ADR 0016 — Staged colour architecture: math, per-lens data, probe

- **Status:** Accepted; tone math and SlateLog updated by ADR 0020
- **Date:** 2026-11-24
- **Phase:** 4/5/7 (colour)
- **Depends on:** ADR 0005 (colour pipeline), ADR 0009 (lens shading)

## Context

The proposed cinema colour architecture wants scene-linear normalization,
per-lens CCM/WB, tone shaping, a 3D LUT, and separate encoder/preview/analysis
outputs. The current app has a single display-encoded shader and an RGBA8888
intermediate, so implementing the full pipeline now would either band badly or
leave the 10-bit stream at 8-bit source precision.

We agreed to ship the safe foundation first: core colour math, per-lens
calibration data with identity defaults, and probe data for the future manual
WB/tone-map path.

## Decision

1. **Core owns the tone math.** `ToneShaper` defines contrast around the 18%
   pivot, a shadow-toe lift, and a soft highlight knee in linear 0..1.
   `SlateLog` already provides encode/decode. `PictureProfile` carries pivot,
   knee and toe as data; delivery transfer and gamut remain owned by the
   recording mode, not the profile.
2. **Calibration is per lens, keyed by the probe-stable lens key.** The device
   database gains optional `lens_key`, `wb_r_offset`, `wb_b_offset` and
   `shadow_lift`; the existing `matrix` is the CCM. Lookup is lens key → camera
   id → wildcard. Missing data is identity, so existing devices behave exactly
   as before.
3. **The current shader applies the per-lens WB/shadow trims as an interim
   display-space stage.** This is useful and does not require the scene-linear
   pipeline. The CCM is already applied through the input matrix. The linear
   phase will move the same data into the working-space stage.
4. **Probe the future path, do not force it.** The probe records
   `TONEMAP_AVAILABLE_TONE_MAP_MODES`, `TONEMAP_MAX_CURVE_POINTS` and
   `COLOR_CORRECTION_AVAILABLE_MODES` (schema 1.11.0). The app does not force
   `TONEMAP_MODE_CONTRAST_CURVE` or manual AWB yet: those are optional,
   frequently ignored for video/HLG10, and must be verified in `CaptureResult`
   before a feature is enabled.
5. **Keep the existing LUT path.** `.cube` parsing and trilinear sampling
   already exist. A 65³ LUT needs atlas tiling or GLES3 3D textures; that is
   deferred.
6. **Do not claim an ISP bypass.** The Camera2 video path still uses the ISP.
   We minimise processing (edge/NR off, shading correction) and do creative
   math in the GPU.

## Deferred decisions

- True 10-bit encoder input. An FP16 encoder intermediate and dither now ship
  (ADR 0017); the encoder surface is still RGBA8888, so true 10-bit delivery is
  still pending.
- Working space (recommended linear Rec.2020) and SDR/HDR delivery conversion.
- White-balance ownership (recommended camera AWB locked/neutral + GPU
  per-lens CCM/WB) and a Kelvin/tint target model.
- Pre-look analysis pass for true scene-referred metering/focus.
- Measured per-lens ColorChecker data; until then identity is honest.

## Consequences

- Per-lens schema, `InputProfile` trims and probe fields are covered by JVM
  tests; the picture-profile look math is covered by `PictureProfileTest`.
- No UI or device behaviour changes unless a calibration entry is added.
- `ColorCalibration` samples are linear Rec.2020, matching where the shader
  applies `uInputMatrix`; the neutral patches now derive the WB offsets. The
  in-app capture/solve flow is ADR 0027.
- The live `ColorScienceTuning` trim was removed in ADR 0020; the Custom
  picture profile is now the single creative tone/colour path and is not a
  substitute for per-lens calibration.
- The profile and the per-lens input stage compose in a defined order:
  input WB/shadow/CCM → input exposure/saturation → black level/black gamma →
  colour matrix/phase/depth → saturation → knee → output transfer → look gamma
  → creative LUT → overlays.

## Alternatives considered

- **Implement the full scene-linear shader now** — rejected until the 10-bit
  intermediate exists; would degrade quality and the 10-bit claim.
- **Force a camera tone curve for an ISP bypass** — rejected: not portable,
  often ignored, and risky with HLG10.
- **Key calibration by physical Camera2 ID only** — rejected: IDs can renumber;
  the lens key is already the profile identity.
