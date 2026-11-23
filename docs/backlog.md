# Backlog and phase ledger

Everything discussed goes here. An item is either **done**, **planned** for a
named phase, **blocked** on hardware, or **rejected** with a reason. Nothing is
left floating in conversation.

## Decisions that shape the backlog

1. **Sensor detection is inventory, not assumption.** The platform decides what
   exists: logical camera IDs, their physical sub-cameras, concurrent groups,
   external cameras. SlateCam records whatever `CameraManager` exposes and never
   assumes IDs, lens counts, or that a marketing sensor is reachable. Physical
   camera roles (`ultrawide`/`wide`/`tele`) are derived relative to each other
   from field of view, so they mean something on any device.
2. **Colour science is a shared pipeline plus per-device data.** A common
   transform chain (white balance trim → 3×3 matrix → tone curve → 3D LUT) runs
   for every camera; device-specific normalisation is data in the device
   database, not code branches. This is how OEM looks stay consistent across
   sensors without hard-coding devices.
3. **Recordings today are Rec.709 SDR, 8-bit, vendor-processed.** Camera2 does
   not expose RAW video. HLG is a *transfer function*, not a log curve, and is
   blocked on this hardware. SlateLog is a *look* applied by us, not a
   scene-referred log.
4. **No UI for features outside the current or previous phase.** Future items are
   recorded here first; they get code and controls when their phase starts.

## Done

| Item | Phase | Evidence |
|---|---|---|
| Identity, licence, module graph, probe schema | 0 | ADRs 0001–0003 |
| Capability prober + bitstream verification | 1 | 1.5.0 schema, SPS parser |
| Preview, manual ISO/shutter/focus/WB | 2 | POCO screenshots |
| Capture controls: edge, NR, antibanding, hot pixel, shading, aberration, distortion | 2 | defaults applied, no HAL-ignored warnings |
| 8-bit HEVC + AAC recording | 3 | ffprobe verified |
| MediaStore output (`Movies/SlateCam`) | 3 | gallery path + row |
| Picture profiles, SlateLog, GPU LUT, zebra, false colour, guides, histogram, waveform | 4 | POCO screenshots |
| Physical camera inventory + relative lens roles | 1/2 | schema 1.5.0, unit tests |
| Focus peaking (sensitivity + strength) | 4 | shader edge detection, preview-only, verified on POCO |
| HAL verification for all seven capture modes | 2 | `CaptureResult` compared; no mismatches on POCO |
| Audio capability probe (unprocessed, devices, effects) | 1 | schema 1.6.0 |
| Colour input-profile model (matrix + trims, versioned) | 4 | `InputProfile`, unit-tested; calibration pending |
| Audio source selection (UNPROCESSED → CAMCORDER → MIC) | 3 | POCO reports no UNPROCESSED, so CAMCORDER; AAC stereo verified |
| Shutter angle (math + control) | 5 | 180°@30 = 1/60 s, unit-tested; live angle shown in UI |
| Gyro sample capture + sidecar | 5 | 206 samples in 5 s, realtime timestamps |
| Gyro frame alignment | 5 | 145 frame rotations in 4.8 s; sidecar v2; shared `elapsedRealtimeNanos` clock |
| Concurrent camera session | 5 | cameras 0+1 configured together; `concurrent_camera_session: pass` |

## Phase 2 completion — remaining

| Item | Why | Notes |
|---|---|---|
| Camera tone-map pinning (`TONEMAP_MODE_GAMMA_VALUE`) | The HAL's tone curve runs before our pipeline | No true "off"; pin a fixed gamma and verify |

## Phase 3 — remaining / blocked

| Item | State | Notes |
|---|---|---|
| 10-bit HLG recording | **blocked** | Implemented but gated; POCO emits 8-bit Main despite claiming Main10. Needs a device whose SPS says `profile_idc=2` |
| Audio noise reduction | **deferred to post** | Live NS/AEC artifacts and DSP cost; record clean, process offline. A monitoring high-pass is acceptable |
| Colour-space menu | planned, gated | Show SDR (enabled) and HLG (disabled with reason). Never offer what the probe did not verify |
| Declared transfer/gamma selection | planned, gated | Must change signal *and* metadata together; needs 10-bit on this hardware |
| Thermal stress test (20 min) | planned | Informal 60 s run showed status 0 |

