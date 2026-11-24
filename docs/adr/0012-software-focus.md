# ADR 0012 — Software focus engine and shooting scenarios

- **Status:** Accepted
- **Date:** 2026-11-24
- **Phase:** 5 (advanced capture)
- **Depends on:** ADR 0010 (focus modes), ADR 0011 (metering)

## Context

Camera2 continuous AF does not expose subject-shift sensitivity or transition
speed. Those are cinema-camera behaviours: how readily AF abandons one subject
for another, and how quickly the lens racks to the new distance. The operator
chose the software-focus route: full control, no face or eye tracking.

The app already has a diopter ramp (`LENS_FOCUS_DISTANCE`), a focus ring slider
and a GPU analysis frame. Contrast AF needs one more input: a sharpness score
that peaks when the lens is focused.

## Decision

1. **A `software` focus mode (`SAF`) is offered when the lens reports a real
   focus range.** `LENS_INFO_MINIMUM_FOCUS_DISTANCE > 0` gates it; fixed-focus
   lenses hide SAF and the scenario controls. SAF uses `AF_MODE_OFF` and drives
   `LENS_FOCUS_DISTANCE` through the existing ramp.
2. **The GPU provides one sharpness score.** The analysis pass is 320×180 at
   15 Hz (every second frame at 30 fps). It computes Laplacian energy over the
   central 50% of the upright frame and adds it to `FrameAnalysis`. No second
   camera or encoder path is added.
3. **Core owns the search.** `SoftwareFocusEngine` is a pure Kotlin hill-climb:
   - starts at the first reported lens position;
   - steps toward the middle of the range, continues while the score improves,
     reverses and halves the step when it falls, and locks at the best position;
   - has a contrast floor so a flat wall is not hunted;
   - after locking, shift sensitivity sets both the score drop that counts and
     how long it must persist before a new search starts.
4. **Two normalized controls.**
   - **Shift sensitivity** 0–1 maps to a 35%→5% score-drop trigger and a
     2000→500 ms hold.
   - **Transition speed** 0–1 maps to a 0.5–12 diopter/second ramp and a
     0.04–0.10 step fraction.
5. **Four shooting scenarios are presets, not hardware modes.**

   | Scenario | Shift sensitivity | Transition speed |
   |---|---|---|
   | Interview | 0.25 | 0.35 |
   | Vlogging | 0.80 | 0.75 |
   | Wedding | 0.50 | 0.50 |
   | Cinematic | 0.20 | 0.15 |

   Selecting a scenario switches to SAF and applies both values; the sliders can
   then be fine-tuned. Settings persist across restarts, and the SAF choice is
   restored if the lens still reports a focus range.
6. **Metering and focus do not fight.** P-mode AE compensation is throttled to
   5 Hz with a 0.10 EV dead band and at most one compensation step per update,
   because the HAL AE and the bias are in the same feedback path. The analysis
   rate remains 15 Hz for metering and focus.
7. **No face or eye tracking.** The focus ROI is the centre of the analysis
   frame. Face/eye tracking would be a separate ML module and is explicitly out
   of scope.

## Consequences

- Pad 3: SAF restored after restart, searched from 4.22 D to a locked 4.22 D
  with the peak score, and the lens report matched the commanded value. Vlogging
  locked in about 2.5 s; Cinematic used 0.20/0.15 and moved in 0.49 D steps.
- The engine is slower than HAL CAF and can settle in a local contrast peak.
  That is inherent to contrast-detect AF without phase detection.
- The focus score is computed on the graded analysis frame, consistent with the
  metering note in ADR 0011; a pre-look analysis pass would refine both.
- Fixed-focus lenses have no SAF/scenario UI. Manual focus (`MF`) and the
  camera's own CAF/CAF-P/AF modes remain available.
- P metering at 100% speed is stable after the 5 Hz/one-step throttle; 50% is
  the default.

## Alternatives considered

- **Tune HAL CAF** — impossible through Camera2; only AF regions and mode.
- **Full-range sweep every time** — reliable peak but too slow for Vlogging and
  not useful for Cinematic racks.
- **Face/eye tracking** — explicitly declined; would need ML and a different
  tracking design.
- **Use the histogram only** — no spatial sharpness information; the Laplacian
  grid is the minimum viable focus signal.
