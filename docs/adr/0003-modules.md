# ADR 0003 — Module architecture

- **Status:** Accepted
- **Date:** 2026-11-23
- **Phase:** 0
- **Depends on:** ADR 0001 (identity), ADR 0002 (SDK levels)

## Context

SlateCam is a single application built as a monolith, but the code must stay
modular: the camera, GPU, codec, and colour-science concerns have different
testability requirements, different failure modes, and different maintainers
over time. The Flutter UI must not be able to reach into camera internals, and
the colour-science code must be testable without a device.

Two layout options exist for a Flutter UI plus native Kotlin core:

1. **Flutter-first** — `flutter create` project at the repository root; Kotlin
   modules live under `android/` as Gradle subprojects of the Flutter host app.
2. **Android-first** — a native Android app consuming a Flutter *module* as an
   AAR or source dependency.

## Decision

**Flutter-first layout.** The Flutter project is the root; the Gradle build
lives in `android/` and includes the Kotlin modules as subprojects.

```
slatecam/
├─ pubspec.yaml
├─ lib/                     Dart UI only
├─ android/
│  ├─ settings.gradle.kts   includes :app and every :core / :feature module
│  ├─ gradle/libs.versions.toml
│  ├─ build-logic/          convention plugins (shared Android config)
│  ├─ app/                  Flutter host, wiring only
│  ├─ bridge/               Pigeon + EventChannel contracts
│  ├─ core/
│  │  ├─ common/            units, timecode, Result, JSON
│  │  ├─ probe/             capability prober, report schema
│  │  ├─ camera/            Camera2 sessions, state machine, quirks
│  │  ├─ gpu/               EGL/GLES pipeline, shaders, 10-bit path
│  │  ├─ codec/             MediaCodec / MediaMuxer, audio
│  │  ├─ profile/           log curve, LUTs, colour science
│  │  ├─ storage/           MediaStore, free space, thermal guard
│  │  ├─ ai/                Phase 6 contracts (LiteRT implementation deferred)
│  │  └─ device/            device database, quirks, calibration, opt-in reports
│  └─ feature/
│     ├─ capture/           record screen logic and state wiring
│     ├─ playback/          clip review
│     └─ devicereport/      probe results and export
└─ docs/
```

Flutter-first was chosen because the UI is Flutter *only* and the native side
has no standalone Activity of its own. The Android-first alternative requires
a `flutter build aar` step or an `include_flutter.groovy` source dependency,
which adds build fragility for no benefit when the host app is the product.

### Module table

| Module | Depends on | May use Flutter | Notes |
|---|---|---|---|
| `:core:common` | — | no | No Android UI; minimal Android APIs |
| `:core:profile` | — | no | **Pure Kotlin/JVM.** No `android.*` imports |
| `:core:probe` | `:core:common` | no | Reads platform APIs directly |
| `:core:camera` | `:core:common` | no | Owns the camera state machine |
| `:core:gpu` | `:core:common` | no | Owns the EGL/GLES thread |
| `:core:codec` | `:core:common` | no | Encoder, muxer, audio capture |
| `:core:storage` | `:core:common` | no | MediaStore, thermal, free space |
| `:core:ai` | — | no | Phase 6 AI contracts; LiteRT implementation deferred |
| `:core:device` | `:core:probe`, `:core:profile` | no | Device database, quirks, calibration, opt-in reports |
| `:bridge` | `:core:common` | **yes** | Pigeon DTOs and channel adapters |
| `:feature:capture` | all `:core:*`, `:bridge` | yes | May not be depended on by other features |
| `:feature:playback` | `:core:common`, `:bridge` | yes | |
| `:feature:devicereport` | `:core:probe`, `:core:common`, `:bridge` | yes | |
| `:app` | every feature, `:bridge` | yes | Wiring only; no business logic |

### Dependency rules

1. Dependencies point **downward only**: `app → feature → core`. A `core`
   module must never depend on a `feature`, `:bridge`, or `:app`.
2. `:core:*` modules must not depend on Flutter. Pixels and camera logic stay
   out of the Dart bridge by construction, not by discipline.
3. `:core:profile` is pure Kotlin. Colour science is tested on the JVM, in
   milliseconds, with no emulator.
4. `feature` modules do not depend on each other. Shared behaviour moves down
   into `core`, or the host app wires it.
5. `:app` contains wiring and lifecycle only. If it grows a `when` on camera
   state, that logic belongs in `:core:camera`.
