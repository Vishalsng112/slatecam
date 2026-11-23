# SlateCam UI design

**Status:** implementation target for the app-UI phase
**Scope:** landscape camera app for phones and tablets
**Source of truth:** Kotlin owns camera state; Flutter renders snapshots and sends commands

This document is the complete UI specification. It lists every current control,
groups the controls by task, reserves space for future options, and fixes the
final layout before the Flutter implementation changes.

## 1. Design goals

1. **Preview first.** The image is the product. Controls float over or beside it,
   never push it below a permanent stack of form-like widgets.
2. **One surface, no tabs.** Every control lives on a single Control Console.
   The record button, lens stepper, mode stepper and Single/Movie toggle are
   fixed at the top; the rest are cards in two columns. Nothing requires a tab
   switch or a dropdown to reach a primary action.
3. **No false controls.** A feature that the probe did not prove is either
   absent or shown read-only with the reason. Future options are documented here
   and only appear in the app when their native path exists.
4. **Cinema language, phone ergonomics.** Exposure is shown in stops, shutter
   time and shutter angle. Buttons remain at least 44 logical pixels so the
   layout behaves like a camera, not a settings page.
5. **Monitoring never reaches the recording.** Zebra, false colour, guides,
   peaking, histogram and waveform are preview UI/shader overlays only.
6. **One hand on the shutter.** Record, mode, ISO, shutter, focus and white
   balance are reachable from the bottom dock.

## 2. Information architecture

The UI is grouped by what the operator is trying to do, not by which native
module owns the data.

| Group | Purpose | Primary location |
|---|---|---|
| Status | What the camera is doing right now | Top HUD |
| Record & lens | Record, lens, mode, shoot mode, shoot set | Fixed console header |
| Exposure | P/S/M, ISO, shutter time/angle, auto exposure | Console card |
| Focus | AF, manual focus, focus speed | Console card |
| Colour | Profile, LUT, white balance, calibration | Console card |
| Monitoring | Guides, zebra, false colour, peaking, histogram, waveform | Left rail + console card |
| Capture | Edge, noise reduction, antibanding, output format | Console card |
| Device | Device database, quirks, calibration state, reports | Console card |
| Future AI | Subject tracking, focus assist, local assistant | Reserved AI tab |
| Future media | Playback/library, export, clip management | Reserved deck tab / separate screen |
| Settings | Theme, help, diagnostics, privacy | Reserved deck tab |

## 3. Final layout

Landscape only. The app keeps the `sensorLandscape` manifest behaviour.

```text
┌──────────────────────────────────────────────────────────────┬─────────────────────────┐
│ SLATECAM  cam 0 · db v1    P  1/50  ISO 400  WB auto          │  CONTROL DECK           │
│                                     REC 00:12  1080p · 30p    │  [Exposure][Focus]...   │
│                                                              │                         │
│  ┌──┐                                                        │  EXPOSURE               │
│  │G │                                                        │  P  |  S  |  M          │
│  │Z │                LIVE PREVIEW                            │                         │
│  │F │                                                        │  ISO      400           │
│  │P │                                                        │  ─────●──────           │
│  │H │                                                        │  Shutter  20.00 ms      │
│  │W │                                                        │  ───────●────           │
│  └──┘                                                        │  Angle    216°          │
│                                                              │  [AE auto]              │
│  ┌────────────┐                                              │                         │
│  │ HISTOGRAM  │                                              │  Kotlin owns state      │
│  └────────────┘                                              │                         │
│                                                              │                         │
│ ┌──────────────────────────────────────────────────────────┐ │                         │
│ │ ●  P | S | M   ISO 400   1/50   5600K  0.19D    [Deck] │ │                         │
│ └──────────────────────────────────────────────────────────┘ │                         │
└──────────────────────────────────────────────────────────────┴─────────────────────────┘
```

### Regions

1. **Top HUD** — brand, camera/device identity, exposure summary, recording
   timer, fixed output summary, errors. Translucent, auto-readable over preview.
2. **Live preview** — a 16:9 frame with the camera stream rotated to the
   display orientation and centre-cropped inside it. The frame is never
   stretched to fill an arbitrary window shape; black surround is preferred to
   a distorted image.
