# ADR 0036 — Selectable VBR/CBR bitrate control

- **Status:** Accepted
- **Date:** 2026-11-26
- **Phase:** 3 (recording correctness)
- **Depends on:** ADR 0004 (recording pipeline), ADR 0033 (recording-mode model)

## Context

The recording-mode catalog derived one bitrate from the stream size and frame
rate, and `ClipRecorder` always requested `BITRATE_MODE_VBR` at that target.
Operators had no way to trade file size against quality, and no predictable
constant-rate option for vlog or movie takes where storage planning matters.

Bitrate is orthogonal to the recording-mode elements: the same 4K 30p 10-bit
mode can be recorded at several targets. Encoding it as extra mode variants
would multiply the catalog by the number of targets.

## Decision

1. **Bitrate is a separate operator setting.** `BitrateControl` (`vbr`/`cbr`)
   and the fixed CBR presets live in `RecordingBitrate` in `:core:camera`; the
   recording-mode catalog keeps owning the per-mode reference target.
2. **VBR is the system variable-bitrate mode** at the mode's reference target.
   **CBR picks one fixed 20/40/60/80/100 Mbps target.** 20–40 Mbps covers 1080p
   vlogging, 60–100 Mbps covers 4K movie work with grain headroom.
3. **The bridge persists the choice** in shoot preferences and passes the
   resolved target plus a boolean to `ClipRecorder`; the codec module stays
   independent of `:core:camera` and does not know model types.
4. **CBR is capability-checked.** `ClipRecorder` asks the selected HEVC
   encoder whether it advertises CBR. If not, the take proceeds at the same
   target in VBR instead of failing.
5. **10-bit HLG keeps the encoder default rate control.** Setting an explicit
   `KEY_BITRATE_MODE` forced an 8-bit Main stream on tested MediaTek encoders,
   so HLG ignores the CBR request rather than risking the bit depth.
6. **Pigeon exposes the state and command.** `CameraState` carries
   `bitrateMode`, `bitrateTarget` and `bitratePresets`;
   `setRecordingBitrate(mode, targetBps)` changes the persisted setting.

## Consequences

- Size, frame rate and bit depth continue to select a recording mode; bitrate
  changes do not reopen the camera session and apply to the next take.
- Changing bitrate is rejected while recording, like lens-set edits.
- CBR file sizes are predictable; VBR remains the safe default and the only
  mode for HLG.
- The bitrate row is no longer read-only, so the deck gains six chips (VBR plus
  five CBR presets) and an effective-target readout.

## Alternatives considered

- **Add bitrate variants to `RecordingMode`** — rejected: orthogonal setting,
  and the catalog would multiply by the number of targets.
- **Apply CBR without a support check** — rejected: a refused configure kills
  the take; falling back to VBR loses nothing but the constant-rate guarantee.
- **Force CBR for HLG too** — rejected: it regressed to 8-bit Main on MediaTek.
