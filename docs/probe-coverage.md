# Probe coverage vs app requirements

The prober answers **"what can this device do?"**. Building and running the
camera also needs **"how do I operate it?"** — and that second set is only
partly covered. This document is the gap list that drives Phases 2, 3 and 5.

Status legend: ✅ covered · ⬜ missing · 🕒 only knowable at session time.

## Captured today

| Area | Fields |
|---|---|
| Identity | `device_key`, manufacturer, brand, model, device, product, board, hardware, fingerprint, build id, Android release, SDK, security patch, SoC, ABIs, emulator flag, low-RAM flag |
| System | thermal status, total RAM, free storage, native page size |
| Display | active refresh, max refresh, wide gamut, HDR types, peak luminance |
| Camera identity | facing, hardware level, capabilities, logical multi-camera, physical IDs, concurrent IDs |
| Sensor | pixel/active array, physical size, orientation, colour filter, timestamp source, ISO range, exposure range, black/white level |
| Optics | focal lengths, apertures, min focus distance, hyperfocal, max digital zoom |
| Controls | available AE / AF / AWB / OIS / video-stabilisation modes |
| Streams | output sizes for `PRIVATE` and `YUV_420_888` with min frame durations; high-speed sizes with max fps |
| Presets | per-preset verdicts with reason codes, basis and verification status |
| Encoders | mime, codec name, hardware/software/vendor, surface input, profiles and levels, Main10 claim, max instances |
| Encoders (verified) | real Main10 + HLG encode, size, duration, output profile/transfer |
| Audio (output path) | 48/44.1 kHz, stereo, 24-bit PCM acceptance, low-latency hint |
| Tier | `cinema` / `cinema_claimed` / `standard` / `basic` / `unsupported` with reasons |

## Missing for Phase 2 — camera session and controls

| Requirement | Camera2 source | Why it matters |
|---|---|---|
| ⬜ Exact frame-rate ranges | `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` | Min frame duration says "30", not whether 24 or 25 is selectable with AE |
| ⬜ Exposure compensation | `CONTROL_AE_COMPENSATION_RANGE`, `_STEP` | EV wheel bounds and granularity |
| ⬜ Lock availability | `CONTROL_AE_LOCK_AVAILABLE`, `CONTROL_AWB_LOCK_AVAILABLE` | Whether a WB/exposure lock button is honest |
| ⬜ Metering/focus regions | `CONTROL_MAX_REGIONS_AE/AF/AWB` | Tap-to-focus needs a region count, not an assumption |
| ⬜ Zoom ratio | `CONTROL_ZOOM_RATIO_RANGE` | Digital zoom alone ignores logical multi-camera switching |
| ⬜ Focus calibration | `LENS_INFO_FOCUS_DISTANCE_CALIBRATION` | Whether dioptre values map to real distances |
| ⬜ Rotate and crop | `SCALER_AVAILABLE_ROTATE_AND_CROP` | Stabilisation crop behaviour |
| ⬜ Buffer stall durations | `StreamConfigurationMap.getOutputStallDuration` | Sizing `ImageReader` and encoder queues |
| ⬜ Recommended 10-bit profile | `REQUEST_RECOMMENDED_TEN_BIT_DYNAMIC_RANGE_PROFILE` | We assume HLG10; the device may prefer HDR10 |
| 🕒 Available dynamic range profiles | session/characteristics | Whether HLG10 actually configures at a given size |
| 🕒 Concurrent session combos | `isConcurrentSessionConfigurationSupported` | Dual-camera feasibility per stream set |
| ⬜ Physical camera stream maps | per-physical `SCALER_STREAM_CONFIGURATION_MAP` | Lens switching changes available sizes |

## Missing for Phase 3 — recording

| Requirement | Source | Why it matters |
|---|---|---|
| ⬜ Encoder size/fps/bitrate limits | `VideoCapabilities` | Knowing a codec exists is not knowing it does 4K60 at 100 Mbps |
| ⬜ HDR encode feature | `FEATURE_HdrEditing` / codec features | Main10 support is necessary, not sufficient |
| ⬜ P010 buffer input | `CodecCapabilities.colorFormats` | Surface input is covered; buffer input is not |
| ⬜ Input audio devices | `AudioManager.getDevices(INPUTS)` | USB mics and headsets are the point of a cinema app |
| ⬜ Per-device audio rates/channels | `AudioDeviceInfo` | A mic that only does 44.1 kHz mono changes the UI |
| ⬜ Storage throughput | `StorageManager`, write benchmark | Bitrate planning and drop detection |
| ⬜ Sustained performance mode | `Window` | Requires the app, not the prober |
| 🕒 Thermal throttling curve | timed encode run | A snapshot says nothing about a 20-minute take |

## Missing for Phase 5 — advanced

| Requirement | Source |
|---|---|
| ⬜ RAW output sizes | `getOutputSizes(ImageFormat.RAW_SENSOR)` |
| ⬜ Max frame duration | `SENSOR_INFO_MAX_FRAME_DURATION` |
| ⬜ Gyro availability and rate | `SensorManager`, `HIGH_SAMPLING_RATE_SENSORS` |
| ⬜ Lens pose and intrinsics | `LENS_POSE_*`, `LENS_INTRINSIC_CALIBRATION` |

## Verdict

- **Capability gating is complete.** The probe can decide every preset, the
  tier, and every "is this feature available" question.
- **Operational data is partial.** It cannot yet drive the full control surface
  (fps selection, EV range, focus regions, zoom ratios) or plan a recording
  (encoder limits, audio devices, storage throughput).
- **Some facts are only knowable by opening a session.** No amount of
  characteristic reading proves that HLG10 configures, that a concurrent combo
  works, or that an encoder sustains a bitrate.

Phases 2 and 3 each add their section above to the probe, and the
`verification` block is where session-time results belong.

## Why unknown device data cannot break the schema

Devices cannot inject fields into the JSON: the prober emits only modelled
fields. Unknown Android constants are already mapped to fallbacks
(`mode_$n`, `profile_$n`, `type_$n`), so a new vendor value becomes a readable
unknown string rather than a crash.

The real risks are version skew, and each has a rule:

1. **Newer report, older reader** — the reader must ignore unknown fields.
   `ProbeRunner.json` sets `ignoreUnknownKeys = true`; a test enforces it.
2. **Older report, newer reader** — every added field must be optional with a
   default. Never add a required field inside a major version.
3. **Unknown value in a string enum** — consumers must treat it as `unknown`
   and never use it to gate a feature. Never remove or repurpose a value.
4. **Unknown device** — it simply produces capabilities we did not expect,
   which the preset evaluator already turns into reason codes.

A free-form extension bag is deliberately **not** part of the schema. Raw
vendor data belongs in `full` reports, and device quirks belong in the device
table, where they are reviewable. If a genuinely vendor-specific typed field is
ever needed, it arrives as an optional namespaced object in a minor version,
and nothing gates on it.
