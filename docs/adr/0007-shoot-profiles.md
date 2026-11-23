# ADR 0007 — Per-camera shoot profiles and movie/vlog switching

- **Status:** Accepted
- **Date:** 2026-11-23
- **Phase:** 5.5 (inserted between Phase 5 and Phase 6)
- **Depends on:** ADR 0004 (recording), ADR 0005 (colour), ADR 0006 (orientation)

## Context

Android camera modules do not share capabilities. On one device the main sensor
may support 8K, a periscope 4K, an ultra-wide 1080p, and the front camera only
30 fps 8-bit. Sizes, frame rates, autofocus, 10-bit, HLG and aspect ratios can
all differ per camera. A configuration that works on one lens can fail to
configure on another, and switching lenses mid-recording previously stopped the
clip or risked a `session configuration failed` error.

The app also needs a Movie/Vlog mode: one recording configuration, one file, and
lens switching while recording, with a colour pipeline that can be matched
across cameras.

## Decision

1. **Capabilities are read per camera, not per app.**
   `CameraCapabilitiesReader` (`:core:camera`) reads sizes, exact AE frame-rate
   ranges, hardware level, autofocus modes, 10-bit claim and zoom range for a
   specific camera ID. Hidden/vendor lenses are included when the probe marked
   them openable. There is no "main camera" assumption.
2. **Modes are validated, not assumed.**
   `RecordingModeCatalog` combines live capabilities with probe verification.
   A mode exists only when the camera exposes the size, can frame-lock the rate,
   and — for 10-bit — the probe verified that exact size and rate for that camera.
   Invalid modes are shown disabled with a plain reason.
3. **Single mode and movie mode are different products.**
   - Single camera: the full mode surface of the active lens.
   - Movie/Vlog: the strict **intersection** of modes across the shoot set.
     The shoot set is every probe-visible, openable, non-macro lens (back and
     front), deduplicated by facing/role/focal length.
   A lens switch must not change size, rate or bit depth.
4. **Switch rules are explicit.**
   - Switching while recording is rejected in single mode instead of silently
     ending the clip.
   - In movie mode a switch is accepted only when the target lens supports the
     active common mode. The recorder, encoder surface, audio and gyro are never
     torn down; only the camera session is recreated.
   - If a mode change is needed before switching while idle, the mode and camera
     are applied in one atomic `configure(..., cameraIdOverride)` call, not two
     reopens.
5. **Autofocus is a capability, not an assumption.**
   `CameraController` reads `CONTROL_AF_AVAILABLE_MODES`; a lens without AF is
   put into manual focus and the UI disables the AF action. Movie mode does not
   promise AF parity between lenses.
6. **Colour matching uses the existing per-camera input profile.**
   On every lens change the bridge refreshes the device snapshot and applies the
   camera's calibrated 3×3 matrix/trims before the shared creative profile and
   LUT. Per-camera calibration data (device database) is the alignment mechanism;
   missing calibration means identity, not a guessed matrix.

## Consequences

- A configuration can never be sent to a lens that does not support it; the
  reported mode list is the contract.
- Movie mode is honestly limited by the weakest lens in the set. On the Pad 3
  the front camera's 10-bit is not probe-verified, so the common mode is 8-bit.
  A 10-bit movie mode requires per-lens and per-size verification.
- Switch-while-recording has a short visual gap while the new camera session is
  created; it does not stop the clip and does not crash.
- The shoot set and common-mode logic are data-driven; adding a lens, a 4:3
  preset or a per-lens verification automatically narrows or widens the list.
- Colour parity is only as good as the per-camera calibration data. Measuring
  and shipping matrices remains Phase 5.5 work.

## Alternatives considered

- **One global mode list per device** — rejected: this is the bug being fixed.
- **Stop/restart the recorder on every lens switch** — rejected: gaps, file
  splits and audio/gyro resets; not a movie mode.
- **Try the switch and fall back on failure** — rejected: it can kill the clip
  and hides capability errors. Validate first, then switch.
- **Per-brand quirks** — rejected: capability data and probe verdicts already
  describe the difference.
