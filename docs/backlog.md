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
| Profile editor UI | Profiles are data already; editing needs persistence |
| Creative vignette | Shader effect, distinct from lens-shading correction |
| Colour calibration data | Model, shader stage and loader exist; measured ColorChecker matrices are data to add |
| Control-panel touch pass | Dropdowns did not respond to synthetic taps |
| Peaking temporal decay | Needs a ping-pong mask buffer |

## Phase 5 — remaining

| Item | Notes |
|---|---|
| Pro AE + EV/sec control | Replace HAL AE in P with the histogram-driven controller; expose Slow/Normal/Fast |
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
| Shoot-set editor | Current set is all probe-visible, openable, non-macro lenses; let the user include/exclude and save a named profile |
| Per-camera colour matrices | Apply pairwise ColorChecker matrices so lenses match; input-profile plumbing is ready |
| Seamless switch polish | Current switch has a short visual gap; pre-open/dual-session is a later refinement |

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
