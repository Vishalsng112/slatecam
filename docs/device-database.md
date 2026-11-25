# Device database, quirks and colour calibration

**Owner:** `:core:device`
**Bundled schema:** `slatecam.device-db`, version 1 (additive per-lens fields)
**Status:** Phase 7 complete; the calibration solver ships, measured target data is still to add

The device database is the Phase 7 data layer between probe reports and the
camera pipeline. It answers three questions without device-specific code:

1. Does this build need a known workaround?
2. Does this camera have a measured colour normalisation?
3. Has the user opted in to storing a device report?

Everything is data. A new device or calibration is a reviewed JSON change, not a
new branch in `CameraController` or the shader.

## Files and precedence

| Location | Purpose |
|---|---|
| `:core:device` assets / `device-database.json` | Bundled, reviewed defaults shipped with the app |
| `files/device-database.json` | Optional user/maintainer override on the device |
| `files/probe.json` | Latest probe report, kept for adb/run-as debugging |
| `files/device-reports/<device_key>/<timestamp>.json` | Opt-in archived reports |
| app-specific external `probe.json` | Convenience copy for local export |

The user database is **merged over** the bundled database:

- quirks replace by `id`;
- calibrations replace by `device_key + camera_id`;
- all unrelated bundled entries survive.

`reloadDeviceDatabase()` re-reads both files and reapplies calibration and
quirks without reinstalling the app.

## Schema

```json
{
  "schema": "slatecam.device-db",
  "version": 1,
  "generated_at": "2026-11-23",
  "quirks": [
    {
      "id": "poco-duchamp-force-8bit",
      "match": {
        "device_key": "xiaomi/duchamp",
        "camera_id": null,
        "fingerprint_prefix": "POCO/duchamp_in/duchamp:",
        "min_sdk": 33,
        "max_sdk": null
      },
      "issue": "ten_bit_encode_claim_unverified",
      "action": "force_8bit",
      "value": null,
      "reason": "Encoder reports Main10 but writes an 8-bit Main stream.",
      "report": "docs/test-devices.md#10-bit-hlg-attempt--rejected-by-evidence-2026-11-23"
    }
  ],
  "calibrations": [
    {
      "device_key": "xiaomi/duchamp",
      "camera_id": "0",
      "lens_key": "back:default:25",
      "version": 1,
      "matrix": [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0],
      "exposure_trim": 1.0,
      "saturation_trim": 1.0,
      "wb_r_offset": 1.0,
      "wb_b_offset": 1.0,
      "shadow_lift": 0.0,
      "source": "ColorChecker Classic, D65, 2026-11-23",
      "created_at": "2026-11-23T12:00:00Z"
    }
  ]
}
```

### Quirk matching

A quirk matches only when **all** supplied fields match:

- `device_key` = lowercased `manufacturer/device`;
- `fingerprint_prefix` = version-scoped build prefix;
- `min_sdk` / `max_sdk` = Android API range;
- `camera_id` = optional per-camera scope.

A quirk without `camera_id` applies to the whole device. Unknown actions are
kept as data and ignored by consumers, so a newer database cannot crash an older
app.

### Known quirk actions

| Action | Effect |
|---|---|
| `force_8bit` | Withholds 10-bit/HLG requests for the matched camera; the probe may still report the hardware as capable |
| `force_antibanding` | Applies `value` (`50hz`, `60hz`, `auto`, `off`) to AE |
| `disable_noise_reduction` | Requests `NOISE_REDUCTION_MODE_OFF` |
| `note` | Documentation only; no camera change |

Quirk rules from [compatibility.md](compatibility.md) still apply: specific,
evidence-backed, version-scoped, and re-evaluated when the device receives a new
Android release.

### Calibration matching

Calibration prefers the probe-stable `lens_key` (`facing:role:focal`), then an
exact `camera_id`, then the `"*"` device-wide fallback. Entries with a
`lens_key` are keyed by `device_key + lens_key`; entries without one keep the
`device_key + camera_id` key. When duplicates exist, the highest `version` wins.

The matrix is the row-major 3×3 CCM into the working space. `wb_r_offset` and
`wb_b_offset` are per-lens white-balance multipliers applied before the CCM;
`shadow_lift` is a small shadow-floor trim. `exposure_trim` and
`saturation_trim` are multipliers after the matrix. The renderer applies all of
them in linear Rec.2020, before the creative look (ADR 0018). Identity values are
the defaults, so an entry without these fields behaves exactly as before.

The pipeline order is documented in [ADR 0005](adr/0005-colour-pipeline.md) and
[ADR 0016](adr/0016-colour-architecture.md):

```text
camera → (HLG→SDR preview) → per-lens WB + shadow trim + CCM
       → creative profile → LUT → preview overlays
```

The bundled database currently ships **no measured calibration entries**. Until
a ColorChecker matrix is added, `InputProfile` stays identity and the UI reports
`identity · no measured data`. This is deliberate: inventing a matrix would make
the preview change without evidence.

`ColorCalibration.solve` (`:core:profile`) fits 24 observed/reference RGB
patches in **linear Rec.2020** (the domain the shader applies the matrix in).
`ColorChecker` supplies the published ColorChecker Classic references and the
BT.709/sRGB decode plus ΔE2000. The neutral patches drive `wb_r_offset` /
`wb_b_offset` and the exposure trim. Degenerate fits are rejected: the result
reports `accepted = false`, keeps identity and cannot be saved.

The in-app Calibration screen (console → Picture profile → *Calibrate colour…*)
captures one bypass frame, lets the user align a 6×4 perspective grid, samples
the patches and saves the accepted result to `files/device-database.json` under
the probe-stable `lens_key`. It is not a substitute for a physically good chart
and even lighting, and the 8-bit captured frame limits precision. See ADR 0027.

## Adding a device or calibration

1. Run the prober on the device and keep the summary report.
2. Add a quirk only when the report or a test recording proves a workaround is
   needed. Record the failing request, API range, and evidence link.
3. For colour, either use the in-app Calibration screen or supply an external
   ColorChecker measurement, and add one `calibrations` entry per lens.
4. Validate the JSON against `DeviceDatabase.validate()`, then either ship it in
   the bundled asset or push it to `files/device-database.json`.
5. Reload from the Device Database panel and confirm the applied quirk/calibration
   ids in the UI.

## Opt-in reports

- Opt-in is **off by default** and stored in app preferences.
- The user-facing run button is disabled until opt-in is enabled.
- The debug launch extra `--ez run_probe true` bypasses the preference for
  maintainers, but writes only on-device files.
- No report is uploaded automatically. There is no network client in this phase.
- Summary reports contain public build metadata and capability results only.
  `full` reports remain local, as defined in [probe-schema.md](probe-schema.md).

While a report runs, the bridge closes the preview, runs the probe on a worker
thread, and reopens the preview if it was open before. This avoids the Phase 1
camera-contention bug.

## Versioning

- Unknown JSON fields are ignored (`ignoreUnknownKeys = true`).
- Quirk actions not known to this app are retained and ignored.
- Calibration and quirk ids are stable review keys. Do not repurpose an id.
- Breaking shape changes require a new `schema` major version and a migration.
