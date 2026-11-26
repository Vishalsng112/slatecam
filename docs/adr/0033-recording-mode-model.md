# ADR 0033 — One recording-mode model

- **Status:** Accepted
- **Date:** 2026-11-26
- **Phase:** 3 (recording correctness) / 5.5 (shoot profiles)
- **Depends on:** ADR 0006 (aspect), ADR 0007 (shoot profiles)

## Context

The recording-mode concept was spread across layers:

- the core catalog knew width/height, but the aspect was a free string;
- the bridge computed the encoder bitrate from pixels;
- Flutter inferred the resolution family from the height, the aspect from a
  9/16 comparison, and kept its own size-preference ladder;
- every 10-bit decision travelled as a boolean.

Adding a new mode, resolution, aspect or bitrate policy meant changing all three
layers and keeping their rules aligned.

## Decision

1. **Core `RecordingMode` is the single model.** Its elements are
   `resolution` (`4K`/`1080`/`720`), `aspect` (`16:9`/`4:3`), `fps`, `bitrate`
   and `bitDepth` (`8`/`10`). `width`/`height` remain as the concrete Camera2
   stream size derived from resolution and aspect, but no caller infers a
   family, aspect or bitrate from them.
2. **One catalog owns the rules.** `RecordingModeCatalog` builds every mode,
   assigns resolution/aspect from the canonical size, applies the bit-depth
   verifier and computes the bitrate through `RecordingBitrate`.
3. **The bridge maps the model verbatim.** Pigeon `RecordingMode` carries
   resolution, aspect, bitrate and bit depth. The bridge no longer computes a
   bitrate; `startRecorder` uses `mode.bitrate`.
4. **The UI selects by elements.** Resolution, aspect, frame-rate and bit-depth
   chips compare model fields. The old `_sizesFor` preference table and
   height-based family/aspect inference are removed.
5. **Bit depth replaces the ten-bit boolean** in the exposed mode model.
   `CameraState.tenBit` remains the live recording state, not a mode property.

## Consequences

- New modes are added in one canonical-size list; the core fills the rest.
- Bitrate changes happen in `RecordingBitrate` and are visible to the whole
  interface.
- The Pad 3 mode card and the 4K/1080, 4:3/16:9, 24/25/30 and 8/10-bit chips
  were re-exercised after the refactor; selecting 4K 16:9 moved the take to
  `2160×3840` in portrait as before.
- The probe verifier still gates 10-bit per size/rate; no capability claim was
  loosened.

## Alternatives considered

- **Keep deriving aspect/resolution in Flutter** — rejected: that is the
  duplication this change removes.
- **Keep the bridge bitrate table** — rejected: it must agree with the catalog
  and had no other consumer.
- **Make `RecordingMode` an interface with getters** — unnecessary; the model
  is a value type and the catalog is the single policy owner.
