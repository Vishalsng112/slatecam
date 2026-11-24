# ADR 0020 — Picture profiles and the creative look pipeline

- **Status:** Accepted
- **Date:** 2026-11-24
- **Phase:** 4/5 (colour)
- **Depends on:** ADR 0016 (colour architecture), ADR 0018 (scene-linear shader),
  supersedes ADR 0014 (live colour-science trim)

## Context

SlateCam had four ad-hoc profile ids (`standard`, `slatelog`, `mono`, `warm`) and
a separate Colour science card with live exposure/contrast/saturation/temperature
/ tint. That duplicated controls and could not express a Sony-style picture
profile. The working space is linear BT.2020 and the encoder must keep truthful
HLG/Rec.709 metadata, so log and gamut names cannot change the container.

## Decision

1. **Three picture profiles.**
   - `standard` — the camera's native look. Identity: no creative tone curve,
     matrix, saturation, black level or gamma; only technical input/output
     transforms and per-lens calibration run.
   - `slatelook` — Sony S-Cinetone-inspired: the S-Cinetone shoulder, a soft
     manual knee, middle black gamma, slightly muted saturation, a touch of
     warm phase and a Cinema matrix.
   - `custom` — user-editable settings, persisted across restarts.
2. **Custom settings exposed now.**
   - Black level −15…+15
   - Gamma: standard, movie, still, cine1–cine4, ITU709, ITU709(800%),
     S-Cinetone
   - Black gamma range (wide/middle/narrow) and level −7…+7
   - Knee auto/manual, auto max point 90–100%, auto sensitivity high/mid/low,
     manual point 75–105% and slope −5…+5
   - Color mode: movie, still, cinema, pro, ITU709 matrix, B&W, BT.2020, 709
   - Saturation −32…+32, colour phase −7…+7, colour depth R/G/B/C/M/Y −7…+7
   - Reset
3. **Gamma presets are creative look curves in the delivery domain.** They are
   applied after the HLG/Rec.709 output transfer and before the creative LUT, so
   the container transfer is untouched. S-Log2/3, HLG1/2/3 and S-Gamut modes are
   deliberately not offered: recording them in an HLG/Rec.709 container would
   make the metadata dishonest.
4. **Deterministic pipeline order.**
   input EOTF → per-lens WB/CCM/shadow → creative black level → black gamma →
   colour matrix → colour phase/depth → saturation → knee → output transfer →
   look gamma → creative LUT → overlays.
5. **Creative LUT is separate from the profile.** The profile list stays at three;
   external `.cube` LUTs are selected independently and persisted.
6. **The Colour science card is removed.** Custom profile is the single creative
   editor. `ColorScience.kt`, `ToneShaper.kt` and the five live-trim commands are
   deleted rather than kept as a second colour path.
7. **Kotlin owns the look.** `PictureProfile.render()` maps the user ranges to
   primitive renderer values; Flutter renders the Pigeon snapshot and sends one
   `setProfileSettings` command.
8. **Approximations are explicit.**
   - The gamma presets are parameterised look curves (power, contrast, toe,
     shoulder), not bit-exact Sony/BT.709 transfer functions.
   - The knee “auto” mode is a static mapping from max point/sensitivity because
     continuous auto-knee tracking is not implemented yet.
   - Detail controls are not exposed in this pass.

## Consequences

- Standard is a true no-look path: `colorOps=false`, identity gamma and no
  creative values, while per-lens calibration still applies.
- SlateLook visibly differs from Standard without changing the encoded transfer.
- SlateLog was removed; a flat monitoring view is no longer offered. The
  standard profile is the camera look and SlateLook is the S-Cinetone-style
  look.
- Custom settings and the active profile persist across restarts; the creative
  LUT selection persists separately.
- Strong looks affect the analysis frame (waveform/false colour and, for now,
  metering). A pre-look metering signal remains backlog work.
- The six-axis colour depth and the look gamma add shader work; both are gated
  so Standard pays only the cheap path.

## Alternatives considered

- **Keep the Colour science card beside the profile editor** — rejected: two
  overlapping colour paths and two sources of truth.
- **Let gamma change the container transfer** — rejected: false SPS/VUI.
- **Offer S-Log/S-Gamut now** — rejected: cannot be tagged honestly until a
  recordable-log delivery exists.
- **Ship the full Sony detail submenu** — deferred: it needs a spatial pass and
  the V/H and B/W balance controls have no clean equivalent here.
- **Per-lens picture profiles** — deferred: profiles are creative and global;
  per-lens calibration stays in the device database.
