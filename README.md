# SlateCam

**Open-source cinema camera for Android.**

SlateCam turns an Android phone into a manual cinema camera: full manual
exposure and focus, 10-bit HEVC recording, picture profiles you actually
control, and on-device monitoring tools. Built in the open, with no telemetry,
no account, and no subscription.

- Application ID: `io.github.vishalsng112.slatecam`
- License: MIT
- Minimum Android: 13 (API 33) — no lite mode, one code path
- Target Android: 17 (API 37)

Architecture decisions are recorded in [`docs/adr/`](docs/adr/).

> **Status: Phase 3 complete on both test devices. Phase 5.5 per-lens
> verification, the shoot-set editor and named shoot profiles are complete (Pad
> 10-bit HLG movie mode across lenses); the in-app ColorChecker flow ships, so
> remaining work is capturing/shipping colour data and seamless switching. The
> audio control surface (input selection, pre-roll meters, gain) is current.**
> Phases 0–4 are complete: preview, manual and P/S/M exposure, 8-bit SDR and
> **10-bit HLG** recording via MediaStore, recording modes (16:9/4:3,
> 24/30 fps), profiles, LUTs, and monitoring tools. Phase 5 adds shutter angle,
> gyro sidecars, and concurrent-camera verification. Phase 6 is an AI contract
> only; no LiteRT engine ships yet. Phase 7 adds the versioned device database,
> quirks, calibration loading and opt-in reports. HLG is enabled by the probe
> and verified from the encoded SPS; the POCO stays 8-bit because its SPS says
> Main/8-bit.

---

## Why another camera app

Free apps already expose manual controls on Android. SlateCam exists for the
things those apps do not do:

1. **Honest device support.** Features are gated by what the camera hardware
   actually reports and actually verifies — never by SoC brand or model name.
   If your phone cannot do 10-bit, SlateCam will tell you exactly why instead
   of silently degrading.
2. **Open pipeline.** Picture profiles, the log curve, and LUT handling are
   documented, versioned, and replaceable. You are not locked into one vendor's
   look.
3. **No telemetry, no account, no cloud requirement.** The optional community
   device database is opt-in, and you can inspect exactly what a report
   contains before it is sent.
4. **Built for the long term.** A device quirk table that grows with real
   reports, not a release that rots when the maintainer disappears.

## Design principles

These are architectural constraints, not aspirations. Changes to them require
an architecture decision record.

1. **Kotlin is the single source of truth.** Camera state, session control,
   and encoding live in Kotlin. Flutter renders state snapshots and sends
   commands. Dart never owns authoritative camera state.
2. **Frames never cross the Flutter bridge.** The preview is a native GPU
   texture; heavy pixels stay native. Only control messages and telemetry cross.
3. **One pixel path.** Camera frames flow through a single GL pipeline for
   preview, recording, and monitoring tools. There is no separate
   "record directly from camera" shortcut.
4. **Probe first, then feature.** Every capability claim is produced by the
   prober and verified by a test recording before it is surfaced in the UI.
5. **Real devices from day one.** Emulators are for CI smoke tests only.

## Requirements for the full cinema tier

A device reaches the cinema tier only when all of the following are verified,
not merely advertised:

| Requirement | Where it comes from |
|---|---|
| Camera hardware level `FULL` or `LEVEL_3` | `CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL` |
| Manual sensor control | `REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR` |
| Manual post-processing control | `REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING` |
| 10-bit dynamic range output | `REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT` |
| Hardware HEVC Main10 encoder | `MediaCodecList` + verified test encode |
| Verified HLG recording | Test clip decoded and color metadata checked |

Devices that fail these fall back to a standard tier (8-bit manual) or are
listed as unsupported. See [docs/compatibility.md](docs/compatibility.md).

## Architecture at a glance

Kotlin owns camera state and pixels; Flutter renders snapshots and sends
commands. Camera frames go through one GL pipeline to preview, recorder and
analysis; no pixels cross the Pigeon bridge.

```
Flutter UI  ──Pigeon commands──►  :bridge  ──►  :core:camera / :core:codec / :core:gpu
     ▲                                  │
     └────── CameraState (Pigeon) ◄─────┘

Camera2 ─► SurfaceTexture ─► GlPreviewRenderer ─┬─► preview texture
                                                 ├─► HEVC encoder surface
                                                 └─► analysis FBO
```

The full module map, runtime flows, contracts and “where to change what” table
are in [`docs/codebase-map.md`](docs/codebase-map.md). Module boundaries are
enforced by `checkModuleGraph`; see
[`docs/adr/0003-modules.md`](docs/adr/0003-modules.md).

Key modules:

| Module | Role |
|---|---|
| `lib/src/ui` | capture screen, control deck, zoom plugin, overlays |
| `:bridge` | Pigeon API and camera state mapping |
| `:core:camera` | Camera2 controller, per-camera capabilities, recording modes |
| `:core:gpu` | EGL/GLES renderer and colour/monitoring shader |
| `:core:codec` | HEVC/AAC recorder, gyro recorder |
| `:core:probe` | capability prober, verifiers, lens roles |
| `:core:device` | device database, quirks, calibration, capability gates |
| `:core:profile` | SlateLog, picture profiles, LUTs |
| `:core:common` | pure helpers, HEVC SPS inspection |
| `:core:ai` | AI contracts only |

## Documentation

- [`CHANGELOG.md`](CHANGELOG.md) — what shipped
- [`docs/roadmap.md`](docs/roadmap.md) — phase order, scope, status
- [`docs/backlog.md`](docs/backlog.md) — not-yet-shipped work
- [`docs/codebase-map.md`](docs/codebase-map.md) — navigation and change guide
- [`docs/test-devices.md`](docs/test-devices.md) — device evidence log
- [`docs/compatibility.md`](docs/compatibility.md) — tiers and quirks policy
- [`docs/device-database.md`](docs/device-database.md) — database and calibration
- [`docs/probe-schema.md`](docs/probe-schema.md) — probe JSON contract
- [`docs/ui-design.md`](docs/ui-design.md) — UI specification
- [`docs/adr/`](docs/adr/) — architecture decisions

## Building

Prerequisites: Flutter 3.47 or newer, Android SDK with platform 37, and JDK 17+
(CI pins 21). The Gradle root is `android/`, not the repository root.

```sh
flutter pub get
flutter build apk --debug      # or: flutter run
```

Module boundary checks and other Gradle tasks:

```sh
cd android
./gradlew checkModuleGraph
```

The app builds and shows the Phase 0 shell. There is no camera functionality
yet — the pipeline lands in Phase 2.

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md). Contributions are accepted under the
Developer Certificate of Origin (DCO); sign off your commits with `git commit -s`.

## License

MIT — see [LICENSE](LICENSE). The SlateCam name and branding are not covered by
the code license; see [TRADEMARK.md](TRADEMARK.md).
