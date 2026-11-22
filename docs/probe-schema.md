# Probe report schema

- **Schema id:** `slatecam.probe`
- **Version:** `1.3.0`
- **Status:** draft for Phase 1
- **Owner:** `:core:probe`

This document defines the capability report produced by the prober. It is the
contract between Phase 1 (probing), Phase 2 (gating the UI), Phase 7 (device
database), and any external tool that reads reports.

What the prober covers today, and what it still lacks for operating a camera,
is tracked in [probe-coverage.md](probe-coverage.md).

## 1. Two artifacts, one schema

A report exists in two kinds. They share every structure; `kind` only controls
how much detail is included in the optional sections.

| Kind | Target size | Uploaded | Use |
|---|---|---|---|
| `summary` | < 20 KB | opt-in only | Device database, UI gating, issue reports |
| `full` | up to ~500 KB | never automatically | Local debugging, maintainer triage |

The summary must be sufficient to answer every gating question the app asks.
If a feature cannot be gated from `summary` data, the schema is missing a
field — do not work around it by shipping `full` dumps.

## 2. Design rules

1. **Verdicts, not dumps.** The report contains evaluated answers for the
   presets the app actually uses, plus the distilled facts used to compute
   them. Raw `StreamConfigurationMap` tables belong in `full` only.
2. **Enums are strings.** Never emit raw Android integer constants. They are
   unreadable, version-dependent, and impossible to review in a diff.
3. **Claimed and verified are separate.** Anything the platform claims and
   anything the prober actually exercised live in different fields.
4. **Null means "not applicable"; missing means "not collected".** Serialize
   absent optional fields by omitting them, not by writing `null`.
5. **Additive only within a major version.** See section 9.
6. **No personal data, ever.** See section 8.

## 3. Envelope

```json
{
  "schema": "slatecam.probe",
  "schema_version": "1.0.0",
  "kind": "summary",
  "report": {
    "id": "0f3c9a52-7d1e-4b2a-9c55-2f6a1d8e4b11",
    "created_at": "2026-11-23T09:41:00Z",
    "prober_version": "1.0.0",
    "app_version": "0.1.0",
    "duration_ms": 4820,
    "locale": "en-IN"
  },
  "device": {},
  "system": {},
  "displays": [],
  "cameras": [],
  "encoders": [],
  "audio": {},
  "tier": {},
  "verification": {},
  "quirks_applied": [],
  "warnings": []
}
```

| Field | Type | Notes |
|---|---|---|
| `schema_version` | semver string | Written by the prober; stored with the report |
| `kind` | `summary` \| `full` | Controls detail in `cameras[].streams` and `encoders[].codecs` |
| `report.id` | UUIDv4 | Unique per run, not per device |
| `tier` | object | Precomputed by the prober, see section 7 |
| `warnings` | string[] | Human-readable inconsistencies, e.g. "encoder list disagrees with codec query" |

## 4. Device identity

```json
{
  "device_key": "google/husky",
  "manufacturer": "Google",
  "brand": "google",
  "model": "Pixel 8 Pro",
  "device": "husky",
  "product": "husky",
  "board": "husky",
  "hardware": "husky",
  "fingerprint": "google/husky/husky:15/AP4A.250105.002/12345678:user/release-keys",
  "build_id": "AP4A.250105.002",
  "android_release": "15",
  "sdk": 35,
  "security_patch": "2025-01-05",
  "soc_manufacturer": "Google",
  "soc_model": "Tensor G3",
  "abis": ["arm64-v8a"],
  "is_emulator": false,
  "is_low_ram": false,
  "reported_by": "owner"
}
```

`device_key` is `manufacturer/device`, lowercased — the stable grouping key for
the device database. `fingerprint` is required for version-scoped quirks; it is
public build metadata, not personal data.

`reported_by` is one of `owner`, `maintainer`, `anonymous`.

## 5. System, display, GPU

