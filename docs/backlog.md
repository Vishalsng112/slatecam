# Backlog

Forward-looking work only. Shipped changes are in [`../CHANGELOG.md`](../CHANGELOG.md);
phase order and status are in [`roadmap.md`](roadmap.md). Design constraints are
recorded in [`adr/`](adr/) and summarised in [`codebase-map.md`](codebase-map.md).

## Phase 2 — remaining

| Item | Why | Notes |
|---|---|---|
| Camera tone-map pinning (`TONEMAP_MODE_GAMMA_VALUE`) | The HAL tone curve runs before our pipeline | No true “off”; pin a fixed gamma and verify |

## Phase 3 — remaining

| Item | State | Notes |
|---|---|---|
| True 10-bit GL intermediate | quality follow-up | Encoded stream is Main10, but the encoder surface is RGBA8888 because the driver rejects 10-bit EGL swaps. Add a 10-bit FBO/surface path when the driver supports it |
| 20-minute thermal test | planned | 60 s at 1080p30 10-bit verified thermal 0 |
| Colour-space menu | planned, gated | SDR enabled; HLG only when verified. Never offer what the probe did not verify |
| Declared transfer/gamma selection | planned, gated | Signal and metadata must change together |

## Phase 4 — remaining

| Item | Notes |
|---|---|
| SAF LUT picker | Folder scan exists (`files/luts`); picker needs a plugin or native SAF |
| Creative LUT UI | `setCreativeLut` / `reloadLuts` remain in the bridge but are hidden from the UI; restore selection/reload when the LUT workflow is prioritised |
| 10-bit 4K 4:3 verification | `dci43_*` / `uhd43_*` / `qhd43_*` presets exist but current reports pre-date them; run the device report to enable 10-bit at 4096×3072 / 3840×2880 / 2880×2160 |
| Creative vignette | Shader effect, distinct from lens-shading correction (correction toggle is current) |
| Colour calibration data | Model, shader stage and loader exist; measured ColorChecker matrices are data to add |
| Control-panel touch pass | Dropdowns did not respond to synthetic taps |
| Analysis-overlay payload | Histogram/waveform still cross Pigeon as int lists at 2 Hz; move to a compact binary payload or decode off the UI thread to remove the residual ~50 ms holds while the cards are visible |
| Peaking temporal decay | Needs a ping-pong mask buffer |

## Phase 5 — remaining

| Item | Notes |
|---|---|
| Phase-detect/hybrid AF | Software contrast AF ships; Camera2 exposes no PDAF controls for a hybrid pass |
| Scene-referred metering | Metering shares the analysis frame with the histogram/waveform, so a strong LUT or log profile can bias the target; add a pre-look metering pass |
| True 10-bit encoder input | FP16 intermediate + dither ships; the encoder surface is still RGBA8888. A 10-bit/P010 surface or buffer-input path is still required for true 10-bit source |
| Recordable log / S-Log / S-Gamut | Picture profiles ship look gammas only; a custom log or gamut cannot be tagged honestly in an HLG/Rec.709 container until a recordable-log delivery exists |
| Picture-profile Detail controls | Level only is planned; V/H balance, B/W balance, limit, crispening and highlight detail need a spatial detail pass |
| Continuous auto knee | Current auto knee is a static max-point/sensitivity mapping; frame-driven tracking is not implemented |
| Monitoring-only LUT | Creative LUT is baked into both outputs; a separate preview-only LUT is not implemented |
| Viewfinder latency | Preview goes through Flutter's texture path. Split the pipeline: encoder renders every camera frame while the preview is blitted to a native surface (or FBO) on vsync; also revisit async analysis readback via JNI/PBO. Measured baseline in ADR 0022 |

| Per-lens CCM/WB measurement | Schema and lookup ship; the database still has no measured ColorChecker data |
| 65³ LUT support | The 2D atlas `n*n × n` exceeds common texture limits at 65; needs tiling or GLES3 3D textures |
| Hybrid contrast-detect AF | Laplacian variance from the analysis FBO + HAL coarse acquisition; fall back to HAL AF |
| RAW burst → DNG sequence | Camera2 has no RAW video; expect low fps and large files |
| Gyro container embedding | Sidecar v2 is frame-aligned; embedding an MP4 metadata track remains |
| Per-physical streams | Only worthwhile on devices that expose physical IDs; lens switching now uses camera IDs |

