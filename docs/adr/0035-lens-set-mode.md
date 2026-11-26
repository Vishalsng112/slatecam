# ADR 0035 — Lens set mode

- **Status:** Accepted
- **Date:** 2026-11-26
- **Phase:** 5.5 (shoot profiles)
- **Supersedes naming in:** ADR 0007 (Movie/Vlog)
- **Depends on:** ADR 0006 (aspect), ADR 0033 (recording mode model)

## Context

"Movie/Vlog" described both a shooting style and a technical behaviour: one
recording mode shared by several cameras. The operator found the name unclear,
and the lens-selection rules lived in the bridge while the common-mode rule
lived in the recording catalog.

The feature is really a **set of lenses** recorded with one mode derived from
their shared capabilities.

## Decision

1. **Name it Lens set.** The two modes are `Single lens` and `Lens set`.
   Wire values are `single` and `set`; the old `movie` value is read as `set`
   for existing selections.
2. **Core owns the rules.** `LensSetMode` (single/set, minimum two lenses),
   `LensSet` (selection, toggle and deselection validity) and `LensSetModes`
   (common modes via `RecordingModeCatalog.commonModes`) live in
   `:core:camera`.
3. **A set needs at least two lenses.** Switching to Lens set with fewer is
   rejected. Deselecting the last-but-one lens in set mode is rejected instead
   of silently falling back; the error is shown and the selection stays valid.
4. **Selection drives recording mode.** While in Lens set, changing the
   selected lenses clears the selected mode and re-resolves it from the new
   common capabilities. The Recording mode card therefore always shows modes
   every selected lens can record.
5. **One exposed interface.** Pigeon carries `lensSetMode` and
   `lensSetLenses`; the host API exposes `setLensSetMode` and `setLensInSet`.
   The UI card is `Lens set & profiles` with an explicit mode chip row.
6. **Profiles remain the persistence layer.** Shoot profiles store lens
   selections; the UI keeps the existing save/rename/delete actions.

## Consequences

- Default remains Single lens. The header reads `SINGLE LENS` or `LENS SET`;
  the console circles are `S` (single) and `L` (set).
- Selecting two lenses and switching to Lens set immediately narrows the
  Recording mode card to their common modes.
- With only two lenses selected, removing either one is refused with
  “lens set needs at least two selected lenses”.
- Existing persisted `movie` selections load as `set`.

## Alternatives considered

- **Keep Movie/Vlog** — rejected: it names a style, not the shared-mode rule.
- **Name it Multi-cam** — rejected: it implies simultaneous recording, while
  the app records one file and switches lenses.
- **Silently fall back to single when the set becomes short** — rejected: it
  hides that the operator's set is no longer valid.