```json
"system": {
  "thermal_status": "none",
  "sustained_performance_supported": true,
  "total_ram_mb": 12288,
  "free_storage_mb": 41200,
  "native_page_size_kb": 16,
  "gles": {
    "version": "3.2",
    "vendor": "Qualcomm",
    "renderer": "Adreno (TM) 740"
  },
  "gpu_capabilities": {
    "color_buffer_float": true,
    "color_buffer_half_float": true,
    "compute_shader": true,
    "rgba1010102_renderable": true,
    "max_texture_size": 16384
  },
  "egl_capabilities": {
    "version": "1.5",
    "bt2020_hlg_colorspace": true,
    "bt2020_pq_colorspace": true,
    "hdr_gl_colorspace": true,
    "ten_bit_config": true
  }
}
```

The `egl_capabilities` block is the distilled form of ~200 extension strings.
Only booleans the pipeline actually branches on are recorded. `full` reports
may include the raw extension arrays.

**Not yet collected in 1.2.0.** The prober does not emit `gles`,
`gpu_capabilities`, or `egl_capabilities`; they arrive with the GPU pipeline in
Phase 2, which needs a live EGL context. Absent means "not collected", never
"unsupported". The verification encode already proves 10-bit EGL surface
creation works, but that is not the same as a full capability report.

`sustained_performance_supported` is populated by the app, not the prober: it
requires a `Window`, which a background probe does not have.

`displays[]` entries carry:

| Field | Meaning |
|---|---|
| `refresh_hz` | Refresh rate of the mode the display is **currently in** |
| `max_refresh_hz` | Highest rate the panel supports; absent if not reported |
| `wide_color_gamut` | Panel covers a wide gamut |
| `hdr_types` | `hdr10`, `hlg`, `hdr10_plus`, `dolby_vision` |
| `max_luminance_nits` | Desired peak luminance for HDR content |

Reporting both refresh values matters: a 144 Hz tablet reports `refresh_hz: 60`
when idle, and monitoring features need the panel maximum, not the current mode.

## 6. Cameras

`cameras[]` is ordered by camera ID as a string (`"0"`, `"1"`, `"2"`), because
logical camera IDs are not guaranteed contiguous.

```json
{
  "id": "0",
  "facing": "back",
  "hardware_level": "full",
  "logical_multi_camera": true,
  "physical_ids": ["2", "3", "4"],
  "concurrent_with": ["1"],
  "capabilities": [
    "manual_sensor",
    "manual_post_processing",
    "raw",
    "dynamic_range_ten_bit",
    "logical_multi_camera"
  ],
  "sensor": {
    "pixel_array": [4096, 3072],
    "active_array": [4080, 3060],
    "physical_size_mm": [9.8, 7.3],
    "orientation_deg": 90,
    "color_filter": "bayer",
    "timestamp_source": "realtime",
    "iso_range": [50, 12800],
    "exposure_time_range_ns": [100000, 500000000],
    "black_level": 64,
    "white_level": 1023
  },
  "lenses": [
    { "focal_length_mm": 6.9, "aperture": 1.68, "min_focus_distance_diopters": 0.0 }
  ],
  "zoom": {
    "max_digital": 30.0,
    "available_focal_lengths_mm": [2.2, 6.9, 14.0],
    "ratio_range": { "min": 1.0, "max": 10.0 }
  },
  "controls": {
    "ae_modes": ["on", "off", "on_auto_flash", "precapture"],
    "af_modes": ["off", "auto", "continuous_video", "continuous_picture"],
    "awb_modes": ["off", "auto", "incandescent", "daylight", "cloudy"],
    "ois_modes": ["on", "off"],
    "ois_available": ["on", "off"],
    "video_stabilization_modes": ["off", "on", "preview"],
    "video_stabilization_available": ["off", "on", "preview"],
    "ae_target_fps_ranges": [{ "min": 24, "max": 30 }, { "min": 30, "max": 60 }],
    "exposure_compensation": { "min": -12, "max": 12, "step": 0.16666667 },
    "ae_lock_available": true,
    "awb_lock_available": true,
    "max_regions": { "ae": 1, "af": 1, "awb": 0 },
    "focus_distance_calibration": "approximate",
    "scaler_rotate_and_crop": ["none", "90", "180", "270", "auto"]
  },
  "dynamic_range": {
    "ten_bit_streams": true,
    "profiles": ["hlg10"],
    "recommended_profile": "hlg10"
  },
  "presets": {},
  "tables": {}
}
```