3. **Left monitor rail** — fast toggles: guides, zebra, false colour, peaking,
   histogram, waveform. Selected state is visibly lit.
4. **Monitor cards** — optional histogram and waveform cards above the bottom
   dock, bottom-left.
5. **Bottom dock** — record button, P/S/M selector, quick readout chips (ISO,
   shutter, white balance, focus), and Control Deck toggle.
6. **Control Console** — a docked, collapsible right panel with no tabs. A fixed
   header holds RECORD, a previous/next camera stepper, a previous/next mode
   stepper and the Single/Movie toggle. Below it, two columns of always-visible
   cards group the remaining controls. The header never scrolls away, so record,
   lens and mode are always one tap from any scroll position. On narrow
   landscape phones the console can be closed to give the preview the whole
   width.
7. **Overlay banners** — camera errors, recording errors, probe mode and saved
   clip messages. These sit above the dock and never block the record button.

### Responsive behaviour

| Width | Behaviour |
|---|---|
| `>= 1400 lp` | Console starts open at 760 lp, two columns; preview keeps the rest |
| `1000–1399 lp` | Console starts open at 600 lp, two columns |
| `760–999 lp` | Console starts open at 460 lp, two columns |
| `< 760 lp` | Console starts closed; opens over the preview at `min(360, width * 0.6)` |

On short landscape screens the six monitor toggles collapse into a compact
two-column rail so they never exceed the available height.

The deck can always be closed with the chevron. The record button never moves.

### Console header (fixed)

| Element | Behaviour | Why it is fixed |
|---|---|---|
| RECORD / STOP | `startRecording` / `stopRecording` | Never scroll away from the shutter |
| Camera ‹ › | Cycles exposed cameras | One tap per lens; no dropdown menu |
| Mode ‹ › | Cycles enabled recording modes | One tap per mode; disabled modes are skipped |
| Single / Movie | `setShootMode` | Common-mode validation stays visible |
| Status line | shoot mode, size, fps, bit depth | Read-only context for the current mode |

Every fixed element has a stable semantics label (`start recording`,
`camera: …`, `recording mode: …`) so UI automation can target it by role or
position without opening a menu.

## 4. Screen states

### 4.1 Launch / no camera

- Black field with `SLATECAM` wordmark and tagline.
- Primary `Open camera` button.
- Permission or bridge error message.
- Probe-mode message: preview unavailable while the prober owns the camera.

### 4.2 Live camera

- Top HUD, preview, left rail, bottom dock and optional deck as above.
- Recording state replaces the record glyph with a stop square, turns the timer
  red, and keeps the deck usable.
- Saved-clip path and gyro sample count appear as a temporary status card, not a
  permanent paragraph.

### 4.3 Probe running

- Preview closes, top HUD shows `PROBING CAMERA` with a spinner.
- Deck stays available but capture controls are disabled.
- On completion the preview reopens and the report path appears in Device.

### 4.4 Future / gated states

- Probe does not support a feature: hide the control, or show a read-only row
  with `unavailable · reason` from probe data.
- Feature exists in code but is gated (today: HLG): show `10-bit HLG · gated` in
  Capture, not an enabled toggle.
- AI not installed: AI tab not shown until `:core:ai` reports capabilities.

## 5. Element inventory

### 5.1 Status HUD

| Element | Current source | Status |
|---|---|---|
| `SLATECAM` wordmark | static | Current |
| Camera ID | `CameraState.cameraId` | Current |
| Device key | `CameraState.deviceKey` | Current |
| Database version | `CameraState.deviceDatabaseVersion` | Current |
| Exposure mode | `CameraState.exposureMode` | Current |
| ISO / shutter / WB snapshot | camera state | Current |
| Recording timer | `recordingSeconds` | Current |
| Preview / frame rate | `previewWidth`, `previewHeight`, `frameRate` | Current |
| Codec | fixed HEVC in the recording path | Current |
| Thermal status | native thermal listener only stops recording; not in state | Future |
| Battery level | not exposed | Future |
| Free storage | not exposed | Future |
| Audio meters | not exposed | Future |
| GPS/timecode | not implemented | Future |

### 5.2 Bottom dock

