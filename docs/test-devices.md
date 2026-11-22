# Test devices

Real hardware is the source of truth. Emulators are only for CI smoke tests.
This list is the Phase 1 test bench; every entry must run the prober before its
capabilities are trusted.

## Reference devices

| Device | SoC | Role | Why it matters |
|---|---|---|---|
| POCO X6 Pro 5G | MediaTek Dimensity 8300-Ultra | **Adversarial device** | Xiaomi/MediaTek HALs are the classic case of advertised capability that does not survive a real encode. If the prober is too trusting, this device proves it. |
| OnePlus Pad 3 | Qualcomm Snapdragon 8 Gen 3 | **Reference device** | Cleaner HAL behaviour and a different vendor stack (Qualcomm `c2.*` encoders) from the MediaTek path. Also catches tablet-specific layout and camera-set assumptions. |

### What each device must prove

**POCO X6 Pro 5G**

- Whether `DYNAMIC_RANGE_TEN_BIT` is real at UHD, and at which frame rates.
- Whether `MediaCodecList` advertises HEVC Main10 that the HAL then rejects.
- Whether 24 and 25 fps are offered at 4K, not just 30.
- Thermal behaviour under a 10-minute 4K encode.
- Whether the camera HAL lies about `MANUAL_SENSOR` or clamps manual exposure.

**OnePlus Pad 3**

- Baseline behaviour for a Qualcomm encoder path (HEVC Main10, HLG metadata).
- Logical multi-camera handling on a device with a different camera topology.
- Large-screen/tablet layout of the preview and controls.
- Gyro timestamp source for metadata alignment.

## Adding a device

1. Install the prober build on the device.
2. Export a `summary` report and attach it to a device report issue.
3. Record a short verification clip if the device claims the cinema tier.
4. Add a row to this table with the role it plays in the test bench.

A device that only ever produces `standard` or `basic` is still useful: it is
the evidence that the app degrades honestly instead of hiding missing features.

## First report — POCO X6 Pro 5G (2026-11-23)

Prober 1.0.0, app 0.1.0, Android 16 (`BP2A.250605.031.A3`), SoC `MT6897`.

| Area | Reported |
|---|---|
| Tier | **`cinema`** — verified, no missing requirements |
| Camera 0 (back) | `level_3`, `manual_sensor`, `manual_post_processing`, `dynamic_range_ten_bit` |
| Camera 1 (front) | `level_3`, same manual/ten-bit capabilities, no UHD presets |
| Camera set | only IDs `0` and `1`, no physical IDs, no logical multi-camera |
| 4K | 30 fps maximum; `uhd60_*` correctly reported unsupported |
| 1080p | 120 fps available through constrained high-speed streams |
| 10-bit | claimed at UHD and FHD 24/25/30; UHD then **verified** at 3840×2160 |
| HEVC encoders | `c2.mtk.hevc.encoder`, `OMX.MTK.VIDEO.ENCODER.HEVC`, secure variants: all claim profile `main` only |
| Verification encode | `c2.mtk.hevc.encoder`, 3840×2160, 11 frames, 75,341 bytes, `profile=2` (Main10), `transfer=7` (HLG), 299 ms |
| HLG10 camera session | camera 0, `OutputConfiguration` with HLG10 at 3840×2160, configured in 748 ms |
| Display | 120 Hz, wide gamut, HDR10 / HLG / HDR10+ / Dolby Vision, 500 nits |
| Audio | 48 kHz, 24-bit PCM supported, not low-latency |
| Native page size | 4 KB |

### Findings

1. **MediaTek under-reports Main10.** No HEVC encoder on this ROM lists
   Main10 in `CodecCapabilities`, yet `c2.mtk.hevc.encoder` produces a Main10
   stream with HLG colour metadata when asked. Before verification the tier was
   `standard`; after it, `cinema`. This is the strongest possible argument for
   the claimed-versus-verified design: trusting the platform would have
   disabled 10-bit recording on a phone that can actually do it.
