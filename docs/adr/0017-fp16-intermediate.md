# ADR 0017 — FP16 encoder intermediate with dithered output

- **Status:** Accepted
- **Date:** 2026-11-24
- **Phase:** 4/5 (colour pipeline / recording quality)
- **Depends on:** ADR 0016 (staged colour architecture)

## Context

The colour pipeline drew the camera OES texture straight into the preview and
encoder window surfaces. The encoder input surface on the test devices is
RGBA8888; the 10-bit EGL window config is advertised but every swap fails with
`EGL_BAD_SURFACE`. The encoded stream is Main10, but the source precision stops
at the 8-bit surface.

The scene-linear phase needs a high-precision intermediate before it can be
honest. We also get an immediate quality win from dithering the final 8-bit
write.

## Decision

1. **Render the encoder path through an FP16 FBO.** When
   `GL_EXT_color_buffer_half_float` and `GL_OES_texture_half_float` are present,
   the renderer creates an `RGBA16F` FBO at the capture size, draws the camera
   frame and creative look into it, then copies it 1:1 to the encoder surface.
2. **The preview and analysis stay on the direct path.** The preview has a
   different HLG→SDR tone-map and overlays; analysis intentionally reflects the
   current monitoring look. Sharing one intermediate would change one of those
   semantics. Only the quality-critical encoder path uses the FBO.
3. **Dither the 8-bit encoder write.** The copy shader adds an ordered 2×2
   dither at 1/255 before writing the RGBA8888 surface. This reduces banding
   introduced by the final 8-bit surface.
4. **Fallback is explicit.** If the FP16 texture is unsupported, allocation
   fails, or the FBO is incomplete, the renderer logs once and uses the existing
   direct encoder path. No recording is blocked by an optional precision path.
5. **This is not true 10-bit delivery.** The encoder input surface is still
   RGBA8888. The FP16 intermediate improves processing precision and enables
   dithering; a true 10-bit source needs a 10-bit/P010 encoder surface or buffer
   input, which remains a separate investigation.

## Consequences

- Pad 3 build 2081: 1920×1080 and 3840×2160 HLG recordings both log
  `FP16 encoder intermediate … (RGBA16F)`, complete frames at 30 fps, and remain
  `Main 10 / yuv420p10le` in the container.
- The encoder path does one extra full-resolution draw and a copy. The preview
  path is unchanged, so live-view cost does not change.
- 4K FP16 needs ~66 MB for the FBO; supported devices allocate it, others fall
  back.
- The first frame of a new recording may create the FBO; it is retained for the
  session and recreated only when the capture size changes.
- The intermediate is the foundation for the future scene-linear shader; it is
  not yet a scene-linear buffer itself.

## Alternatives considered

- **Use a 10-bit EGL encoder window config** — rejected on evidence: swaps fail
  with `EGL_BAD_SURFACE`.
- **One shared intermediate for preview and encoder** — rejected: the preview
  needs HLG→SDR and overlays; the encoder must not have overlays, and its signal
  must stay HLG.
- **Read back FP16 and convert to P010 input buffers** — a possible true 10-bit
  route, but a separate recorder architecture and CPU cost.
- **No intermediate, just dithering in the direct shader** — loses the
  high-precision buffer that the scene-linear phase needs.
