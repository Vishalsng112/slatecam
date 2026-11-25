# ADR 0030 — Dual-session lens switching

- **Status:** Accepted and implemented for exposed concurrent pairs
- **Date:** 2026-11-26
- **Phase:** 5.5 (Movie-Vlog)
- **Depends on:** ADR 0006 (single pixel path), ADR 0022 (presentation),
  ADR 0029 (logical zoom handoff)

## Context

[ADR 0029](0029-logical-zoom-handoff.md) makes lens switches seamless when a
logical multi-camera covers the target stop: one Camera2 session, one
`CONTROL_ZOOM_RATIO` stream, one GL texture. It is the preferred path.

It cannot cover every lens. Front↔back switches, macro or tele lenses outside
the logical range, and cameras whose mode differs from the active one still use
a session recreation. `SwitchFramePump` repeats the last frame while the new
session starts, which hides the encoder gap but not the camera pause: 3A
reconverges, the viewfinder freezes for a few frames, and the clip contains a
held frame rather than a live handoff.

A true dual-session switch would keep a second Camera2 session open and warm,
then switch the renderer's source at a frame boundary. That is the remaining
iPhone-style behavior. It is a large resource and synchronization change, so it
is specified now and implemented only after a real-device verdict proves it.

## Decision

### Scope

1. **Fallback only.** Use dual-session switching only when ADR 0029's
   `logicalZoomHandoff` is unavailable or does not cover the target lens. The
   implementation allows it during a take when the pair and mode pass the
   verdict; logical zoom remains preferred for covered stops.
2. **Per-pair, per-mode verdict.** A `dualSessionSwitch` probe check must pass
   for the exact camera pair and recording mode. Concurrency claims and the
   existing `concurrent_camera_session` check are not sufficient: they prove two
   sessions can configure, not that a switch keeps the clip continuous.
3. **Exposed cameras first.** A vendor-hidden camera may open once and vanish
   after another session starts (ADR 0029). A hidden pair therefore needs its
   own stability evidence; until then only exposed IDs participate.

### Pipeline

4. **Two camera inputs, one pixel path.** Each session renders into its own OES
   texture; the GL renderer selects exactly one active texture per draw. The
   preview surface, encoder surface and analysis FBO are always fed by that one
   active texture. There is no second encoder, muxer, analysis FBO or
   camera-to-encoder path.
5. **One recorder timeline.** `ClipRecorder` stays unchanged. The encoder keeps
   receiving frames and its PTS comes from the active camera timestamp. A switch
   may repeat at most one frame, never create a timestamp jump.
6. **Standby is warm, not displayed.** The second session runs a repeating
   request at the same size/fps/bit depth, with its own 3A converged and zoom
   ratio pre-positioned. It is not composited and does not contribute analysis
   samples until it becomes active.

### Handoff sequence

1. Open and configure the standby session before the user reaches the switch
   (on record start for the first standby, then after each completed switch).
2. Copy controls from active to standby: AE/AWB mode, exposure compensation,
   manual ISO/shutter/WB, focus mode/distance, zoom ratio, stabilization and
   shading settings.
3. Wait for at least two standby frames and a stable 3A sample.
4. Arm the switch, then select the standby texture on the next encoder draw
   boundary. Stop analysis from the outgoing session at that boundary.
5. Keep the outgoing session alive for one frame interval, then close it or
   reuse it as the next standby. Never close the recorder or muxer.
6. Record a state transition (`active → standby`) for telemetry and the verdict.

### Failure and resource policy

- If the standby cannot open, configure, or produce a stable frame, keep the
  active session and the recorder running; disable dual-session for the pair and
  fall back to the frame pump. Never tear down a take for a failed standby.
- At most two camera devices and two sessions. Release the standby while idle or
  when the app backgrounds. Thermal status and sustained-performance data from
  the probe inform whether a pair is offered in Movie mode.
- Audio, gyro and the timecode do not change; they belong to the recorder
  timeline, not to a camera session.
- A stream mismatch (size/fps/bit depth/HLG vs SDR) is not repairable by this
  path; that lens/mode requires a session switch with the frame pump.

### Probe verdict

7. Add `dualSessionSwitch_c<A>_c<B>` to the probe:
   - open both cameras concurrently,
   - configure both at the requested mode,
   - run both streams concurrently and join a sample boundary A→B,
   - require no timestamp gap beyond the normal frame interval and both stream
     IDs observed,
   - skip exposure/WB jump checks across two different sensors; those remain a
     same-session logical-zoom check.
   The probe check currently exercises a common preview-size stream, while the
   runtime additionally requires both cameras to support the **active recording
   mode** before it starts a standby. A pass on one pair never promotes another.

### Ownership

- `:core:camera`: session pair, active/standby ownership, control copy, handoff
  state machine.
- `:core:gpu`: two texture inputs, active-source selection, one preview/encoder/
  analysis output set.
- `:core:codec`: unchanged; one recorder timeline.
- `:bridge`: state/telemetry and verdict lookup; no second recorder.
- Flutter: existing lens chips only; no second preview or split screen.

## Consequences

- True no-freeze switching for pairs the HAL can genuinely run concurrently.
- Resource cost: two camera streams, two 3A loops, more power and thermal load.
  The mode catalog and thermal guard must expose/limit it.
- Back/back concurrency is rare on phones; front/back and some logical-adjacent
  pairs are more likely. The verdict, not a model name, decides.
- Dual-session and logical zoom overlap; logical zoom remains preferred because
  it needs one session and preserves one 3A state.
- Pad 3 passes the verdict and switches front↔back mid-take with no recorder
  teardown; max PTS delta was 44.6 ms across two switches at 1440×1080 10-bit.
  The frame pump remains the fallback for pairs without a verdict or a matching
  mode.

## Alternatives considered

- **Keep session recreation + `SwitchFramePump`** — current fallback; safe but
  shows a held frame and re-converges 3A.
- **Always prefer dual-session** — doubles resource use and breaks on devices
  that advertise concurrency but fail at recording mode.
- **Two encoders/muxers and splice** — avoids texture switching but creates two
  timelines, audio alignment and file-joining problems; rejected.
- **Vendor private multi-camera APIs** — non-public and device-specific; against
  AGENTS.md.
- **Split-screen preview of both sessions** — not requested and doubles the
  preview cost; the standby is a source, not a second viewfinder.