## Phase 4 — remaining

| Item | Notes |
|---|---|
| SAF LUT picker | Folder scan exists (`files/luts`); picker needs a plugin or native SAF |
| Profile editor UI | Profiles are data already; editing needs persistence |
| Creative vignette | Shader effect, distinct from lens-shading correction |
| Colour calibration data for the input profile | Model and shader stage are done; a ColorChecker workflow pairs with Phase 7 |
| Control-panel touch pass | Dropdowns did not respond to synthetic taps |
| Peaking temporal decay | Persistence after focus changes needs a ping-pong mask buffer |

## Phase 5 — advanced

| Item | Notes |
|---|---|
| RAW burst → DNG sequence | Camera2 has no RAW video; expect low fps and large files |
| Gyro container embedding | Sidecar v2 is frame-aligned; embedding a metadata track in MP4 remains |
| Lens switching / per-physical streams | Only worth pursuing on a device that exposes physical IDs |

Framing guides moved to Phase 4 (done).

## Phase 6 — AI

Subject tracking and focus assist on LiteRT; optional local assistant. Not
started. Must be opt-in and frame-capped to protect thermals.

## Phase 7 — device database

Opt-in reports, quirk table, and **per-device colour calibration** (ColorChecker
matrix, sensor/lens normalisation). Calibration is data, not code.

## Phase 8–10

Stress tests, profiling, signed releases, F-Droid, API tracking. Unchanged.

## Rejected, with reasons

| Item | Reason |
|---|---|
| Antialiasing on/off toggle | A full-screen quad sampling a camera texture gains nothing from MSAA; real AA is in the ISP and not exposed |
| Baked-in live audio noise suppression | Destroys ambience and spatial cues; incompatible with cinema audio. Post-processing instead |
| RAW video via Camera2 | No such stream exists; only still RAW frames |
| A colour-space menu that offers HLG on unverified devices | We already shipped one false HDR claim; the prober must gate the menu |
| Dual preview mode | Removed by decision. It needs a synchronous camera-release path plus a second controller, preview and bridge surface, for a feature a single-camera cinema app does not need. Concurrency itself remains verified in the probe. |

## Capture engine — next major slice

Four requested features share one engine in `:core:camera`. All are feasible;
none is a one-session change.

### 1. Mode engine (P / S / M)
- **P**: stock HAL AE/AF (current default).
- **S (Tv)**: fix `SENSOR_EXPOSURE_TIME`, run our **auto-ISO** loop from the GPU
  histogram to hold brightness. Metering source already exists (64 bins @ ~5 Hz).
- **M**: manual ISO/shutter (done).
- Av is impossible: phone apertures are fixed and Camera2 has no selection key.

### 2. Own exposure loop + change speed
- Replace HAL AE when a "Pro AE" mode is active.
- Controller: target from a percentile of the histogram, PI controller on
  `SENSOR_SENSITIVITY` (and shutter in P-pro), clamped to reported ranges.
- **Speed control**: rate-limit the EV change (`EV/sec`) or define a convergence
  time; expose Slow / Normal / Fast.
- Risks: oscillation, flicker under 50/60 Hz light (antibanding stays on), and
  vendor gain-step quantisation.

### 3. Hybrid contrast-detect AF
- Metric: Laplacian variance over the AF region, computed in the existing
  analysis FBO and read back at ~10 Hz.
- Coarse acquisition: HAL AF (one-shot), then refine `LENS_FOCUS_DISTANCE` by
  hill-climb (step, settle, measure, reverse at the peak).
- Fallback to HAL continuous AF if the metric is unreliable (low light/low
  texture).
- Risks: lens/HAL rate limits, visible hunting; keep HAL AF as the default.

### 4. Focus shift (rack) speed — most cinematic, least risky
- `setFocusDiopters(target)` currently jumps. Add a **focus transition speed**
  (`diopters/sec`) and ramp toward the target each frame.
- Slow = long, deliberate rack; fast = snap focus. Preview-only effect on the
  lens, so it applies to recording too.
- Implement first: small, self-contained, immediately visible, and it becomes the
  actuator layer the CDAF hill-climb needs anyway.
