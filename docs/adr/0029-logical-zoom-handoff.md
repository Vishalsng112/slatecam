# ADR 0029 — Logical-camera zoom handoff

- **Status:** Accepted for step 1 (discovery); controller and verdict pending
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
2. **Controller path (next step).** When the active camera has an eligible
   `logical_zoom` and the requested stop is inside its range, select that
   camera ID and drive `CONTROL_ZOOM_RATIO`; do not reopen the session. Lens
   chips, pinch and the zoom rail all resolve to the same ratio targets through
   `SmoothZoom`. `SwitchFramePump` remains for the front/back switch, macro and
   any `uncovered_ratios`.
3. **Active lens is read, not assumed.** Surface
   `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` from capture results in state so
   the lens badge reflects what the HAL actually chose during the handoff.
4. **The verdict gates the path.** A `logicalZoomHandoff` verification records
   a clip while sweeping across the discovered stops, then checks:
   - camera-to-camera timestamp/PTS continuity (no frame gap),
   - exposure and white-balance jump size at the handoff,
   - the requested ratio and the resulting active physical ID agree.
   Only a pass enables the zoom path for that logical camera. A device that
   advertises the range but jumps, freezes or rebalances 3A stays on the
   session-switch path. This is the probe-first rule applied to switching.
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
  and nowhere through camera 5.
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