`timestamp_source` matters: gyro metadata alignment is only reliable when it is
`realtime`. `presets` is defined in section 6.1. `tables` exists only in `full`
reports (section 6.2).

### 6.0 Control-surface fields (1.3.0)

These exist to drive the UI, not to gate features:

| Field | Source characteristic | Used for |
|---|---|---|
| `controls.ae_target_fps_ranges` | `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` | Offering 24/25 only where AE can actually run them |
| `controls.exposure_compensation` | `CONTROL_AE_COMPENSATION_RANGE/_STEP` | EV wheel bounds and granularity |
| `controls.ae_lock_available`, `awb_lock_available` | `CONTROL_AE_LOCK_AVAILABLE`, `CONTROL_AWB_LOCK_AVAILABLE` | Whether a lock control is honest |
| `controls.max_regions` | `CONTROL_MAX_REGIONS_AE/AF/AWB` | Enabling tap-to-focus and spot metering |
| `controls.focus_distance_calibration` | `LENS_INFO_FOCUS_DISTANCE_CALIBRATION` | Whether dioptres map to real distances |
| `controls.scaler_rotate_and_crop` | `SCALER_AVAILABLE_ROTATE_AND_CROP_MODES` | Stabilisation crop behaviour |
| `zoom.ratio_range` | `CONTROL_ZOOM_RATIO_RANGE` | Zoom slider in ratio units, not digital factor |
| `dynamic_range.recommended_profile` | `REQUEST_RECOMMENDED_TEN_BIT_DYNAMIC_RANGE_PROFILE` | HLG10 vs HDR10 preference |

`scaler_rotate_and_crop` modes are the documented values `none`, `90`, `180`,
`270`, `auto`; the platform does not expose them as public constants.

### 6.1 Presets (summary)

Preset ids are `{resolution}{fps}_{depth}`, with resolution one of `uhd`
(3840×2160), `dci4k` (4096×2160), `fhd`, `hd`. Frame rates are the app's
supported rates, which include **24 and 25 fps** — cinema rates are not
optional, and many sensors offer 24/25 but not 30 in a given size.

The canonical preset matrix is generated from the app's recording-mode
catalog, not hardcoded in the prober. For 1.0 the catalog is:

| Resolution | Frame rates | Depth |
|---|---|---|
| `uhd` | 24, 25, 30 | 10-bit, 8-bit |
| `uhd` | 60 | 8-bit, 10-bit |
| `dci4k` | 24 | 10-bit |
| `fhd` | 24, 25, 30 | 8-bit, 10-bit |
| `fhd` | 60, 120 | 8-bit |
| `hd` | 240 | 8-bit |

The 10-bit FHD entries are load-bearing: some devices claim
`dynamic_range_ten_bit` but only expose a P010 stream at 1080p. Without those
presets the prober would grant the cinema tier to a phone that cannot record
10-bit UHD.

Adding a recording mode means adding a preset, so the prober and the UI can
never disagree about what is supported.

```json
"presets": {
  "uhd30_10bit": {
    "supported": true,
    "format": "p010",
    "min_frame_duration_ns": 33333333,
    "profiles": ["hlg10"],
    "basis": "capability_only",
    "verified": "not_run"
  },
  "uhd60_10bit": {
    "supported": false,
    "reason": "fps_out_of_range",
    "detail": "max 30.0 fps at 3840x2160",
    "basis": "stream_map",
    "verified": "not_run"
  },
  "uhd30_8bit": {
    "supported": true,
    "format": "yuv420_flexible",
    "basis": "stream_map",
    "verified": "not_run"
  }
}
```