Focus shift speed, shutter angle, gyro alignment and concurrent verification are
done (see changelog).

## Phase 5.5 — shoot profiles / Movie-Vlog

Foundation is shipped (capabilities, modes, common mode, validated switching,
AF handling). Remaining:

| Item | Notes |
|---|---|
| Manual focus UI | The MF slider, tap-to-focus and pinch zoom ship in the Focus card; **focus/AE lock** remains and is not implemented |
| Measured colour matrices | `ColorCalibration` solves a 3×3 matrix + trim; capture 24 patches per lens and add the result to the device database |
| Logical zoom handoff controller | Implemented and gated by the recorded `logicalZoomHandoff` verdict |
| `logicalZoomHandoff` verdict | Implemented: stream sweep checks gaps, exposure/WB jumps and active physical IDs; runs last in the probe |
| Hidden logical fallback | POCO hidden logical `4` survives front→back once hidden macro is excluded; other hidden round trips still need verdict coverage |
| Hidden lens stability | Opening hidden macro `3` on the POCO invalidates the camera list for the process; hidden macro is excluded from the UI until a reopen/identity verdict exists |
| Dual-session switch | ADR/design only after the logical-zoom path is verified; no concurrent-session code yet |
| Dual-session seamless switch | The frame pump hides the session gap; a true no-freeze switch needs concurrent sessions and a probe verdict |

## Audio

Input selection, pre-roll metering, gain and 256 kbps AAC ship (ADR 0028).
Remaining:

| Item | Notes |
|---|---|
| Headphone monitoring | `AudioTrack` playback has latency/echo risks; needs a routing and feedback policy |
| Higher-resolution capture | PCM16 ships; 24-bit/PCM and 96 kHz depend on per-input capabilities |
| Limiter / AGC toggle | Processing is deliberately off; a limiter would need an explicit, visible mode |
| Per-input sensitivity calibration | Gain is a single trim; mics differ in sensitivity |
| Loudness history | Meters are instantaneous; LUFS/true-peak history is future |
| Audio metadata in MP4 | Input, gain and source are in state, not embedded in the clip |
| Bluetooth caveat | SCO input is low quality; surface a warning when selected |

## Phase 6 — AI

Contracts exist in `:core:ai` (`AiEngine`, `AiConfig`, `AiFrame`, `AiFrameGate`,
`NoOpAiEngine`). Still to implement: LiteRT engine, model assets, thermal budget,
GL frame handoff, opt-in UI, optional local assistant.

## Phase 7 — device database

The database, quirks, overrides, opt-in reports and loader are implemented.
Remaining: measured ColorChecker matrices, and new quirk entries from real device
reports (including the Pad's hidden-lens policy). See
[`device-database.md`](device-database.md).

## Phase 8–10

Stress tests, profiling, startup stability, signed releases, F-Droid, API
tracking. Unchanged.

## Media workflow

First clip library and player shipped in build 2126 (ADR 0026). Remaining:

| Item | Notes |
|---|---|
| Batch select / delete | Single-clip delete is current |
| Rename / album | MediaStore DISPLAY_NAME update and a SlateCam album |
| Proxy / transcode | For sharing and fast scrubbing |
| SD / USB-C SSD destination | MediaStore external volumes and SAF |
| Worker-thread media query | Move MediaStore listing + thumbnail generation off the platform thread |
| Playback scopes / LUT preview | Reuse the analysis FBO path |
| Frame-accurate scrubbing | Native Media3 view or frame-step handling |
| Embedded gyro/metadata | The sidecar is removed on delete; embedding remains future |

## Rejected, with reasons

| Item | Reason |
|---|---|
| Antialiasing toggle | A full-screen quad sampling a camera texture gains nothing; real AA is in the ISP |
| Live audio noise suppression | Destroys ambience; process offline |
| RAW video via Camera2 | No such stream; still RAW only |
| HLG offered on unverified devices | The prober gates it; one false HDR claim already cost trust |
| Dual preview mode | Removed by decision; concurrency is verified by the probe, but dual preview is out of scope |
