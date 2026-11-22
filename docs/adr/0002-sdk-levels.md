# ADR 0002 — SDK levels and the lite-mode decision

- **Status:** Accepted
- **Date:** 2026-11-23
- **Phase:** 0
- **Supersedes:** the "optional lite mode: API 29-32" note in the original plan

## Context

Android requires three separate build inputs, and they are routinely confused:

- `minSdkVersion` — the install floor. Devices below it cannot install the app.
- `compileSdkVersion` — which platform APIs may be referenced at build time. No
  runtime effect.
- `targetSdkVersion` — the behavior contract the OS applies. It is **not** an
  upper bound: an app targeting 33 runs on Android 17 under compatibility
  behavior.

There is no way to omit `targetSdkVersion` or pin it to "latest". It is a fixed
integer that must be raised deliberately over time, because Android raises the
floor at which old targets may install (Android 14 already blocks targets below
API 23) and because Play, if ever used, mandates a recent target.

Camera capability does not depend on `targetSdkVersion`. 10-bit dynamic range,
manual sensor controls, and HEVC Main10 are exposed through
`CameraCharacteristics` and `MediaCodecList` regardless of target. The hard
API requirement for the cinema tier is API 29 (`DYNAMIC_RANGE_TEN_BIT`).

The original plan included an optional "lite mode" for API 29-32 offering 8-bit
manual recording.

## Decision

| Property | Value |
|---|---|
| `minSdk` | 33 (Android 13) |
| `compileSdk` | 37 (Android 17, latest stable) |
| `targetSdk` | 37 (Android 17, latest stable) |
| Lite mode (API 29-32) | **Dropped** for 1.0 |
| Build variants | `debug` / `release` only; no per-SDK flavors |

Policy:

1. `minSdk` is only raised in a major release, and only after one release that
   announces the deprecation. Lowering it later is always permitted.
2. `compileSdk` may track a stable preview in a throwaway branch, but never on
   `main` while it is unreleased.
3. `targetSdk` moves at most once per year, never in the same pull request as a
   feature, and only after a smoke test on at least one API 33 device and one
   current-API device.
4. Runtime API differences are handled with `Build.VERSION.SDK_INT` guards
   inside `:core:*`. Do not fork behavior by build flavor.

## Rationale

`minSdk 33` is not driven by a hard API requirement — it is a maintenance
budget decision:

- Cinema-tier devices (the ones that pass the 10-bit + HEVC Main10 gate) are
  effectively all Android 13 or newer. A 29-32 path serves 8-bit manual
  recording, which Open Camera already does well.
- Every supported Android version multiplies the vendor camera HAL bug surface,
  and every HAL quirk becomes an entry maintained forever in the device table.
- One code path means the `SDK_INT` branches stay shallow and testable.

`targetSdk` tracks latest stable because the alternative — lagging — produces
compatibility shims nobody else runs, install refusal over time, and an OS
warning banner, while providing no protection against new-version bugs.

## Consequences

- SlateCam cannot be installed on Android 12L or older. This must be stated in
  the README and the F-Droid listing.
- The phrase "lite mode" is retired; there is one feature set, gated by the
  probe, not by OS version.
- A yearly `targetSdk` bump is a scheduled maintenance task with a required
  device test pass. It is not a drive-by change.
- Because `targetSdk` affects foreground-service types and storage behavior,
  every bump must include a recording smoke test, not just an app-launch test.
- Devices on API 33 that lack the cinema hardware still install the app and get
  the `standard` or `basic` tier. The floor is about OS version, not capability.

## Alternatives considered

- **minSdk 29 with a lite mode** — rejected: doubles the quirk and test matrix
  to serve a shrinking population with a feature better handled by mature
  existing apps.
- **minSdk 32** — rejected: covers few additional devices while still adding
  an older-Android branch to test.
- **`targetSdk` pinned one version behind** — viable if no Android 17 device is
  available before release; revisit at Phase 9 rather than silently drifting.