`basis` states where a verdict came from:

| Value | Meaning |
|---|---|
| `stream_map` | Derived from `ScalerStreamConfigurationMap`; size and frame rate are directly reported |
| `capability_only` | Device advertises the capability, but per-size support can only be proven by configuring a real session |

`verified` is `pass`, `fail`, or `not_run`. A `capability_only` verdict with
`not_run` must never be presented to the user as confirmed support.

`stall_duration_ns` is present only when the platform reports a non-zero stall
for that stream. Zero is omitted: it means the stream does not stall, not that
the value is unknown.

### Why 10-bit is always `capability_only`

Camera2 does not report bit depth per stream size. `DYNAMIC_RANGE_TEN_BIT` is a
device-wide capability, and 10-bit output is selected when a session is
configured with a dynamic range profile, not by asking the stream map for a
different format. A device can therefore advertise ten-bit and still reject a
P010 stream at UHD. Only a real session and encode can prove otherwise, which
is what the verification step exists for.

Frame-rate limits for 10-bit presets are also derived from the 8-bit stream map
and are therefore a heuristic, not a guarantee.

Version 1.1.0 added `basis` and `verified`; both are additive.

Version 1.2.0 added `displays[].max_refresh_hz` and
`verification.checks[].width`/`height`; all optional and additive. Reports
produced by 1.1.0 probers decode unchanged.

Version 1.3.0 added the Phase 2 control surface: `controls.ae_target_fps_ranges`,
`exposure_compensation`, `ae_lock_available`, `awb_lock_available`,
`max_regions`, `focus_distance_calibration`, `scaler_rotate_and_crop`,
`zoom.ratio_range`, `dynamic_range.recommended_profile`, and
`presets[].stall_duration_ns`. All optional and additive.

Reason codes are a closed set so the UI can localize them:

| Code | Meaning |
|---|---|
| `no_stream_config` | Resolution not offered at all |
| `no_10bit_config_at_fps` | 10-bit exists, but not at this frame rate |
| `fps_out_of_range` | Frame rate outside the supported range for this size |
| `unsupported_format` | Stream exists but not in a format the pipeline can use |
| `stabilization_conflict` | Size/fps only available with stabilization in a fixed state |
| `hardware_limit` | Explicit HAL refusal (e.g. too many concurrent streams) |

Presets are evaluated for the primary back camera and any logical camera the
app can select. Do not evaluate all presets for all physical sub-cameras; the
schema would explode for no benefit.

### 6.2 Tables (full only)

`tables` carries the untranslated data for triage: the full
`StreamConfigurationMap` output sizes, per-size frame duration ranges, high
speed sizes, and the raw capability bitmasks. Nothing in the app reads this.

## 7. Tier verdict

```json
"tier": {
  "name": "cinema_claimed",
  "missing": [],
  "unverified": ["hevc_main10_encode"],
  "reasons": ["ten_bit_claimed_not_verified"]
}
```

| Tier | Rule |
|---|---|
| `cinema` | `FULL`/`LEVEL_3` + `manual_sensor` + `manual_post_processing` + `dynamic_range_ten_bit` + hardware HEVC Main10 **verified by a real encode** + HLG10 camera session **verified** |
| `cinema_claimed` | All of the above claimed, but one or both verifications are not run or not passed |
| `standard` | `LIMITED` or better + `manual_sensor` + hardware HEVC (8-bit) |
| `basic` | Camera2 surface path works, no manual control |
| `unsupported` | No usable surface path |

`missing` lists failed requirements; `unverified` lists checks that were not
run. The UI must render these as distinct states. "Your phone lacks 10-bit" and
"we have not tested 10-bit on your phone" are different messages.

## 8. Encoders and audio

