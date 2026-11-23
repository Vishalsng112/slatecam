# Codebase map

The navigation index for SlateCam. Read this before opening files; it points to
the owner of each concern and the contracts between modules.

## Documentation roles (avoid duplicates)

| File | Owns | Do not put here |
|---|---|---|
| [`../CHANGELOG.md`](../CHANGELOG.md) | What shipped, by date/build | Future plans |
| [`roadmap.md`](roadmap.md) | Phase order, scope, status, exit criteria | Detailed task lists |
| [`backlog.md`](backlog.md) | Not-yet-shipped work and rejected ideas | History of shipped work |
| [`../README.md`](../README.md) | Product overview, build/run, principles | Phase status tables |
| [`compatibility.md`](compatibility.md) | Tier definitions, claimed-vs-verified rules, quirks policy | Device reports |
| [`device-database.md`](device-database.md) | Database schema, quirk actions, calibration workflow | Per-device evidence |
| [`probe-schema.md`](probe-schema.md) | Probe JSON contract | Implementation history |
| [`probe-coverage.md`](probe-coverage.md) | What the prober does/does not collect | Probe schema detail |
| [`test-devices.md`](test-devices.md) | Chronological device evidence | Current status |
| [`ui-design.md`](ui-design.md) | UI layout, controls, responsive rules | Native behaviour |
| [`adr/`](adr/) | Accepted architecture decisions | Open questions |
| [`../AGENTS.md`](../AGENTS.md) | Rules for future coding sessions, doc update policy | Product docs |

## Repository layout

```
lib/                         Flutter UI only
  main.dart                  app entry
  src/ui/                    capture screen, deck, plugins, painters
  src/pigeon/                generated Pigeon client
pigeon/slatecam.dart         Pigeon contract source
android/app/                 Flutter host, permissions, launch extras
android/bridge/              Flutter <-> Kotlin API, state mapping, profiles, switch pump
android/core/common/         pure helpers (HevcSps, ShutterAngle, GyroAligner)
android/core/probe/          capability prober, verifiers, report model
android/core/camera/         Camera2 controller, capabilities, modes
android/core/gpu/            EGL/GLES renderer and shader
android/core/codec/          HEVC/AAC recorder, gyro recorder
android/core/profile/        SlateLog, profiles, LUTs, input profile
android/core/storage/        declared, no source yet
android/core/device/         device database, quirks, calibration, gates
android/core/ai/             AI contracts only (NoOp engine)
android/feature/devicereport/ device report service
android/feature/capture/     declared, no Kotlin source
android/feature/playback/    declared, no Kotlin source
```

Dependency allowlist: `android/build.gradle.kts` (`checkModuleGraph`). The Gradle
root is `android/`, not the repository root.

## Runtime ownership

| Concern | Owner | Key files |
|---|---|---|
| Camera state, session, repeating request | `:core:camera` | `CameraController.kt` |
| Per-camera capabilities | `:core:camera` | `CameraCapabilities.kt` |
| Recording modes / common mode | `:core:camera` | `RecordingModeCatalog.kt` |
| Exposure P/S/M | `:core:camera` | `ExposureEngine.kt` |
| Lens-shading / vignette correction | `:core:camera` | `ShadingCorrection.kt`, `CameraController.applyControls` |
| Focus mode policy | `:core:camera` | `FocusMode.kt` |
| OIS / EIS requests | `:core:camera` | `CameraController.readCapabilities/applyControls` |
| Frame rendering, shader, overlays | `:core:gpu` | `GlPreviewRenderer.kt` |
| Recording/mux/audio | `:core:codec` | `ClipRecorder.kt`, `GyroRecorder.kt` |
| HEVC SPS verification | `:core:common` | `HevcSps.kt` |
| Look, log, LUT | `:core:profile` | `PictureProfile.kt`, `SlateLog.kt`, `Lut3D.kt` |
| Device quirks/calibration | `:core:device` | `DeviceDatabase.kt`, `DeviceDatabaseRepository.kt`, `CapabilityGates.kt` |
| Probe | `:core:probe` | `ProbeRunner.kt`, `source/`, `verify/`, `eval/` |
| Flutter state/commands | `:bridge` | `SlateCamBridge.kt`, generated Pigeon |
| Control surface | `lib/src/ui` | `capture_screen.dart` preview/dock, `control_deck.dart` console |
| Lens-switch encoder continuity | `:bridge` | `SwitchFramePump.kt`, `GlPreviewRenderer.repeatLastFrame` |
| Colour calibration math | `:core:profile` | `ColorCalibration.kt` |
| UI | `lib/src/ui` | `capture_screen.dart`, `control_deck.dart`, `zoom_plugin.dart` |

## Flows

### Control
`Flutter → Pigeon HostApi → SlateCamBridge → CameraController/codec/gpu/device`

