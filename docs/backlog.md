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
| HAL verification for all seven capture modes | 2 | `CaptureResult` compared; no mismatches on POCO |
| Audio capability probe (unprocessed, devices, effects) | 1 | schema 1.6.0 |
| Colour input-profile model (matrix + trims, versioned) | 4 | `InputProfile`, unit-tested; calibration pending |
| Audio source selection (UNPROCESSED → CAMCORDER → MIC) | 3 | POCO reports no UNPROCESSED, so CAMCORDER; AAC stereo verified |

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

## Phase 5 — advanced

| Item | Notes |
|---|---|
| RAW burst → DNG sequence | Camera2 has no RAW video; expect low fps and large files |
| Shutter angle | Derived from exposure time and frame rate; UI over the existing shutter control |
| Gyro metadata | Requires `SENSOR_INFO_TIMESTAMP_SOURCE == realtime` and aligned sensor clocks |
| Dual camera / concurrent sessions | Gated by `getConcurrentCameraIds` and `isConcurrentSessionConfigurationSupported` |

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
