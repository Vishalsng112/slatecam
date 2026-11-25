# ADR 0028 — Audio input, monitoring and metering

- **Status:** Accepted
- **Date:** 2026-11-25
- **Phase:** 3/5.5 (recording, shoot surface)
- **Depends on:** ADR 0003 (modules), ADR 0024 (state channels)

## Context

The recorder already captured AAC-LC 48 kHz stereo through `AudioRecord`, but
the app had no audio surface: no input choice, no levels before a take, no gain,
and no way to tell whether a clip had usable sound. The probe already reported
input classes and per-device formats, so the capability data existed without a
pipeline behind it. A professional camera cannot ship with an invisible
microphone.

Audio also has a different failure mode from video: a silently missing or
clipped track is not obvious until post. The UI must show the route and level
*before* recording, not after.

## Decision

1. **One shared audio core.** `:core:codec` gains `AudioInputs`,
   `AudioMonitor`, `AudioMeter`/`AudioLevel`. `AudioInputs` enumerates
   `AudioManager` inputs, persists a stable key (`type:address:name`), routes
   with `AudioRecord.setPreferredDevice`, and keeps the existing clean-source
   order (`unprocessed → camcorder → mic`) with no AGC or noise suppression.
2. **Exclusive ownership.** `AudioMonitor` holds the selected input while the
   camera is open for pre-roll levels. Recording stops the monitor, opens the
   recorder's own `AudioRecord` with the same key/gain, and restarts the monitor
   after finalize. The two never capture simultaneously.
3. **Gain before meter and encoder.** A software trim in −12…+24 dB is applied
   to PCM16 in place, clamped, and flagged as clipped. The meter therefore shows
   exactly what is recorded. Gain changes take effect on the next buffer, even
   mid-take.
4. **Pure, tested meter math.** `AudioMeter` computes per-channel peak and RMS
   in dBFS, clamps at −120 dBFS, duplicates mono to both meters, and detects
   full-scale/clamped samples. It has no Android dependency and is covered by
   JVM tests.
5. **Metering is a separate channel.** `AudioTelemetry` is pushed at ~10 Hz via
   `onAudio`; selection, route, gain and errors live in `CameraState`. The
   10 Hz meter rebuilds only the audio card, never the viewfinder or the whole
   console (same reasoning as ADR 0024).
6. **Honest states.** Missing permission, no usable input, a failed read and a
   silent input are explicit. Recording without audio is reported instead of
   silently omitting the track. Hotplug refreshes the list, falls back to the
   platform route when the selected device disappears, and never interrupts a
   take.
7. **No device branches.** Inputs, rates and routes come from `AudioManager`;
   the choice is data. Bluetooth, USB and built-in mics follow the same path.

## Consequences

- The operator can choose a microphone, see pre-roll levels, set gain and trust
  that the meter matches the encoded track.
- AAC is now 256 kbps / 48 kHz / stereo. PCM16 is still the capture format;
  the AAC encoder receives the gained PCM.
- The meter uses the audio thread's own samples, so it is not delayed by the
  video pipeline or Pigeon state.
- Pre-roll monitoring keeps a microphone open whenever the camera is open. It
  stops on pause, camera close, device-report runs and during recording's audio
  ownership.
- The phone's "back" built-in mic can be silent or very quiet on some devices;
  the UI reports SILENT INPUT rather than hiding it, but a future probe could
  rank inputs by measured signal.
- No headphone playback monitoring, limiter, loudness history, 24-bit capture,
  per-input calibration or embedded audio metadata yet. These are backlog
  items.
- Bluetooth SCO remains a low-quality route; selecting it is allowed and
  surfaced as the same data rather than blocked.

## Alternatives considered

- **Keep the platform default mic only** — no way to use a USB-C or lavalier
  input, which is the point of a cinema app.
- **Use `MediaRecorder` audio source defaults / AGC** — convenient, but hides
  destructive processing and conflicts with the "clean source" policy.
- **Always run the recorder's audio engine and mute the encoder for pre-roll**
  — would hold the muxer's timing/track state before a take and complicate
  start/stop; a separate monitor is simpler and releases cleanly.
- **Push audio levels through `CameraTelemetry`** — mixes 10 Hz meter data with
  configuration/live camera readouts and rebuilds more UI.
- **A Flutter audio plugin** — moves input routing and meters outside the
  Kotlin core, against ADR 0003.
