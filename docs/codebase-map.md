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
android/core/profile/        picture profiles, LUTs, input profile
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
| Zoom target and smooth ramp | `:core:camera` | `SmoothZoom.kt`, `CameraController.setZoomRatio` |
| Per-camera capabilities | `:core:camera` | `CameraCapabilities.kt` |
| Recording modes / common mode | `:core:camera` | `RecordingModeCatalog.kt`, `RecordingMode` (resolution/aspect/fps/bitrate/bitDepth), `RecordingBitrate` |
| Exposure P/S/M | `:core:camera` | `ExposureEngine.kt` |
| Manual white balance | `:core:camera`, `:core:gpu` | `ManualWhiteBalance.kt`, `CameraController.applyControls`, `GlPreviewRenderer.setManualWhiteBalance` |
| Metering patterns and response speed | `:core:camera` | `Metering.kt`, `ExposureEngine.kt` |
| Luma grid for metering | `:core:gpu` | `FrameAnalysis.kt`, `GlPreviewRenderer.runAnalysis` |
| Lens-shading / vignette correction | `:core:camera` | `ShadingCorrection.kt`, `CameraController.applyControls` |
| Focus mode policy | `:core:camera` | `FocusMode.kt` |
| Focus interface, modes, CAF default | `:core:camera` | `FocusController.kt`, `FocusMode.kt` |
| Software focus engine / scenarios | `:core:camera` | `SoftwareFocus.kt`, `FocusController.onSharpness` |
| Focus sharpness score | `:core:gpu` | `FrameAnalysis.focusScore`, `GlPreviewRenderer.focusScore` |
| FP16 encoder intermediate | `:core:gpu` | `GlPreviewRenderer.renderEncoder`, `ensureIntermediate` |
| Scene-linear shader / output routing | `:core:gpu` | `GlPreviewRenderer.ShaderSource.FRAGMENT` |
| Flicker-safe exposure grid | `:core:common` | `FlickerGrid`, `CameraController.setShutterNs` |
| Viewfinder tap-to-focus / pinch | `lib/src/ui/capture_screen.dart`, `:core:gpu` transform, `:core:camera` AF region | `GlPreviewRenderer.viewfinderToBuffer`, `CameraController.setFocusPoint` |
| Orientation / portrait recording | `MainActivity`, `:bridge`, `:core:camera`, `:core:gpu`, `lib/src/ui/capture_screen.dart` | `onDisplayRotationChanged`, `recordingOrientationHint`, `RotatedBox` preview |
| Picture profiles / look pipeline | `:core:profile` | `PictureProfile.kt`, `PictureProfileLibrary.kt`, `SlateCamBridge.applyProfile` |
| Custom profile persistence | `:bridge` | `setProfileSettings`, `encodeSettings`/`decodeSettings` |
| Per-lens calibration / WB / shadow | `:core:device` | `CalibrationEntry`, `DeviceDatabase.calibrationFor`, `InputProfile` |
| Independent viewfinder resolution | `:core:gpu`, `:bridge`, Flutter | `GlPreviewRenderer` capture/preview sizes, `SlateCamBridge.previewOutputFor` |
| OIS / EIS requests | `:core:camera` | `CameraController.readCapabilities/applyControls` |
| Frame rendering, shader, overlays | `:core:gpu` | `GlPreviewRenderer.kt` |
| Live telemetry vs configuration state | `:bridge`, Flutter | `SlateCamBridge.pushTelemetry/pushState`, `CameraTelemetry`, `capture_screen.dart` builders |
| Recording/mux/audio | `:core:codec` | `ClipRecorder.kt`, `GyroRecorder.kt`, `AudioInputs.kt`, `AudioMonitor.kt`, `AudioMeter.kt` |
| Clip library / playback | `:core:storage`, Flutter | `MediaLibrary.kt`, `playback_screen.dart` |
| HEVC SPS verification | `:core:common` | `HevcSps.kt` |
| Look, LUT | `:core:profile` | `PictureProfile.kt`, `Lut3D.kt` |
| Device quirks/calibration | `:core:device` | `DeviceDatabase.kt`, `DeviceDatabaseRepository.kt`, `CapabilityGates.kt` |
| Probe | `:core:probe` | `ProbeRunner.kt`, `source/`, `verify/`, `eval/`, `eval/LogicalZoom.kt` |
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

### State
`CameraController / renderer analysis → SlateCamBridge
→ onState (configuration) + onTelemetry (live readouts)
→ CaptureScreen ValueNotifier → status/dock/zoom/monitor/console-exposure builders`

`onTelemetry` deliberately does **not** rebuild the preview Texture or the whole
Control Console; see ADR 0024.

### Recording
`SlateCamBridge.startRecording → ClipRecorder.start (encoder surface)
→ renderer draws each frame twice → MediaMuxer → MediaStore`

### Media
`MediaStore (Movies/SlateCam) → :core:storage MediaLibrary
→ Pigeon media API → Flutter playback_screen → video_player (ExoPlayer)`

The camera closes while the clips screen is open and reopens when it is popped.

### Audio
`AudioManager inputs → :core:codec AudioInputs → AudioMonitor (pre-roll)
→ 10 Hz AudioTelemetry → Flutter audio card`
`AudioInputs → ClipRecorder audio thread (gain + meter) → AAC 48 kHz stereo
→ MediaMuxer`