2. **Only two camera IDs are exposed, with no physical IDs.** The ultrawide and
   macro lenses are not reachable through Camera2 on this ROM. Lens switching
   and dual-camera support must be probed per device and described honestly;
   marketing sensor counts are not a capability report.

### Rules this report produced

- A verified encode may promote a device to `cinema` even when the encoder
  advertises no Main10 profile.
- A verdict is credited only to the codec that produced output. The other HEVC
  encoders stay `not_run` rather than inheriting a pass.
- A preset verdict covers only the size that actually encoded. The 4K pass does
  not promote FHD, and an unsupported mode never inherits a pass.
- The device database stores both the claim and the verification result, because
  they disagree on this device and probably on many more.

## Second report — OnePlus Pad 3 (2026-11-23)

Prober 1.0.0, app 0.1.0, Android 17 (API 37), SoC `SM8750P` (Snapdragon 8 Elite).

| Area | Reported |
|---|---|
| Tier | `cinema` — claimed and verified |
| Cameras | IDs `0` (back) and `1` (front), both `level_3`, both ten-bit, concurrent |
| Sensor (back) | 4160×3120, 4.66×3.49 mm, ISO 100–1600, exposure 20.8 µs – 260 ms |
| Presets | UHD 24/25/30 in 10-bit and 8-bit; FHD 24/25/30; no 60/120/240 modes |
| HEVC encoders | `c2.qti.hevc.encoder` and five siblings, all advertise Main10 |
| Verification | `c2.qti.hevc.encoder`, 4K Main10 HLG, 11 frames, 24,134 bytes, 348 ms |
| Display | 60 Hz active mode, wide gamut, HDR10 / HLG / HDR10+ / Dolby Vision, 500 nits |
| Audio | 48 kHz, 24-bit PCM, low latency |
| Native page size | 4 KB |

### Vendor comparison

| | POCO X6 Pro 5G | OnePlus Pad 3 |
|---|---|---|
| SoC | MediaTek MT6897 | Qualcomm SM8750P |
| Android | 16 (API 36) | **17 (API 37)** |
| Main10 advertised | no | yes |
| Main10 verified | **yes** | yes |
| Tier | `cinema` | `cinema` |
| Camera session | HLG10 at 3840×2160, 748 ms | not run |
| Encode session | 4K Main10 HLG, 299 ms | 4K Main10 HLG, 348 ms |

Both devices are cinema-capable and disagree completely about whether they can
be trusted to say so. That is why the database stores the claim and the
verification as separate fields rather than collapsing them into one flag.

### Probe refinements this report suggests

- `display.refresh_hz` reported only the active mode (60 on this tablet, and
  120 on the POCO when it happened to be in its high-rate mode). Schema 1.2.0
  adds `max_refresh_hz`; the POCO now correctly reports 60 active / 120 maximum.
- The tablet offers no high-speed streams at all, so 120/240 presets correctly
  report `no_stream_config` rather than a frame-rate limit. The distinction
  matters for the UI copy.
- The Pad confirms the build runs on the target API 37, which the POCO
  (API 36) could not.

### Phase 2 control data (schema 1.3.0)

The POCO now reports the full control surface:

| Field | Value |
|---|---|
| AE target fps ranges | `10–10`, `15–15`, `15–20`, `20–20`, `24–24`, `10–30`, `14–30`, `30–30` |
| Exposure compensation | `-24 … +24`, step `1/6` EV (about ±4 EV) |
| AE / AWB lock | available |
| Max regions | AE 1, AF 1, AWB 1 |
| Focus distance calibration | `uncalibrated` |
| Zoom ratio | `1.0 … 10.0` |
| Recommended 10-bit profile | `hlg10` |
| Rotate and crop | `none` only |

**25p cannot be frame-locked on this device.** There is an exact `24–24` range
and an exact `30–30` range, but no `25–25`. Recording at 25 fps is still
possible inside the `10–30` range, but the camera will not hold a fixed 25 fps.
This is exactly the distinction `ae_target_fps_ranges` exists to expose: the
stream map says 25 fps is a supported stream, while the AE ranges say it cannot
be locked.
