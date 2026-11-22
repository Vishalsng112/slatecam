# Device compatibility

SlateCam gates features on verified hardware capability, never on brand, model,
or SoC name. This document defines the tiers and how a device moves between
them. It is the contract that the prober (Phase 1) and the device database
(Phase 7) both implement.

## Tiers

| Tier | Requirements | What the user gets |
|---|---|---|
| **Cinema** | Hardware level `FULL` or `LEVEL_3`; `MANUAL_SENSOR`; `MANUAL_POST_PROCESSING`; `DYNAMIC_RANGE_TEN_BIT`; hardware HEVC Main10; verified HLG clip | 10-bit HLG recording, full manual control, GPU profiles and monitoring tools |
| **Standard** | Hardware level `LIMITED` or better; `MANUAL_SENSOR`; hardware HEVC (8-bit) | 8-bit manual recording, profiles and monitoring in 8-bit |
| **Basic** | Camera2 API works, auto or limited manual control | 8-bit recording, auto exposure, no cinema features |
| **Unsupported** | No usable Camera2 surface path | Not shipped a feature set |

A device is placed in a tier from **claimed** capabilities first, then demoted
if **verification** fails.

## Claimed vs verified

- **Claimed** — what `CameraCharacteristics` and `MediaCodecList` report. Cheap,
  and often wrong or optimistic on vendor builds.
- **Verified** — what the prober and a short test recording actually produce:
  the codec produced output at the expected profile, the clip carries the
  expected color metadata, and the decoder reads it back.

The UI shows claimed capability and marks verified ones with a check. Users
can request verification from the device report screen.

## How a device becomes supported

1. The prober runs and produces a JSON report (schema lives in `:core:probe`).
2. A maintainer or the device owner records a short verification clip.
3. If the device needs a workaround, a quirk entry is added.
4. The entry ships in the next device table update.

## Quirk entries

Quirks are narrow, evidence-backed overrides. Each entry records:

- Stable device key (manufacturer + model + build fingerprint prefix)
- Affected Android version range
- The failing request or capability
- The workaround
- A link to the report that demonstrated the bug

Example shape (final schema defined in Phase 1):

```json
{
  "device": "manufacturer/model",
  "fingerprint_prefix": "BRAND/PRODUCT/DEVICE:13/",
  "min_sdk": 33,
  "issue": "ten_bit_stream_rejected_by_encoder",
  "action": "force_8bit",
  "reason": "Encoder advertises Main10 but rejects the input surface",
  "report": "https://github.com/Vishalsng112/slatecam/issues/000"
}
```

Rules for accepting a quirk:

- Specific to a device or device family, not a whole brand.
- Justified by a reproducible report.
- Re-evaluated when a new Android version ships for the device.

## What we promise

- No device is advertised as cinema-capable without a verified clip.
- When a feature is unavailable, the UI says why, in plain language.
- A user can export their full capability report at any time.