The monitor and recorder are mutually exclusive: the monitor stops before the
recorder opens its own `AudioRecord` and restarts after finalize. Metering runs
on its own channel so it never rebuilds camera state. See ADR 0028.

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
| Add/change audio routing or metering | `:core:codec/AudioInputs.kt`, `AudioMonitor.kt`, `AudioMeter.kt` | `ClipRecorder` audio loop, `SlateCamBridge` ticker, `AudioTelemetry`, audio card |
| Add a device quirk | `assets/device-database.json` | `QuirkActions` consumer; report evidence |
| Change grading/HLG policy | `CapabilityGates.kt`, `ClipRecorder.kt` | Probe verification, ADR 0004 |

## Contracts

- **Pigeon:** `pigeon/slatecam.dart` is the source of truth. Regenerate both
  clients after edits:
  `dart run pigeon --input pigeon/slatecam.dart --dart_out lib/src/pigeon/slatecam.g.dart --kotlin_out android/bridge/src/main/kotlin/io/github/vishalsng112/slatecam/bridge/pigeon/SlateCamPigeon.kt --kotlin_package io.github.vishalsng112.slatecam.bridge.pigeon`
  Pigeon 30 emits `Uint8List` in generated code but omits it from the
  `dart:typed_data` show-list; add it (and the `unnecessary_import` ignore) after
  regenerating, and strip trailing whitespace in the Kotlin callbacks.
- **Audio API:** `listAudioInputs`, `setAudioInput`, `setAudioGain`,
  `refreshAudioInputs`; meter snapshots arrive via `onAudio` (ADR 0028).
- **Probe JSON:** schema 1.12.0, `docs/probe-schema.md`.
- **Media API:** `listMediaClips`, `mediaStorageInfo`, `mediaClipThumbnail`,
  `deleteMediaClip`, `shareMediaClip` in `pigeon/slatecam.dart`; MediaStore
  ownership is in `:core:storage` (ADR 0026).
- **Device DB:** `slatecam.device-db` v1, `docs/device-database.md`.
- **Orientation/aspect:** ADR 0006.
- **Lens-shading (vignette) correction:** ADR 0009.
- **Stabilisation and focus modes:** ADR 0010.
- **Exposure metering and response speed:** ADR 0011.
- **Software focus engine and scenarios:** ADR 0012.
- **Viewfinder gestures and tap-to-focus:** ADR 0013.
- **Viewfinder presentation and resolution control:** ADR 0022.
- **Telemetry push and preview presentation:** ADR 0024.
- **Colour-science trim (superseded):** ADR 0014.
- **Independent viewfinder resolution:** ADR 0015.
- **Staged colour architecture:** ADR 0016.
- **FP16 encoder intermediate:** ADR 0017.
- **Scene-linear shader and routing:** ADR 0018.
- **Flicker-safe manual exposure:** ADR 0019.
- **Picture profiles and look pipeline:** ADR 0020.
- **User picture profiles:** ADR 0025.
- **Media library and playback:** ADR 0026.
- **Manual white balance:** ADR 0021.
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
  :core:camera:testDebugUnitTest :core:codec:testDebugUnitTest
scripts/probe.sh <serial> [out.json]     # PROBE_WAIT=160 for Main10-failing devices
```

Debug install on the Pad 3 uses push + `pm install -r -t -d -g` because streamed
installs can drop adbd. `-g` pre-grants runtime permissions, so it does **not**
exercise the first-run prompt: drop `-g` (or uninstall and reinstall without it)
when testing camera access. Probe launch:
`am start -n io.github.vishalsng112.slatecam/.MainActivity --ez run_probe true`.

## Known current limits

- True 10-bit GL intermediate is not available; HLG encoder surface is
  RGBA8888, but the encoded stream is Main10/HLG and SPS-checked. Pad movie mode
  is 10-bit; POCO stays SDR because its encoder fails Main10.
- Colour matching depends on measured per-camera matrices (not shipped).
- Logical zoom handoff is implemented and gated by the recorded
  `logicalZoomHandoff` probe verdict; hidden macro lenses remain excluded
  (ADR 0029).
- Dual-session lens switching is implemented for exposed pairs with a passing
  `dualSessionSwitch` verdict; the frame pump remains for other pairs/modes
  (ADR 0030).
- Audio capture uses PCM16 48 kHz stereo; input selection and gain are data.
  Headphone monitoring, 24-bit capture and a limiter are not implemented.
- Manual white balance anchors on the nearest HAL AWB preset and applies the
  kelvin/tint residual in the scene-linear shader; absolute kelvin accuracy is
  the preset's nominal CCT plus a black-body approximation (ADR 0021).
- Shoot profiles live in `ShootProfileStore` (pure Kotlin, persisted through a
  storage interface; JVM tests in `bridge/src/test`).
- The Control Console is one surface with a fixed record/lens/mode bar; avoid
  reintroducing tabs or dropdowns for primary actions.
- Phase 5.5 remaining: captured colour-target data; the solver already exists.

- `:feature:capture` and `:feature:playback` remain marker modules; media
  access lives in `:core:storage` and the Flutter clips screen.
