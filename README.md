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

> **Status: Phase 5 — advanced features (in progress).**
> Shutter angle is implemented from the exposure/fps relationship and shown
> live (20 ms at 30 fps reads 216°). Gyro samples are captured during recording
> with realtime timestamps and written to a sidecar. Frame alignment and
> container embedding remain, as do RAW burst and dual camera.
>
> Phases 0–4 are complete: preview, manual and capture controls, 8-bit SDR
> recording via MediaStore, profiles, LUTs, and monitoring tools. HLG remains
> gated because this device's encoder claims Main10 while emitting 8-bit Main.

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

```
Flutter (Dart)            Kotlin (single source of truth)
  ├─ controls / menus  ←→  Pigeon commands
  ├─ profile editor    ←   EventChannel state + telemetry
  └─ device reports    ←   GPU texture preview (never pixel data)
```

Planned layout (monolithic modular, Flutter-first — the Gradle root is
`android/`, not the repository root):

```
lib/                     Dart UI only
android/app              Flutter host, wiring only
android/bridge           Pigeon + EventChannel contracts
android/core/common      units, timecode, Result, JSON helpers
android/core/probe       capability prober + versioned schema
android/core/camera      Camera2 sessions, state machine, quirks table
android/core/gpu         EGL/GLES pipeline, shaders, 10-bit path
android/core/codec       MediaCodec / MediaMuxer, audio
android/core/profile     log curve, LUTs, color science (pure Kotlin)
android/core/storage     MediaStore, free space, thermal guard
android/feature/*        capture, playback, device reports
```

Dependencies point downward only (`app → feature → core`); a `checkModuleGraph`
task fails the build otherwise. See
[docs/adr/0003-modules.md](docs/adr/0003-modules.md).

## Roadmap

| Phase | Theme | Outcome |
|---|---|---|
| 0 | Foundation | Name, license, repo, layout, test phones |
| 1 | Prober | Capability prober running on real devices; verified feature dictionary |
| 2 | Camera core + preview | Camera2 session, Pigeon bridge, texture preview, manual ISO/shutter/focus/WB |
| 3 | Recording | 8-bit then 10-bit HLG HEVC with audio, storage, thermal guard |
| 4 | GPU + profiles | Log curve, LUTs, picture profiles, shader-based histogram/zebra/waveform |
| 5 | Advanced | RAW burst, shutter angle, framing guides, gyro metadata, dual camera |
| 6 | AI (optional) | LiteRT subject tracking and focus assist; optional local assistant |
| 7 | Device database | Opt-in reports, device table, per-device quirk overrides |
| 8 | Quality | Real-device stress tests, profiling, multi-version checks |
| 9 | Release | Signed APKs, F-Droid, user docs, beta feedback |
| 10 | Maintenance | Track new Android APIs, grow the device database |

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