| Element | Behaviour | Status |
|---|---|---|
| Record / stop | `startRecording` / `stopRecording` | Current |
| P / S / M | `setExposureMode` | Current |
| ISO chip | opens the console | Current |
| Shutter chip | opens the console | Current |
| WB chip | opens the console | Current |
| Focus chip | opens the console | Current |
| Control Deck toggle | opens/closes the deck | Current |
| Photo shutter | not in native API | Future |
| Zoom / lens selector | lens chips + continuous zoom, from the probe report | **Current** (`ZoomPlugin`) |
| Audio monitor mute | no audio monitor path | Future |

### 5.3 Exposure card

| Element | Native call | Status |
|---|---|---|
| P/S/M segmented control | `setExposureMode` | Current |
| ISO slider | `setIso` (manual M only) | Current |
| Shutter time slider | `setShutterNs` (S/M) | Current |
| Shutter angle presets | `setShutterAngle` | Current |
| AE auto | `setAutoExposure` | Current |
| Auto-ISO status | `AutoIsoController` state via ISO + S mode | Current |
| Exposure compensation / EV wheel | not in the Pigeon contract | Future |
| Pro AE | own exposure loop | Future |
| AE response speed (Slow/Normal/Fast) | EV/sec controller | Future |
| Zebra threshold for exposure | `setZebra` only takes on/off | Future |
| Metering mode (matrix/centre/spot) | probe reports regions, no UI yet | Future |

### 5.4 Focus card

| Element | Native call | Status |
|---|---|---|
| AF auto | `setAutoFocus` | Current |
| Manual focus slider | `setFocusDiopters` | Current |
| Focus shift speed | `setFocusShiftSpeed` | Current |
| Focus distance readout | `focusDiopters` / `focusMax` | Current |
| Tap to focus | needs metering region API | Future |
| AF lock / AE lock buttons | probe has lock flags, not wired | Future |
| Hybrid CDAF | hill-climb focus engine | Future |
| Focus peaking controls | Monitoring card | Current |

### 5.5 Colour card

| Element | Native call | Status |
|---|---|---|
| Profile dropdown | `setProfile` | Current |
| Reload LUTs | `reloadLuts` | Current |
| White balance mode | `setWhiteBalanceMode` | Current |
| Input calibration status | device database / InputProfile | Current (display only) |
| LUT SAF picker | requires plugin/native picker | Future |
| Profile editor UI | profile persistence | Future |
| Creative vignette | shader effect | Future |
| Colour-space menu (SDR/HLG) | gated by probe | Future |
| Transfer/gamma selection | needs 10-bit path | Future |
| ColorChecker capture workflow | `ColorCalibration` solver; capture tooling | Solver current, workflow Future |

### 5.6 Monitoring card and left rail

| Element | Native call | Status |
|---|---|---|
| Framing guides | Dart overlay | Current |
| Zebra | `setZebra` | Current |
| False colour | `setFalseColor` | Current |
| Focus peaking on/off | `setPeaking` | Current |
| Peaking sensitivity | `setPeaking` | Current |
| Peaking strength | `setPeaking` | Current |
| Histogram visibility | Dart overlay from state bins | Current |
| Waveform visibility | Dart overlay from state bins | Current |
| Guide types (thirds, centre, safe area, crosshair) | not wired | Future |
| Level / horizon | not wired | Future |
| Peaking temporal decay | shader ping-pong buffer | Future |
| False-colour legend | UI only | Future |
| Monitor size/opacity settings | UI only | Future |

### 5.7 Capture tab

| Element | Native call | Status |
|---|---|---|
| Edge mode | `setEdgeMode` | Current |
| Noise reduction | `setNoiseReduction` | Current |
| Antibanding | `setAntibanding` | Current |
| Output summary | preview size + frame rate from state | Current |
| HLG gate status | current bit depth + probe reason | Current |
| Shoot mode | Single vs Movie/Vlog common mode | **Current** |
| Recording mode | per-camera size/fps/bit-depth incl. 4K/1080p/720p and 16:9/4:3, probe-gated | **Current** |
| Resolution / frame-rate selector | replaced by the recording-mode catalog | **Current** |
| Codec / bitrate selector | fixed HEVC, bitrate scales with mode | Future |
| Audio source and level | native selection exists, no UI/state | Future |
| Stabilisation / OIS | probe reports modes, no state | Future |
| RAW / DNG burst | Camera2 still capture not implemented | Future |
| Photo mode | not implemented | Future |

