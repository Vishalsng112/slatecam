# ADR 0024 — Telemetry push and preview presentation

- **Status:** Accepted
- **Date:** 2026-11-25
- **Phase:** 5 (viewfinder)
- **Depends on:** ADR 0006 (single pixel path), ADR 0022 (viewfinder presentation)

## Context

The recorded file was smooth but the viewfinder juddered. The GL path was not at
fault: on the Pad 3 (build 2109, 1440×1080 30p) draw timestamps were a clean
33.3 ms (277/289 frames at exactly 33 ms; only three warm-up stragglers).
SurfaceFlinger told the real story: most presents were 33.3 ms apart, punctuated
by repeated **83–100 ms** holds followed by catch-up frames.

Instrumentation tied the holds to the state path, not the camera:

- `pushState()` built the entire `CameraState` and `BasicMessageChannel` send
  took **50–160 ms**; `producer.scheduleFrame()` was delayed **34–65 ms** on the
  platform thread.
- Suppressing periodic pushes restored a perfect 33.3 ms cadence.
- Splitting the payload to a small `CameraTelemetry` message was not enough:
  the **`setState` in the Flutter handler rebuilt the whole preview stack and
  the entire Control Console at ~5 Hz**, and presentation still had 83–100 ms
  gaps.
- Muting only that `setState` (mutating state without rebuilding) returned a
  perfect 33.3 ms cadence.
- Re-adding the histogram/waveform arrays (1280 ints) to every telemetry push
  brought 66.7 ms gaps back even when no card was visible.

So two independent costs had to be removed: the size/frequency of the snapshot
crossing the bridge, and the scope of the Flutter rebuild that consumed it.

## Decision

1. **Two state channels.** `onState` remains the full configuration snapshot
   (modes, profiles, lens lists, device data) and is pushed only on
   configuration changes — camera open/close/switch, mode/profile/WB/monitor
   commands, recording start/stop and finalization.
2. **`CameraTelemetry` for fast readouts.** A small Pigeon FlutterApi message
   carries ISO, shutter, focus, WB, zoom, record state/timer, error, peaking /
   zebra / false-colour flags, and analysis visibility-gated arrays. It is sent
   at analysis/exposure rate.
3. **Selective Flutter rebuilds.** `CaptureScreen` keeps a
   `ValueNotifier<CameraTelemetry?>`; `onTelemetry` mutates the existing
   `CameraState` and notifies that notifier instead of calling `setState`.
   `ValueListenableBuilder`s wrap only the live strips: status pills, bottom
   dock, zoom control, monitor cards, recording border, and the console's
   Exposure card. The preview Texture and the rest of the console do not rebuild
   on telemetry.
4. **Analysis arrays only when visible.** The viewer tells the bridge through
   `setAnalysisOverlayVisible` whether histogram/waveform cards are shown. The
   arrays are omitted otherwise, and when shown they are sent on the 2 Hz
   analysis publish only — not on every controller emit.
5. **Open/camera transitions always send full state.** A telemetry-only update
   cannot express `isOpen` or a new camera's capability surface; those must use
   `onState`.

## Consequences

Measured on the Pad 3 (build 2120, 1440×1080 30p, 60 Hz display mode):

- Idle preview: **126/127** SurfaceFlinger presents at 33.2–33.5 ms, no 83/100 ms
  holds.
- Recording: mostly 33.3 ms, with about three 83 ms holds in five seconds (from
  the encoder-path load, not state pushes).
- Histogram/waveform visible: about six 50 ms holds in five seconds from the
  2 Hz array push; acceptable for an optional diagnostic overlay.
- The console's live ISO/shutter readouts still update through the telemetry
  notifier, and the REC border/timer update without rebuilding the preview.

Remaining work: the analysis arrays could move to a compact binary payload or be
decoded off the UI thread to remove the residual histogram-on hitches. The
full `onState` still carries the arrays on rare configuration pushes.

## Alternatives considered

- **Lower `pushState` frequency only** — reduces but does not remove the hitches,
  and the console still rebuilds on every update.
- **`RepaintBoundary` around the preview** — the stall is in the UI-thread build,
  before raster; caching paint does not help.
- **Move the console to another Flutter view** — larger change; the selective
  builders remove the cost with far less surface area.
- **Keep one `CameraState` but push it as a diff** — requires a diffing contract
  and still rebuilds the console unless the UI side also splits.
