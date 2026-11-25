# Agent guide

Read these first, in order:

1. [`docs/codebase-map.md`](docs/codebase-map.md) — modules, flows, where to change what.
2. [`docs/roadmap.md`](docs/roadmap.md) — current phase/status and exit criteria.
3. [`CHANGELOG.md`](CHANGELOG.md) — what already shipped.
4. [`docs/backlog.md`](docs/backlog.md) — forward-looking work only.

## Rules

- **No device-specific branches.** Gate features with probe verdicts, live
  `CameraCharacteristics` and device-database data. Never on brand/model/SoC.
- **Probe first.** Claimed capability is not verified capability; read the SPS,
  session result or recording before enabling a feature.
- **One pixel path.** Camera → GL → preview/encoder/analysis. Do not open a
  second camera-to-encoder path a codec or AI extra.
- **No legacy/backport.** Change the current architecture; do not add
  compatibility shims for superseded internal APIs.
- **Kotlin owns state.** Flutter renders Pigeon snapshots and sends commands.

## After a change

- Shipped behaviour: add an entry to `CHANGELOG.md`.
- Phase status/order changed: update `docs/roadmap.md`.
- New unfinished work: add it to `docs/backlog.md` under the owning phase; do
  not duplicate shipped history there.
- New architecture decision: add an ADR under `docs/adr/`.
- New/changed contract (Pigeon, probe schema, device DB): update the owning doc
  and regenerate Pigeon when needed.

## Verify before claiming done

```sh
flutter analyze
flutter build apk --debug
cd android && ./gradlew checkModuleGraph :core:common:testDebugUnitTest \
  :core:probe:testDebugUnitTest :core:device:testDebugUnitTest \
  :core:camera:testDebugUnitTest :core:codec:testDebugUnitTest \
  :core:profile:test :bridge:testDebugUnitTest
```

Real-device tests and evidence go in `docs/test-devices.md` with the build
number and measured outputs. The test bench is the OnePlus Pad 3 and POCO X6
Pro; both Phase 3 and the Phase 5.5 foundation have been exercised on each.
