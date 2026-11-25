# ADR 0018 — Scene-linear colour shader and output routing

- **Status:** Accepted (profile parameters and SlateLog updated by ADR 0020)
- **Date:** 2026-11-24
- **Phase:** 4/5 (colour pipeline)
- **Depends on:** ADR 0005 (colour), ADR 0016 (colour architecture),
  ADR 0017 (FP16 intermediate)

## Context

The previous shader operated on display-encoded camera values. The agreed
architecture called for a linear working space, per-lens normalisation in that
space, profile tone shaping, a creative LUT, and distinct encoder, analysis and
preview outputs.

## Decision

1. **Working space is linear Rec.2020.**
   - HLG input: apply the HLG inverse OETF (`0.17883277`, `0.28466892`,
     `0.55991073`) to scene-linear BT.2020.
   - SDR input: apply the BT.709 inverse OETF, then the BT.709→BT.2020 matrix.
2. **Per-lens normalisation runs in linear.** Per-lens WB gains, the CCM
   (`uInputMatrix`), exposure trim, saturation trim and shadow lift are applied
   before the creative transform. The CPU `InputProfile` reference uses the same
   Rec.2020 luma weights.
3. **Profile tone shaping is linear and per channel.** Contrast around the 18%
   pivot, shadow-toe lift, highlight knee and saturation are applied in working
   space. The GLSL matches `PictureProfile.render()` (ADR 0020 replaced the
   earlier `ToneShaper`).
4. **The creative LUT is applied in the delivery/preview transfer domain.** The
   existing `.cube`/built-in LUTs are display-referred; applying them to linear
   values would break their look. Order: linear look → output transfer →
   creative LUT → overlays.
5. **Output routing is explicit.**
   - Encoder: HLG OETF when the recording mode is HLG, otherwise BT.2020→BT.709
     then BT.709 OETF. No preview overlays, no monitoring transform.
   - Analysis: the same delivery signal as the encoder, without overlays or
     monitoring transforms, so waveforms/false colour/metering follow what is
     recorded.
   - Preview: for HLG input, apply the BT.2100 OOTF and normalise the
     1000-nit reference to a 100-nit SDR display (with a soft shoulder), then
     BT.2020→BT.709 and BT.709 OETF or SlateLog, then the creative LUT and
     overlays. SDR input skips the extra mapping. SlateLog is a monitoring view
     only; the encoder always receives HLG or BT.709. This keeps the container
     SPS/VUI honest and stops the HLG viewfinder sitting darker than SDR.
6. **FP16 encoder intermediate remains.** The scene-linear shader writes the
   encoder delivery into the FP16 FBO (ADR 0017), then the dithered copy feeds
   the RGBA8888 encoder surface. True 10-bit delivery is still pending a
   10-bit/P010 encoder input.
7. **The creative LUT is baked into both encoder and preview.** A separate
   monitoring-only LUT and a recordable SlateLog delivery are not implemented;
   they remain future work.

## Consequences

- Pad 3 build 2084:
  - HLG 1080p and 4K record Main10/arib-std-b67; SDR 1080p records Main/BT.709.
  - SlateLog selected: the preview is flat/log, the encoded clip is standard
    HLG. The UI says “Monitoring view only”.
  - FP16 intermediate logs and 30 fps cadence are unchanged.
  - Focus/metering remain stable on the delivery analysis frame.
- The preview look changes slightly because it now uses the proper HLG inverse
  and BT.2020→BT.709 conversion instead of the previous approximate tonemap.
- SDR and HLG recordings use the linear profile contrast/saturation/knee; the
  image is no longer shaped in BT.709-encoded space.
- Manual white balance, measured per-lens CCM data, a monitoring-only LUT and a
  recordable log delivery remain in the backlog.

## Alternatives considered

- **Apply the LUT in linear** — rejected: existing LUTs are display-referred.
- **Record SlateLog into the HLG container** — rejected: misleading metadata.
- **Separate preview and encoder intermediates** — more memory and passes; the
  preview remains direct, the encoder uses FP16.
- **Working space linear Rec.709** — simpler for SDR but loses HLG/BT.2020
  headroom; Rec.2020 is the delivery-native choice.