```json
"encoders": [
  {
    "mime": "video/hevc",
    "name": "c2.qti.hevc.encoder",
    "hardware": true,
    "software_only": false,
    "vendor": true,
    "surface_input": true,
    "profiles": [
      { "profile": "main10", "level": "5.2" },
      { "profile": "main", "level": "5.2" }
    ],
    "main10_claimed": true,
    "main10_verified": "pass",
    "max_instances": 2
  }
],
"audio": {
  "sample_rates": [48000, 44100],
  "max_input_channels": 2,
  "pcm_24bit": true,
  "low_latency": true
}
```

`main10_verified` is one of `pass`, `fail`, `not_run`. A pass requires a real
short encode; the claim alone never sets it.

**Bitstream verification (prober 1.4.0):** the encode check now parses the
HEVC SPS from the encoded bytes and requires `general_profile_idc == 2`
(Main10). A codec that reports Main10 in `MediaCodec.outputFormat` while
emitting an 8-bit Main stream fails the check. This was observed on MediaTek
MT6897, where the header claimed `profile=2` and the bitstream was Main.

`audio.max_input_channels` is the channel count the prober actually opened, not
a device maximum. `pcm_24bit` means `AudioRecord` accepts 48 kHz/24-bit input,
not that the microphone is lossless.

Verification checks record the size they exercised, so consumers never have to
parse prose:

```json
"verification": {
  "ran": true,
  "checks": [
    {
      "id": "hevc_main10_encode",
      "status": "pass",
      "detail": "c2.mtk.hevc.encoder: 11 frames, 75341 bytes, profile=2, transfer=7",
      "width": 3840,
      "height": 2160,
      "duration_ms": 495
    }
  ]
}
```

A check without `width`/`height` is capability-level only and must not be used
to promote a specific preset.

Known check ids:

| id | Proves |
|---|---|
| `hevc_main10_encode` | The device produced a Main10 stream with HLG colour metadata |
| `camera_hlg10_session` | The camera configured a session whose output carries the HLG10 profile |

A 10-bit preset is only marked `verified: pass` when **both** checks passed at
the preset's size. Either half alone is not a working 10-bit path.

## 9. Versioning and evolution

- `schema_version` is semver.
- **Patch:** documentation or clarification, no shape change.
- **Minor:** new optional fields. Consumers must ignore unknown fields.
- **Major:** removed, renamed, or retyped fields, or a changed enum value.

Rules for readers:

1. Unknown fields are ignored, never fatal. `ProbeRunner.json` sets
   `ignoreUnknownKeys = true`.
2. An unrecognised value in a string enum (`tier.name`, `reason`, `basis`,
   `verified`, `status`, `facing`, `hardware_level`) is treated as `unknown`.
   It must never be used to enable a feature or to fail a parse.
3. A field that is absent is "not collected", which is different from `false`
   and different from "unsupported". Do not substitute one for another.
4. Within a major version, a newly added field must be optional with a default.
   Never add a required field, and never repurpose an existing field name.

Planned, not yet done: the JSON Schema will be generated from the Kotlin
`@Serializable` model in `:core:probe` rather than hand-maintained, once
external consumers exist. Until then the Kotlin model is the single source of
truth and this document describes it.

Reports are stored with the `schema_version` they were produced with. The
device database migrates old reports at read time; readers never assume the
current version.

## 10. Privacy

Never collected, in any kind:

- IMEI, serial number, MAC address, phone number
- Accounts, advertising ID, installed app list
- Location, media, contact data
- Free-form user text

Collected but only in `summary` after explicit opt-in: device/build identifiers
listed in section 4, capability data, and the tier verdict.

`full` reports stay on the device unless the user attaches one to a bug report
themselves. The export screen shows a diff-style preview of exactly what will
be sent.

## 11. Non-goals

- Benchmarking. The prober verifies capability, not speed, except where a
  sustained-write or thermal check is needed to confirm a recording preset.
- Cross-device ranking. That is a Phase 8 concern, derived from reports, not
  part of the schema.
- Vendor diagnostics. Raw HAL logs stay out of the schema.