6. DTOs never cross from `:core:*` to `:bridge` directly. `:bridge` maps domain
   models to Pigeon types, so a UI-facing rename never forces a core change.

Rules 1–5 are enforced by a `checkModuleGraph` Gradle task that fails the build
if a forbidden project dependency appears. Rule 6 is a review rule.

### Versions and toolchain

Pinned in `android/settings.gradle.kts` (plugins) and, once library
dependencies exist, `android/gradle/libs.versions.toml`:

| Component | Version | Verified |
|---|---|---|
| Gradle | 9.3.1 | Flutter 3.47 template default |
| Android Gradle Plugin | 9.1.0 | Flutter 3.47 template, supports SDK 37 |
| Kotlin | 2.4.0 | Flutter 3.47 template |
| compileSdk / targetSdk | 37 | installed locally |
| minSdk | 33 | ADR 0002 |
| JDK | 17 bytecode target | see risk below |

**Risk:** the local JDK is 25. AGP 9.1 supports JDK 17 through 24 officially.
Gradle 9.3.1 runs on 25 locally, but CI should pin JDK 21. The bytecode target
stays 17 regardless, so nothing in the app depends on the build JDK.

**Known warning (accepted):** Flutter 3.47 warns that library modules apply the
Kotlin Gradle Plugin and that a future Flutter version will require AGP Built-in
Kotlin instead. The build is correct today. Migrating the ten library modules is
a Phase 10 maintenance task and must not block Phase 1; the warning is recorded
here so it is not rediscovered as a surprise.

### Threading ownership

Each `core` module owns its threads and exposes `StateFlow`-based state. No
module creates threads on another module's behalf.

| Module | Thread | Owns |
|---|---|---|
| `:core:camera` | dedicated `HandlerThread` + `Handler` | Camera2 device, session, captures |
| `:core:gpu` | dedicated GL thread with EGL context | preview surface, encoder input surface, shaders |
| `:core:codec` | encoder thread(s) | `MediaCodec` drain loop, `MediaMuxer` writes |
| `:core:storage` | IO dispatcher | file creation, free-space and thermal polling |
| `:bridge` | main thread | Pigeon calls in, `EventChannel` out |

The camera session's `OutputSurface` is the GPU module's preview surface. The
encoder's input surface is the same GPU module's output surface. That is the
single pixel path from ADR-level rule 3 and it is why `:core:gpu` sits between
`:core:camera` and `:core:codec` rather than beside them.

## Consequences

- A Flutter engine dependency appears only in `:bridge`, `:feature:*`, and
  `:app`. Deleting the UI one day would leave the core intact.
- Colour-science and probe-schema tests run as fast JVM tests; only camera,
  GPU, and codec modules need a device.
- The Gradle root is `android/`, which surprises contributors expecting the
  repository root. The README and CONTRIBUTING must say so explicitly.
- `checkModuleGraph` adds a build task that must be maintained as modules are
  added; it is cheap and catches the most common monolith rot.
- Since `:bridge` owns DTO mapping, every new UI feature touches at least two
  modules. This is deliberate friction that keeps the boundary honest.

## Alternatives considered

- **Android-first with a Flutter AAR** — rejected: extra build step, worse
  hot-reload story, and the native app has no independent value.
- **One Gradle module with package-level separation** — rejected: no compiler
  enforcement, and the GPU/codec modules would leak into UI code within weeks.
- **Multi-repository** — rejected: version skew and PR overhead outweigh the
  isolation benefit at this size.
- **Dynamic feature modules** — rejected: they solve install size, which is not
  a problem here, and add manifest complexity.

## Amendment — Phase 2

Implementation changed three details without changing the layering:

1. **State is pushed through a Pigeon `FlutterApi`, not an `EventChannel`.**
   Commands arrive on a Pigeon `HostApi`; state snapshots go back on a typed
   Pigeon callback. One contract, one codec, no second serialization path.
   `EventChannel` remains the right choice for high-frequency telemetry such as
   histograms, which are not part of this contract.
2. **`:bridge` compiles against the Flutter embedding jar with `compileOnly`.**
   The app supplies the classes at runtime. This keeps the module graph honest:
   `:bridge` is still the only module that references Flutter types.
3. **`:bridge` now depends on `:core:camera` and `:core:gpu`**, and the
   `checkModuleGraph` allowlist was updated. `:app` still depends only on
   `:bridge` plus features, so the host stays wiring-only.

Threading is unchanged: camera callbacks on `slatecam-camera`, all GL on
`slatecam-gl`, state emission on the main thread.
