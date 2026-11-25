# ADR 0031 — One focus interface and a CAF default

- **Status:** Accepted
- **Date:** 2026-11-26
- **Phase:** 5 (advanced capture)
- **Supersedes in part:** ADR 0010 (focus default), ADR 0012 (SAF default)
- **Depends on:** ADR 0010 (stabilisation and focus), ADR 0012 (software focus)

## Context

Focus logic had grown across the camera controller, the bridge and the UI:
`setFocusMode`, `setFocusDiopters`, `setFocusScenario` and a raw
`setFocusShiftSpeed` each mutated a different subset of focus state, and the
bridge forced SAF on every capable lens by default. That made the active mode
hard to predict: the same tap could either change the HAL request, implicitly
leave a mode, or switch to the software engine. The operator asked for one
place to switch modes, every HAL-supported mode plus the app's SAF engine, and
CAF (Camera2 `CONTROL_AF_MODE_CONTINUOUS_VIDEO`) as the default.

## Decision

1. **`FocusController` is the single interface.** It owns the mode catalog, the
   active and preferred modes, manual diopters, SAF presets, the software
   search and the AF/lens fields written into the capture request. The camera
   controller only feeds frames, results and taps to it.
2. **The catalog is every HAL mode the active lens reports plus `software`.**
   `CONTROL_AF_AVAILABLE_MODES` is mapped through one table; unknown values are
   dropped instead of surfacing a chip that would silently become `off`.
   Operator order is CAF, CAF-P, AF, MACRO, EDOF, MF, then SAF.
3. **CAF is the default.** `FocusMode.defaultFor` tries CAF, then CAF-P, AF,
   MACRO, EDOF, and finally MF. SAF is never selected implicitly. A lens
   without continuous AF falls back deterministically.
4. **One switch for every entry point.** `selectMode` is the only place that
   changes the mode. Manual focus (`selectManual`) selects MF and then sets the
   distance; a shooting scenario (`selectScenario`) selects SAF and applies its
   two presets. Re-selecting SAF resets the engine.
5. **The operator's choice persists.** The preferred mode is stored in
   `shoot-profile` prefs. A lens that cannot run it uses its own default while
   it is active; switching back to a capable lens restores the preference.
   There is no manufacturer/model branch.
6. **The raw diopter-speed command is removed.** Transition speed is a SAF
   preset dimension, not a separate focus mode; keeping a second ramp command
   made the interface ambiguous.

## Consequences

- A fresh install opens in CAF on every lens that advertises it. SAF and the
  scenarios are one explicit tap away on lenses with a real focus range.
- The UI keeps the same Pigeon commands (`setFocusMode`, `setFocusDiopters`,
  `setFocusScenario`, sensitivity and transition speed); they now funnel into
  `FocusController` instead of each owning focus state.
- Selecting a scenario on a fixed-focus lens does not change the lens request;
  the preference is remembered for the next capable lens.
- Focus verification: the repeating request reports `CONTROL_AF_MODE` matching
  the selected HAL mode, or `OFF` with `LENS_FOCUS_DISTANCE` for MF/SAF.
  Restarting the app restores the last explicit mode; with no saved mode it is
  CAF.

## Alternatives considered

- **Keep SAF as the default on lenses with a range** — rejected: the operator
  asked for CAF, and an implicit engine switch is what made mode prediction
  hard.
- **Expose scenarios as separate focus modes** — rejected: they are SAF
  presets, not distinct camera behaviours; duplicating them in the mode list
  would make the interface wider, not clearer.
- **Keep `setFocusShiftSpeed` as an automation back door** — removed because
  it was unused and gave two ways to set the same ramp.
