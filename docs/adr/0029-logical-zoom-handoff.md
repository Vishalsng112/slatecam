# ADR 0029 — Logical-camera zoom handoff

- **Status:** Accepted; discovery, controller and `logicalZoomHandoff`
  verdict implemented
- **Date:** 2026-11-25
- **Phase:** 5.5 (shoot profiles / Movie-Vlog)
- **Depends on:** ADR 0005 (colour pipeline), ADR 0023 (smooth zoom),
  ADR 0024 (telemetry)

## Context

Lens changes during recording currently reopen a Camera2 session and keep the
encoder alive with `SwitchFramePump`. That hides the session gap, but it is
still a session recreation with a short freeze/pause, and it duplicates control
of exposure and white balance across sessions.

Some devices expose a **logical multi-camera** with an ultra-wide, normal and
telephoto physical camera behind one Camera2 ID. When that logical camera
advertises a `CONTROL_ZOOM_RATIO_RANGE` that reaches below 1.0, the HAL can hand
off between physical lenses inside one session. That is the iPhone-style
behaviour: no session teardown, no frame pump, no clip gap, one 3A state.

Camera2 does not expose a list of "lens stops". The prober must derive them
from physical IDs, the zoom range and the already-computed relative intrinsic
ratios. It must also keep the existing session-switch path for cameras the
logical session cannot reach.

## Decision

1. **Probe discovery is the interface.** Schema 1.12.0 adds
   `cameras[].logical_zoom`:
   - `handoff_eligible`: logical multi-camera, at least two physical IDs, a
     valid zoom range that starts below 1.0, and at least two reachable stops
     with one below and one at/above 1.0.
   - `ratio_range`: the logical camera's `CONTROL_ZOOM_RATIO_RANGE`.
   - `stops[]`: `{physical_id, role, ratio, basis, role_confidence}`, sorted by
     ratio. Ratios come from each physical camera's intrinsic ratio; a
     `1.0` logical-default stop is synthesised when metadata is missing.
   - `uncovered_ratios[]`: physical lenses outside the logical range. These are
     exactly the lenses that still need the session-switch fallback.
   - `reason`: why the zoom path is not a candidate.
   `LogicalZoom` is a pure Kotlin evaluator with JVM tests; `CameraSource` only
   feeds it characteristics. No UI, Pigeon, or controller code is added in
   step 1.
2. **Controller path (implemented for exposed logical cameras).** A logical
   stop selects the logical camera ID when it is not active, then drives
   `CONTROL_ZOOM_RATIO`; it never reopens the session for a stop inside the
   range. Lens chips call `selectLensStop`, and pinch/slider use the same ratio
   targets through `SmoothZoom`. The first open prefers the logical camera when
   it covers the current lens, so a take starts on the handoff session.
   `SwitchFramePump` remains for the front/back switch, macro and any
   `uncovered_ratios`.
   **Hidden logical cameras are excluded from this path.** They may open once
   but are removed from the vendor camera list after another session starts:
   on the POCO, opening macro removes logical camera `4` until process restart,
   so returning to it cannot be made seamless. Hidden macro `3` also invalidates
   the exposed camera list for the process. Exposed logical cameras are safe to
   switch away from and back; hidden candidates stay discovery-only until a
   verdict that includes the fallback round trip exists.
3. **Active lens is read, not assumed.** Surface
   `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` from capture results in state so
   the lens badge reflects what the HAL actually chose during the handoff. The
   logical stop chip is selected by the requested ratio; the physical chip dot
   shows the sensor the HAL reports.
4. **The verdict gates the path.** A `logicalZoomHandoff` verification captures
   a short camera stream while sweeping across the discovered stops, then
   checks:
   - camera frame timestamp continuity (no gap beyond 3× median / 100 ms),
   - exposure and white-balance jump size at the handoff,
   - at least two active physical IDs are reported.
   Only a pass enables the zoom path for that logical camera. A device that
   advertises the range but jumps, freezes or rebalances 3A stays on the
   session-switch path. The check runs last because opening a hidden logical
   camera can disturb other probe sessions.
5. **No device branches.** Eligibility comes from the probe report and the
   live camera characteristics. A model name or fixed camera ID is never used.
6. **Dual-session switch stays design-only.** A true concurrent-session switch
   is a separate ADR; no concurrent-session code is introduced by this work.

## Consequences

- Step 1 is data only: reports gain stops/reasons, and the existing controller
  is unchanged. Discovery is visible on a device before any recording behavior
  changes.
- POCO X6 Pro: logical camera `4` (physical `0`/`2`) reports an eligible range
  0.6–10 with stops at 0.621 and 1.0; logical camera `5` is rejected because
  its range starts at 1.0. The ultra-wide is reachable through zoom on camera 4
  and nowhere through camera 5. Camera `4` is vendor-hidden, but its recorded
  verdict passes and front→back returns to it, so the build enables it. Hidden
  macro `3` is excluded because it invalidates the vendor camera list.
- Exposed logical cameras can switch to macro/front and return. Hidden logical
  `4` can do the same once hidden macro is excluded; other fallback lenses
  remain covered by the session switch.
- The zoom path only covers stops the logical range reaches. A 5× tele outside
  a 4× range remains a session switch; the fallback must stay healthy.
- The 0.6× stop is the HAL's ratio, not a marketing label. UI labels should
  follow the report or the physical lens's 35 mm equivalent, not a fixed
  `0.5x` string.
- A logical-camera session may still be torn down when switching to the front
  camera or a camera with a different shooting mode; the recorder must not be
  torn down, and the existing frame pump continues to cover those cases until
  the dual-session design exists.

## Alternatives considered

- **Always use session switching** — current behavior, but leaves the visible
  gap and the extra 3A state.
- **Always use `CONTROL_ZOOM_RATIO` when available** — ignores HALs that
  advertise the range but exhibit gaps or exposure jumps; needs the verdict.
- **Per-device allowlist** — rejected by AGENTS.md; the capability and
  evidence belong in the probe and device database, not a model branch.
- **Expose every physical ID in the UI** — the user chooses a scene framing,
  not a sensor; physical IDs are diagnostics.
