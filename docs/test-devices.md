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