### 5.8 Device tab

| Element | Native source | Status |
|---|---|---|
| Device key + camera | `deviceKey`, `cameraId` | Current |
| Database version | `deviceDatabaseVersion` | Current |
| Calibration status/source | `deviceCalibrationActive`, `deviceCalibrationSource` | Current |
| Applied quirks | `deviceQuirks` | Current |
| Opt-in reports | `setDeviceReportOptIn` | Current |
| Run device report | `runDeviceReport` | Current |
| Reload database | `reloadDeviceDatabase` | Current |
| Last report path / error | `deviceReportPath`, `deviceReportError` | Current |
| Report upload / share | no network client | Future |
| Quirk editor | database is reviewed JSON | Future |

### 5.9 Future AI tab

Not shown while `:core:ai` reports unavailable.

| Element | Contract | Status |
|---|---|---|
| AI master opt-in | `AiConfig.enabled` | Future |
| Frame-rate cap / thermal budget | `AiFrameGate` | Future |
| Subject tracking overlay | `SubjectTrack` bounds | Future |
| Focus assist suggestion | `FocusSuggestion` | Future |
| Tracking target selection | AF region / UI tap | Future |
| Local assistant | separate text interface | Future |
| Model download / status | not designed | Future |

### 5.10 Future media and settings

| Element | Notes |
|---|---|
| Clip library / playback | feature:playback is declared, no source |
| Export / share sheet | needs Android share intent |
| Storage-free warning | needs storage state |
| Theme/accent choice | optional later |
| Diagnostics / about | can consume probe and device DB |
| Help / gestures | after interaction model stabilises |

## 6. Interaction rules

- Tapping a bottom readout opens the corresponding deck tab.
- Tapping the deck toggle opens/closes the deck; the record button never moves.
- Sliders preview their value immediately and send the native command on release.
  Native state remains authoritative.
- Switching to S disables ISO editing but leaves shutter editing enabled.
- Switching to P disables manual ISO and shutter editing.
- Switching to M enables both.
- Native toggles (zebra, false colour, peaking) show the state returned by Kotlin,
  not a local guess.
- Local toggles (guides, histogram visibility, waveform visibility) only affect
  the Flutter overlay and are deliberately not part of the camera contract.
- When recording, the deck remains usable except for actions that would stop the
  camera (probe/reload that reopens the device).
- Errors are shown as a dismissible banner and remain in the deck where relevant.

## 7. Visual system

| Token | Value |
|---|---|
| Background | `#08090B` |
| Surface | `#111317` |
| Raised surface | `#171A1F` |
| Border | `#2A2E36` |
| Accent | `#E8B44A` |
| Accent dark | `#8A6A26` |
| Text | `#F5F2EA` |
| Muted text | `#9AA3AF` |
| Recording / error | `#E5484D` |
| Success / calibration | `#4CC38A` |
| Info / focus | `#6EA8FE` |

- Corner radius: 14 for cards, 12 for controls, pill for chips.
- Spacing: 4/8/12/16/24.
- Numeric readouts use tabular figures where available.
- Minimum touch target: 44 logical pixels.
- Preview overlays use a slight dark gradient at top and bottom for legibility.
- Icons: Material Symbols equivalents (`record`, `tune`, `exposure`, `center_focus`, `palette`, `monitor`, `camera`, `memory`, `psychology`).

## 8. Implementation decision

The final implementation is:

- a full-bleed preview with top HUD and bottom transport dock;
- a quick monitor rail on the left;
- a collapsible, tabbed Control Deck on the right with six tabs:
  **Exposure, Focus, Colour, Monitor, Capture, Device**;
- optional overlay cards for histogram and waveform;
- no AI, playback or future-control tab until the native contract exists;
- future options listed in this document and reserved structurally, not exposed
  as dead buttons.

The next section of the implementation is a rewrite of the Flutter widget tree
to this layout, keeping `CameraState` and `SlateCamHostApi` as the only bridge.