### Frame
`Camera2 → renderer.cameraSurface (SurfaceTexture) → GlPreviewRenderer.drawFrame
→ preview EGL surface (overlays) + encoder EGL surface (clean) + analysis FBO`

### Recording
`SlateCamBridge.startRecording → ClipRecorder.start (encoder surface)
→ renderer draws each frame twice → MediaMuxer → MediaStore`

### Probe
`DeviceReport UI or --ez run_probe → ProbeRunner → sources + verifiers
→ files/probe.json (+ opt-in archive) → DeviceDatabaseRepository snapshot
→ CapabilityGates / mode catalog`

### Device database
`assets/device-database.json + files/device-database.json → merged DB →
quirks (force_8bit/antibanding/NR) + per-camera calibration → bridge applies
on camera change`

## Where to change what

| Change | Start here | Then |
|---|---|---|
| Add a recording mode | `RecordingModeCatalog.kt` canonical list | Probe preset/verifier mapping in `SlateCamBridge.tenBitVerifier`; probe schema if new size needs verification |
| Add a camera capability | `CameraCapabilities.kt` | Probe source/model if it must be reported; UI state only if shown |
| Fix a lens/role/AUX issue | `core/probe/source/CameraSource.kt`, `eval/CameraRoles.kt` | Probe schema, device docs |
| Change preview/recording orientation | `GlPreviewRenderer` shader/`setGlRotation` | `CameraController.glRotationDegrees`, ADR 0006 |
| Add colour transform/log/LUT | `:core:profile` + `ShaderSource.FRAGMENT` | `GlPreviewRenderer.drawQuad` uniforms; ADR 0005 |
| Add an AI feature | `:core:ai/AiEngine.kt` | Renderer offscreen pass + `AiFrameGate`, bridge config/UI |
| Add a UI control | `pigeon/slatecam.dart` | Regenerate Pigeon; bridge; `control_deck.dart`/`capture_screen.dart` |
| Add a device quirk | `assets/device-database.json` | `QuirkActions` consumer; report evidence |
| Change grading/HLG policy | `CapabilityGates.kt`, `ClipRecorder.kt` | Probe verification, ADR 0004 |

## Contracts

- **Pigeon:** `pigeon/slatecam.dart` is the source of truth. Regenerate both
  clients after edits:
  `dart run pigeon --input pigeon/slatecam.dart --dart_out lib/src/pigeon/slatecam.g.dart --kotlin_out android/bridge/src/main/kotlin/io/github/vishalsng112/slatecam/bridge/pigeon/SlateCamPigeon.kt --kotlin_package io.github.vishalsng112.slatecam.bridge.pigeon`
- **Probe JSON:** schema 1.9.0, `docs/probe-schema.md`.
- **Device DB:** `slatecam.device-db` v1, `docs/device-database.md`.
- **Orientation/aspect:** ADR 0006.
- **Lens-shading (vignette) correction:** ADR 0009.
- **Stabilisation and focus modes:** ADR 0010.
- **Shoot profiles/movie mode:** ADR 0007.

## Keeping docs current

- Shipped: `CHANGELOG.md`.
- Phase order/status: `docs/roadmap.md`.
- Unfinished work: `docs/backlog.md` (not a history).
- Contract/architecture change: owning doc + ADR.
- Device evidence: `docs/test-devices.md` with build number and outputs.

`AGENTS.md` repeats these rules for coding sessions.

## Build, test, run

```sh
flutter pub get
flutter analyze
flutter build apk --debug
cd android && ./gradlew checkModuleGraph :core:common:testDebugUnitTest \
  :core:probe:testDebugUnitTest :core:device:testDebugUnitTest \
  :core:camera:testDebugUnitTest
scripts/probe.sh <serial> [out.json]     # PROBE_WAIT=160 for Main10-failing devices
```

Debug install on the Pad 3 uses push + `pm install -r -t -d -g` because streamed
installs can drop adbd. Probe launch:
`am start -n io.github.vishalsng112.slatecam/.MainActivity --ez run_probe true`.

## Known current limits

- True 10-bit GL intermediate is not available; HLG encoder surface is
  RGBA8888, but the encoded stream is Main10/HLG and SPS-checked. Pad movie mode
  is 10-bit; POCO stays SDR because its encoder fails Main10.
- Colour matching depends on measured per-camera matrices (not shipped).
- Shoot profiles live in `ShootProfileStore` (pure Kotlin, persisted through a
  storage interface; JVM tests in `bridge/src/test`).
- The Control Console is one surface with a fixed record/lens/mode bar; avoid
  reintroducing tabs or dropdowns for primary actions.
- Phase 5.5 remaining: captured colour-target data; the solver already exists.

- Feature modules `:feature:capture` and `:feature:playback` are empty.
