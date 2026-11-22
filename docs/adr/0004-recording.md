# ADR 0004 — Recording pipeline

- **Status:** Accepted
- **Date:** 2026-11-23
- **Phase:** 3 (partial)
- **Depends on:** ADR 0003 (modules)

## Context

Recording must reuse the single pixel path from Phase 2 rather than opening a
second camera-to-encoder route. It also has to survive device quirks: MediaTek
encoders ignore `KEY_BIT_RATE` in their default mode, and a MediaCodec input
surface rendered with a non-recordable EGL config receives uninitialised
buffers.

## Decision

1. **The recorder lives in `:core:codec`.** `ClipRecorder` owns the HEVC and AAC
   encoders, the muxer, and the drain threads. It exposes the encoder input
   surface and returns it to the caller.
2. **`:core:gpu` renders each frame twice** — once to the Flutter preview
   surface, once to the encoder surface — from the same context and texture.
   There is still exactly one decode of the camera texture per frame.
3. **Audio is optional.** Without `RECORD_AUDIO` the recorder produces a
   video-only file rather than failing.
4. **Bitrate mode is explicit** (`BITRATE_MODE_VBR`). Relying on `KEY_BIT_RATE`
   alone produced 150 Mbps on this MediaTek encoder.
5. **The EGL config requests `EGL_RECORDABLE_ANDROID`**, falling back if the
   driver rejects it. This is required for correct encoder input on some GPUs.
6. **Output goes to MediaStore** (`Movies/SlateCam/`) with `IS_PENDING` set
   until the muxer finalizes, so clips appear in galleries without a storage
   permission. A private file is used only if the insert fails.
7. **Thermal guard stops recording at `THERMAL_STATUS_SEVERE`** or worse and
   reports why, rather than letting the device throttle silently.

## Consequences

- Two EGL window surfaces per frame cost one extra draw; measured output is
  correct and the frame rate is unchanged in practice.
- 10-bit HLG recording needs a second EGL config (10-bit, BT.2020/HLG) and a
  second encoder instance; the current pipeline is 8-bit only.
- The muxer cannot add tracks after `start()`, so the drain loop waits for both
  formats. A future failure mode to watch is an audio format that never
  arrives; the loop currently treats that as a stall.
- Files live in app storage and are invisible to galleries. Phase 3 completion
  requires MediaStore insertion with a relative path.

## Alternatives considered

- **Camera → encoder directly** — rejected: breaks the single pixel path and
  skips the GL pipeline that Phase 4's profiles and LUTs depend on.
- **`MediaRecorder`** — rejected: no control over the surface, bitrate mode, or
  colour, and no path to 10-bit HLG.
- **One encoder per resolution with reconfiguration** — rejected for now:
  stop/start per clip is simpler and correct.

## Amendment — HLG gated off

HLG recording is compiled in but disabled (`hlgEnabled = false` in
`SlateCamBridge`). On the POCO the encoder's `outputFormat` reported
`profile=2` and `transfer=7`, while the actual bitstream was 8-bit Main,
full-range. Writing an HLG tag over that would misrepresent the footage.

The gate stays closed until the recorder verifies the encoded bitstream itself
(HEVC SPS `general_profile_idc`, bit depth, range). The same check must be added
to `hevc_main10_encode` in the prober, because it currently trusts the codec's
own claim.
