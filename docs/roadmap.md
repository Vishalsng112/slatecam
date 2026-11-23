# Roadmap

This is the canonical phase plan. Update it whenever phase order, scope or
status changes. Shipped details live in [`CHANGELOG.md`](../CHANGELOG.md);
detailed remaining work lives in [`backlog.md`](backlog.md).

**Last updated:** 2026-11-23

## Status

| Phase | Theme | Status |
|---|---|---|
| 0 | Foundation | Done |
| 1 | Prober | Done (schema 1.9.0, prober 1.8.0) |
| 2 | Camera core + preview | Done |
| 3 | Recording | Done on Pad 3 and POCO X6 Pro |
| 4 | GPU + profiles | Done |
| 5 | Advanced capture | Mostly done: shutter angle, gyro, focus shift, concurrent verification. Pro AE and hybrid AF remain |
| **5.5** | **Shoot profiles / Movie-Vlog** | **Foundation complete on Pad + POCO; remaining per-lens 10-bit/4:3, shoot-set editor, matrices** |
| 6 | AI (optional) | Contract only |
| 7 | Device database | Done |
| 8 | Quality | Not started |
| 9 | Release | Not started |
| 10 | Maintenance | Not started |

## Phase table

| Phase | Outcome |
|---|---|
| 0 | Name, licence, repository, module layout, test phones |
| 1 | Capability prober running on real devices; verified feature dictionary |
| 2 | Camera2 session, Pigeon bridge, texture preview, manual ISO/shutter/focus/WB, P/S/M |
| 3 | 8-bit and 10-bit HLG HEVC with audio, MediaStore, recording modes, thermal guard |
| 4 | SlateLog, LUTs, picture profiles, shader histogram/zebra/false colour/waveform/peaking/guides |
| 5 | RAW burst, shutter angle, gyro metadata/alignment, focus shift, Pro AE, hybrid contrast AF |
| 5.5 | Per-camera capability modes, Movie/Vlog common mode, switch while recording, per-camera colour matching |
| 6 | LiteRT subject tracking and focus assist; optional local assistant |
| 7 | Opt-in reports, device table, quirks, calibration data |
| 8 | Real-device stress tests, profiling, multi-version checks, startup stability |
| 9 | Signed APKs, F-Droid, user docs, beta feedback |
| 10 | New Android APIs, device database growth |

## Dependencies

```
Phase 1 probe ──► Phase 3 verification (10-bit/HLG, movie profile evidence)
Phase 4 colour ─┐
Phase 5 capture ─┼─► Phase 5.5 shoot profiles
Phase 7 DB ─────┘
Phase 3 + 5.5 ──► Phase 6 AI (advisory frames/results)
Phase 5.5 ──────► Phase 8 quality (cross-device stress)
```

## Phase 3 exit criteria

- 8-bit and 10-bit HLG HEVC with AAC, upright pixels, MediaStore, thermal guard.
- Recording modes are probe-gated per camera.
- 60-second run with no dropped frames and thermal status 0.
- **Pad 3:** met.
- **POCO:** met — 4K/4:3/1080p 8-bit, 60 s at 1859 frames, 10-bit withheld by quirk
  and the SPS Main/8-bit verdict.

## Phase 5.5 exit criteria

- Modes are read per camera; no global mode list.
- Invalid size/fps/bit-depth/AF configurations cannot reach Camera2.
- Movie mode exposes only the common mode across the shoot set.
- A lens switch while recording never tears down the recorder, and is rejected
  when the target cannot match the active mode.
- One continuous clip across a lens switch on a real multi-lens device.
- Per-camera colour path applied on switch; calibration data tracked separately.
- Pad back↔front and POCO main→ultra-wide→front verified; macro correctly
  rejected mid-recording.
- Remaining: per-lens 10-bit/4:3 verification, shoot-set editor, measured colour
  matrices, seamless-switch polish.

## Next phase

Finish the remaining Phase 5.5 scope (per-lens 10-bit/4:3 verification,
shoot-set editor, measured colour matrices, seamless switch), then Phase 5
remaining items (Pro AE, hybrid AF) or Phase 6 AI. Phase 8/9 after cross-device
Phase 3 + 5.5 sign-off.

## Change policy

- Phase order/scope/status changes: edit **this file** and note the change in
  `CHANGELOG.md` under “Changed”.
- Shipped work: add to `CHANGELOG.md`; do not duplicate it in `backlog.md`.
- Work not yet shipped: add to `backlog.md` under the owning phase.
