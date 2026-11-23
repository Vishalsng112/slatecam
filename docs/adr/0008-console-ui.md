# ADR 0008 — Single-surface Control Console

- **Status:** Accepted
- **Date:** 2026-11-24
- **Phase:** 5.5
- **Depends on:** ADR 0007 (shoot profiles)

## Context

The previous Control Deck grouped controls behind six tabs and used dropdowns
for the camera, recording mode, shoot mode and profiles. Reaching a lens or mode
took a tab switch plus a menu. That was slow with a camera in hand and made
scripted device verification fragile: a tap sequence depended on menus that
opened at different positions and on a preview that continuously animates.

## Decision

1. **No tabs.** The deck becomes one scrollable console. Cards are grouped by
   task and laid out in two columns when the rail is at least 520 logical pixels
   wide.
2. **Fixed quick bar.** A circular RECORD/STOP button, circular S/M
   Single/Movie buttons and one circular lens badge per camera (`B0`, `F1`,
   `B2`, …) sit in a non-scrolling header, with a compact recording-mode
   stepper on the status row. Record, lens and shoot mode never require a menu
   or a scroll. The visible circle is 34 lp inside a 44 lp tap target.
3. **Direct-choice controls.** Camera, recording mode, shoot mode, exposure
   mode, shutter angle, profile, white balance, processing modes and shoot
   profiles are compact chips or segmented buttons. Monitoring is a wrap of
   icon chips instead of switch rows. Dropdowns remain only for long free-form
   lists that do not fit as chips.
4. **Recording mode follows the camera convention.** Resolution, frame rate and
   bit depth are separate rows (4K/1080p/4:3/720p → 24/25/30 → 10-bit/8-bit).
   The UI selects the closest enabled combination instead of listing every
   Cartesian product as its own chip.
5. **Stable semantics.** The fixed controls carry explicit labels
   (`start recording`, `camera: …`, `recording mode: …`) so automation can find
   them without relying on visual text.
6. **Console, not settings page.** Sliders keep their labels and values inline;
   status stays in the header and footer. The preview remains the largest
   element at every breakpoint.

## Consequences

- Lens and mode changes are two fixed taps from any scroll position; recording
  and switching can be scripted without opening a menu.
- Mode chips make the disabled reasons visible instead of hiding them in a
  closed dropdown.
- Three columns at >=720 lp and two columns at >=520 lp reduce scrolling;
  chips use 10 lp labels and shrink-wrapped tap targets to fit more per row.
- The recording-mode card is three short rows instead of ~20 chips, so mode,
  exposure, shoot set and focus are visible together on a phone console.
- The console is wider (up to 760 lp) than the old deck, trading preview width
  for always-visible controls; it can still be collapsed.
- Dropdowns are still used where a chip row would be unbounded or unreadable.
