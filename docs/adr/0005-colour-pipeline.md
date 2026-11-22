# ADR 0005 — Colour pipeline and picture profiles

- **Status:** Accepted
- **Date:** 2026-11-23
- **Phase:** 4 (in progress)
- **Depends on:** ADR 0003 (modules)

## Context

Profiles and monitoring tools must not fork the pixel path. Preview and
recording already share one GL pipeline, so colour transforms belong in the
shader, and any overlay (zebra, later guides) must be applied only to the
preview target.

The colour maths also has to be testable without a device, which means the
curve and LUT handling live in a pure JVM module.

## Decision

1. **`:core:profile` is pure Kotlin** and owns `SlateLog`, `PictureProfile` and
   `Lut3D` (including a `.cube` parser and CPU trilinear sampling). Unit tests
   run on the JVM.
2. **Profiles are data**, not behaviour: exposure, contrast, saturation, log
   on/off, mono on/off. The shader applies them; preview and recording see the
   same transform.
3. **SlateLog is a specific, documented curve**: `0.738 + 0.169 * log2(x + 0.07)`
   with a 0.07 toe, so:
   - 0 linear → 0.09 (lifted black)
   - 18% grey → 0.40
   - 100% white → 0.754
   Decoding is the exact inverse. The toe is mandatory: without it the curve
   crushes dark scenes instead of flattening them.
4. **Zebra is preview-only.** The encoder pass is rendered with overlays
   disabled, so monitoring marks never end up burned into a recording.
5. **LUT application uses a 2D-packed 3D texture**, not a 3D sampler, so the
   pipeline stays on GLES 2.0. A 17³ LUT becomes a 289×17 texture; the shader
   samples the two nearest blue slices and blends. The upload is deferred until
   the EGL context exists — applying a profile before the camera opens otherwise
   produces a black preview.
6. **Histogram and waveform come from one 160×90 FBO readback**, done every fifth
   frame and published at ~5 Hz. The histogram is 64 luma bins; the waveform is
   64 columns × 16 bins. They are analysis only: overlays never reach recordings.

## Consequences

- A profile change costs no pipeline rebuild: it is four uniforms.
- Log and mono apply to recordings too, which is intended (they are looks, not
  monitoring aids). Zebra is the exception and is excluded.
- The log curve operates on an approximated linear signal (`pow(c, 2.2)`), not a
  measured sensor response. It is a look, not a scene-referred camera log; this
  must be stated in user documentation.
- LUTs are parsed, sampled on the CPU for tests, and applied on the GPU;
  user-supplied `.cube` files still need a picker and a cache.
- The waveform's horizontal axis is sensor-oriented because the analysis pass
  renders the unrotated buffer while the preview is rotated in Dart. Histogram
  is unaffected; aligning waveform columns is a follow-up.
- The in-app profile dropdown was not reachable through `adb input tap`; profile
  changes were verified through launch extras. Worth revisiting for real users.
