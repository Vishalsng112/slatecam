# ADR 0032 — Portrait UI and orientation-tagged recording

- **Status:** Accepted
- **Date:** 2026-11-26
- **Phase:** 3 (recording correctness) / 5.5 (shoot profiles)
- **Depends on:** ADR 0022 (viewfinder presentation), ADR 0026 (one pixel path)

## Context

The activity was locked to `sensorLandscape`, so the console and recording were
landscape-only. The operator needs the same app upright on a phone or tablet,
with portrait clips that play the way they were framed. Duplicating the console
or opening a second camera-to-encoder path for portrait would violate the
one-pixel-path and reuse rules.

Camera2 separates three rotations:

- `SENSOR_ORIENTATION` — how the sensor is mounted in the device;
- display rotation — how the activity is currently presented;
- MP4 orientation hint — the clockwise rotation a player applies.

The encoder receives a landscape frame in every orientation. Portrait can be
either physically rotated pixels or the same landscape frame plus a container
hint; the latter keeps one encoder path and one GL frame.

## Decision

1. **The activity follows `fullSensor`.** `MainActivity.onConfigurationChanged`
   asks the bridge to recompute rotation without reopening the camera.
2. **Preview and encoder share one landscape GL frame.** The shader rotates by
   `SENSOR_ORIENTATION`; the encoder always receives that frame. The bridge
   exposes a separate `recordingOrientationHint` from the current display.
3. **Portrait playback is metadata, not a second pixel path.** `ClipRecorder`
   writes the hint (`90` on the POCO in portrait) so players show `1080×1440`
   for a `1440×1080` encode. Landscape takes keep `0` and are unchanged.
4. **Flutter presents the playback composition.** The viewfinder applies
   `RotatedBox(quarterTurns: hint / 90)` and swaps the aspect for odd turns.
   Tap-to-focus inversely rotates the normalized point before the existing
   GL viewfinder-to-buffer transform, so focus regions stay correct.
5. **One responsive widget set.** Landscape docks the console beside the
   viewfinder; portrait stacks the viewfinder above the same console at roughly
   half height. No orientation-specific copies of the cards, zoom rail or dock.
6. **A take locks its orientation.** Rotation while idle updates preview and
   hint live. Rotation during recording is deferred until the clip is
   finalized, so the encoder stream never changes orientation mid-clip.

## Consequences

- POCO X6 Pro: portrait viewfinder matches the auto-rotated playback frame;
  a portrait clip is `1440×1080` with a `-90` display matrix and plays as
  `1080×1440`. Landscape clips remain `1440×1080` with no rotation.
- Pad 3: portrait uses the stacked layout and the console's three responsive
  columns below the viewfinder; rotation during idle is live.
- Portrait offers `9:16` and `4:3` as family labels. Both are upright takes:
  the sensor frame plus the quarter turn, delivered as `1080×1920` and
  `1080×1440` respectively. This is the chosen option after inspecting the raw
  sensor feed: the native frame is landscape and sideways in portrait, so
  upright text requires the quarter turn, and 4:3 stays the familiar family
  label rather than becoming 3:4. Naming follows phone-camera convention.
- The viewfinder never crops: the red recording border wraps the actual frame
  for both families, all four sides.
- Mid-take rotation does not rotate the clip; the operator sees the live
  preview continue for the current take, then the new orientation applies.

## Alternatives considered

- **Encode portrait dimensions directly** — rejected: it would require a
  second encoder size negotiation and a second GL target layout for every
  mode; the MP4 hint is the standard Camera2 approach.
- **Rotate the GL preview only** — rejected: preview and playback diverged
  (the user saw a composition the clip did not contain).
- **Leave the activity in landscape and rotate only the widget tree** —
  rejected: the system bars, safe areas and touch coordinates would stay
  landscape-oriented.
- **Recreate the activity on rotation** — rejected: it tears down the camera
  and preview for a purely presentational change.
