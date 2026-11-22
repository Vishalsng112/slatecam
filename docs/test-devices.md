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
| Tier | `standard` — missing `hevc_main10_encoder` |
| Camera 0 (back) | `level_3`, `manual_sensor`, `manual_post_processing`, `dynamic_range_ten_bit` |
| Camera 1 (front) | `level_3`, same manual/ten-bit capabilities, no UHD presets |
| Camera set | only IDs `0` and `1`, no physical IDs, no logical multi-camera |
| 4K | 30 fps maximum; `uhd60_*` correctly reported unsupported |
| 1080p | 120 fps available through constrained high-speed streams |
| 10-bit | claimed at UHD and FHD 24/25/30, `basis: capability_only` |
| HEVC encoders | `c2.mtk.hevc.encoder`, `OMX.MTK.VIDEO.ENCODER.HEVC`, secure variants: profile `main` only |
| Display | 120 Hz, wide gamut, HDR10 / HLG / HDR10+ / Dolby Vision, 500 nits |
| Audio | 48 kHz, 24-bit PCM supported, not low-latency |
| Native page size | 4 KB |

### Two findings that change the plan

1. **The camera claims 10-bit, but no HEVC encoder claims Main10.** Either
   10-bit recording is unavailable to third-party apps on this ROM, or MediaTek
   omits Main10 from `CodecCapabilities` while the encoder still accepts it.
   Only an actual encode can tell the difference — which is exactly why the
   tier rules were changed to let a verified Main10 override a missing claim.
   This device is the first mandatory target for the verification encode.
2. **Only two camera IDs are exposed, with no physical IDs.** The ultrawide and
   macro lenses are not reachable through Camera2 on this ROM. Lens switching
   and dual-camera support must be probed per device and described honestly;
   marketing sensor counts are not a capability report.

The tier verdict of `standard` is correct under the current rules: without a
verification run, an unadvertised Main10 encoder must not be assumed to work.
