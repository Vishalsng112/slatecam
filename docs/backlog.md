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
| Profile editor UI | Live colour-science trim ships; saving a named custom profile still needs persistence |
| 10-bit 4K 4:3 verification | `dci43_*` / `uhd43_*` / `qhd43_*` presets exist but current reports pre-date them; run the device report to enable 10-bit at 4096×3072 / 3840×2880 / 2880×2160 |
| Creative vignette | Shader effect, distinct from lens-shading correction (correction toggle is current) |
| Colour calibration data | Model, shader stage and loader exist; measured ColorChecker matrices are data to add |
| Control-panel touch pass | Dropdowns did not respond to synthetic taps |
| Peaking temporal decay | Needs a ping-pong mask buffer |

## Phase 5 — remaining

| Item | Notes |
|---|---|
| Phase-detect/hybrid AF | Software contrast AF ships; Camera2 exposes no PDAF controls for a hybrid pass |
| Scene-referred metering | Metering shares the analysis frame with the histogram/waveform, so a strong LUT or log profile can bias the target; add a pre-look metering pass |
| 10-bit / FP16 GL intermediate | Required before the full scene-linear colour shader; the encoder stream is Main10 but the current GL intermediate is RGBA8888 |
| Scene-linear colour shader | Pending the 10-bit intermediate and delivery/working-space decision (ADR 0016) |
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
| Manual focus UI | Focus card and dock chip removed while continuous AF is the default; restore with tap-to-focus, AF/AE lock and focus speed |
| Measured colour matrices | `ColorCalibration` solves a 3×3 matrix + trim; capture 24 patches per lens and add the result to the device database |
| Dual-session seamless switch | The frame pump hides the session gap; a true no-freeze switch needs concurrent sessions and a probe verdict |

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

## Rejected, with reasons

| Item | Reason |
|---|---|
| Antialiasing toggle | A full-screen quad sampling a camera texture gains nothing; real AA is in the ISP |
| Live audio noise suppression | Destroys ambience; process offline |
| RAW video via Camera2 | No such stream; still RAW only |
| HLG offered on unverified devices | The prober gates it; one false HDR claim already cost trust |
| Dual preview mode | Removed by decision; concurrency is verified by the probe, but dual preview is out of scope |
