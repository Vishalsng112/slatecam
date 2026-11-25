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
| Status | What the camera is doing right now | Top status strip |
| Record & lens | Record, lens, mode, shoot mode, shoot set | Fixed console header |
| Exposure | P/S/M, ISO, shutter time/angle, auto exposure | Bottom dock + console card |
| Focus | Continuous AF by default; manual controls deferred | Kotlin default |
| Colour | Profile, LUT, white balance, calibration | Console card |
| Monitoring | Guides, zebra, false colour, peaking, histogram, waveform | Console card (+ overlay cards) |
| Capture | Edge, noise reduction, antibanding, output format | Console card |
| Device | Device database, quirks, calibration state, reports | Console card |
| Future AI | Subject tracking, focus assist, local assistant | Reserved AI tab |
| Media library | Clip library, playback, share/delete, free space | Clips screen |
| Settings | Theme, help, diagnostics, privacy | Reserved deck tab |

## 3. Final layout

Landscape only. The app keeps the `sensorLandscape` manifest behaviour.

```text
┌──────────────────────────────────────────────────────────────┬─────────────────────────┐
│ 1440×1080 · 30p · HEVC · 10-bit    REC 00:12                 │  CONTROL DECK           │
│                                                              │  [Exposure][Focus]...   │
│                                                              │                         │
│                                                              │  EXPOSURE               │
│                                                              │  P  |  S  |  M          │
│                  LIVE PREVIEW                                │                         │
│                                                              │  ISO      400           │
│                                                              │  ─────●──────           │
│                                                              │  Shutter  20.00 ms      │
│                                                              │  ───────●────           │
│  ┌────────────┐                                              │  Angle    216°          │
│  │ HISTOGRAM  │  (only when enabled from the deck)           │  [AE auto]              │
│  └────────────┘                                              │                         │
│  [16mm][25mm]                                   ┃ 1.0×      │  Kotlin owns state      │
│  ●   P | S | M   ISO 400   1/50   5600K                 (›)  │                         │
└──────────────────────────────────────────────────────────────┴─────────────────────────┘
```

### Regions

1. **Top status strip** — compact output-format, recording-timer and error
   pills, plus the `VF Full / 1080 / 720` preview-quality pill at the
   top-right. No brand, camera identity or exposure summary: those live in the
   Control Deck so the preview stays clean.
2. **Live preview** — a 4:3/16:9 frame with the camera stream rotated to the
   display orientation and letterboxed inside it. The frame is never stretched
   to fill an arbitrary window shape; black surround is preferred to a distorted
   image.
3. **Left monitor rail** — removed from the viewfinder. Guides, zebra, false
   colour, peaking, histogram and waveform are toggled from the Control Deck's
   Monitoring card; selected overlays still render over the preview.
4. **Monitor cards** — histogram and waveform cards appear bottom-left while
   enabled from the Monitoring card.
5. **Bottom dock** — a flat floating row with no enclosing panel: record
   button, P/S/M selector, ISO/shutter/white-balance readout chips and the
   Control Deck toggle.
6. **Right zoom rail** — a single vertical strip pinned to the preview's right
   edge: maximum at the top, minimum at the bottom, ticks at lens-switch
   ratios, and the current ratio inside the draggable thumb. There is no
   background panel; labels use a shadow and the strip floats over the video.
   Tap or drag anywhere on the strip; the scale is logarithmic so wide-angle
   travel is not compressed. It clears the top-right VF pill and the bottom
   dock.
7. **Control Console** — a docked, collapsible right panel with no tabs. A fixed
   header holds a circular RECORD/STOP button, circular S/M shoot-mode buttons
   and one circular lens badge per camera (`B0`, `F1`, …), plus a compact mode
   stepper. Below it, three columns at >=720 lp (two at >=520 lp) of dense cards
   group the remaining controls. The header never scrolls away, so record,
   lens and mode are always one tap from any scroll position. On narrow
   landscape phones the console can be closed to give the preview the whole
   width.
8. **Overlay banners** — camera errors, recording errors, probe mode and saved
   clip messages. These sit above the dock and never block the record button.

### Responsive behaviour

| Width | Behaviour |
|---|---|
| `>= 1400 lp` | Console starts open at 760 lp, three columns; preview keeps the rest |
| `1000–1399 lp` | Console starts open at 600 lp, two columns |
| `760–999 lp` | Console starts open at 560 lp, two columns |
| `< 760 lp` | Console starts closed; opens over the preview at `min(360, width * 0.6)` |

On short landscape screens the six monitor toggles collapse into a compact
two-column rail so they never exceed the available height.

The deck can always be closed with the chevron. The record button never moves.

### Console header (fixed)

| Element | Behaviour | Why it is fixed |
|---|---|---|
| Record circle | `startRecording` / `stopRecording` | Never scroll away from the shutter |
| Lens badges `B0` / `F1` | `setCameraId` | One tap per lens, all lenses visible |
| Single `S` / Movie `M` circles | `setShootMode` | One tap, no menu |
| Mode ‹ › | Cycles enabled recording modes | Disabled modes are skipped |
| Status line | shoot mode, size, fps, bit depth | Read-only context for the current mode |

Every fixed element has a stable semantics label (`start recording`,
`camera: …`, `recording mode: …`) so UI automation can target it by role or
position without opening a menu.

## 4. Screen states

### 4.1 Launch / no camera

- Black field with `SLATECAM` wordmark and tagline.
- Primary `Open camera` button.
- Before camera access is granted the primary action becomes
  `Grant camera access`, with a short explanation and no red error; granting it
  opens the viewfinder automatically.
- Bridge error message for a genuine failure (not permissions).
- Probe-mode message: preview unavailable while the prober owns the camera.

### 4.2 Live camera

- Top status strip, preview, bottom dock and optional deck as above.
- Recording state replaces the record glyph with a stop square, turns the timer
  red, draws a 3 lp red border around the preview pane, and keeps the deck usable.
- The Clips action in the fixed header closes the camera and opens the media
  library; recording must be stopped first. Returning reopens the camera.
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

### 4.5 Media library and playback

- Clips opens a full-screen library from the console header. The camera closes
  while reviewing and reopens on return.
- Grid: newest first, thumbnail, duration, resolution, size, date, free space.
  Empty and error states are explicit; Refresh re-queries MediaStore.
- Player: play/pause, scrub, mute, current/total time, share via the system
  sheet, delete with confirmation. Deleting removes the MediaStore entry and the
  gyro sidecar, then refreshes the grid.
- Clips are app-owned in `Movies/SlateCam`; the library never uploads anything.

### 4.6 Audio

- An **Audio** card sits under Exposure: live input chips, the routed
  source/rate/channels, a stereo post-gain meter and an input-gain slider.
- The meter shows peak bars, an RMS marker, a slow peak hold and a CLIP badge
  that latches for two seconds. A silent input shows a NO SIGNAL hint after
  two seconds.
- Pre-roll metering runs while the camera is open; recording locks the input
  choice but gain stays live. Missing permission shows a Grant action; a failed
  input shows the error instead of pretending audio is present.
- Inputs are live-enumerated and hotplug-aware; only real capture routes are
  offered. The source line names `unprocessed`, `camcorder` or `mic`.

## 5. Element inventory

### 5.1 Status strip

| Element | Current source | Status |
|---|---|---|
| Output summary pill | `previewWidth`, `previewHeight`, `frameRate`, `tenBit` | Current |
| Recording timer | `recordingSeconds` | Current |
| Gyro sample count | `gyroSampleCount` | Current |
| Error pill | `recordingError`, `error` | Current |
| `SLATECAM` wordmark | launch screen only | Current |
| Camera ID / database version | Control Deck | Current |
| Exposure mode + ISO / shutter / WB | bottom dock and console | Current |
| Thermal status | native thermal listener only stops recording; not in state | Future |
| Battery level | not exposed | Future |
| Free storage | not exposed | Future |
| Audio meters | Audio card: post-gain peak/RMS, peak hold, clip latch | Current |
| GPS/timecode | not implemented | Future |

### 5.2 Bottom dock

| Element | Behaviour | Status |
|---|---|---|
| Record / stop | `startRecording` / `stopRecording` | Current |
| P / S / M | `setExposureMode` | Current |
| ISO chip | opens the console | Current |
| Shutter chip | opens the console | Current |
| WB chip | opens the console | Current |
| Control Deck toggle | opens/closes the deck | Current |
| Photo shutter | not in native API | Future |
| Zoom / lens selector | vertical log-scale zoom rail: min/max labels, lens-switch ticks, thumb readout; tap or drag anywhere | **Current** (`ZoomPlugin`) |
| Headphone monitoring | capture meters ship; no playback monitor path | Future |

### 5.3 Recording mode card

| Row | Values | Native call |
|---|---|---|
| Resolution | 4K, 1080 (families) | `selectRecordingMode` |
| Aspect | 16:9, 4:3 circles | `selectRecordingMode` |
| Frame rate | 24, 25, 30 (whatever the size exposes) | `selectRecordingMode` |
| Bit depth | 10-bit, 8-bit (when the combination exists) | `selectRecordingMode` |

A resolution is a family, not a fixed size. 4K 16:9 is 3840×2160; 4K 4:3 picks
the first enabled size from 3840×2880, 4096×3072 and 2880×2160. 1080 4:3 is
1440×1080 (with 1920×1440 as a fallback). Changing aspect therefore keeps the
resolution family instead of dropping 4K to 1080.

The initial selection is **4:3 · 1080 · 30 fps**, preferring a probe-verified
10-bit path and falling back to the closest enabled combination (16:9 1080p,
then any enabled mode) when a camera does not offer 1440×1080. Changing a row
keeps the other two where possible and falls back to the closest enabled
combination. If no size in the family/aspect is enabled, the console reports the
bridge reason and leaves the current mode alone.

The shoot set lists lenses as focal-length circles (`16mm`, `25mm`, `26mm`).
Tapping a circle includes or excludes that lens from the Movie/Vlog set; the
facing letter is prefixed only when two lenses share a focal length.

### 5.3.1 Viewfinder gestures

| Gesture | Action |
|---|---|
| Tap on the image | Focus at that point; shows a 32 lp reticle for ~900 ms |
| Pinch with two fingers | Zoom between the camera's `zoomMin` and `zoomMax` |
| Drag with one finger | No camera action |

The gesture area is the active image (the AspectRatio box), not the black bars,
HUD or console. In hardware AF modes the tap sets `CONTROL_AF_REGIONS` and sends a
one-shot AF trigger; in **SAF** it moves the software focus ROI and restarts the
search. In **MF** the tap is ignored because there is no depth information for a
manual diopter value.

### 5.4 Exposure card

| Element | Native call | Status |
|---|---|---|
| P / S / M circles | `setExposureMode` | Current |
| Metering pattern | `setMeteringMode` | Current |
| Meter speed (0–100%) | `setMeteringSpeed` | Current |
| ISO slider | `setIso` (manual M/S; auto in P/S) | Current |
| Shutter time slider | `setShutterNs` (S/M) | Current |
| Shutter angle presets | `setShutterAngle` | Current |
| AE auto / return to AE | `setAutoExposure` | Current |
| Exposure compensation / EV wheel | not in the Pigeon contract | Future |
| Zebra threshold for exposure | `setZebra` only takes on/off | Future |

Metering is software over the GPU luma grid: P biases the HAL AE with exposure
compensation, S drives ISO, and M leaves exposure manual. The speed slider is
disabled in M because there is no automatic exposure to follow. Highlight is
protect-only: it lowers exposure when the brightest 10% exceed 0.85 and never
brightens a flat scene.

### 5.5 Focus and stabilisation card

The card sits after Exposure in the primary column.

| Element | Native call | Status |
|---|---|---|
| Focus mode (`CAF`, `CAF-P`, `AF`, `MACRO`, `EDOF`, `MF`, `SAF`) | `setFocusMode` | Current |
| Manual focus slider (only when `MF`) | `setFocusDiopters` | Current |
| Shooting scenario (`Interview`, `Vlogging`, `Wedding`, `Cinematic`) | `setFocusScenario` | Current |
| Shift sensitivity (0–100%) | `setFocusShiftSensitivity` | Current |
| Transition speed (0–100%) | `setFocusTransitionSpeed` | Current |
| OIS on/off (only when the sensor advertises `on`) | `setOis` | Current |
| EIS on/off | `setEis` | Current |
| Focus distance readout | `focusDiopters` / `focusMax` | Current (in MF/SAF) |

Focus modes, OIS and EIS are per sensor: `focusModes`, `oisSupported` and
`eisSupported` come from live `CameraCharacteristics`; the bridge persists the
operator's choice per camera ID. A lens that only offers `off` (for example a
fixed-focus front camera) shows `MF` alone. Continuous-video AF is the default
when available.

**SAF** is the software contrast autofocus. It is shown only when the lens
reports a focus range (`LENS_INFO_MINIMUM_FOCUS_DISTANCE > 0`). CAF is the
default on every lens that reports it; SAF is selected explicitly, either by
tapping it or by choosing a shooting scenario. Selecting a scenario activates
SAF and applies the scenario's two normalized values; the sliders can then be
fine-tuned. A scenario chip is highlighted only while SAF is active, so a saved
preset cannot look like the current hardware mode. The card shows a hint when a
hardware focus mode is active, because shift sensitivity and transition speed
only affect SAF. The scenario/settings and the focus choice persist across
restarts. There is no face or eye tracking.
| Tap to focus | needs metering region API | Future |
| AF lock / AE lock buttons | probe has lock flags, not wired | Future |
| Hybrid CDAF | hill-climb focus engine | Future |
| Focus peaking controls | Monitoring card | Current |

### 5.5.1 Orientation

The same widgets serve both orientations; nothing is duplicated per layout.

- **Landscape / wide:** viewfinder and console side by side. The console docks
  on the right and the viewfinder takes the remaining width.
- **Portrait:** viewfinder on top and the console docked below at roughly half
  the height. The console uses its existing responsive columns. The viewfinder
  shows the complete recorded frame at its true aspect; it never crops. The
  red recording border wraps that frame (all four corners), so the viewfinder
  and the encoded clip always contain the same image. Portrait offers `9:16`
  (vertical video, effective `1080×1920`) and `4:3` (sensor open gate, stays
  `1440×1080` and is letterboxed). Landscape offers `16:9` and `4:3`. Labels
  follow the display orientation; sizes follow the actual frame.
- **Preview/recording:** the GL layer renders the sensor-upright landscape frame
  for both preview and encoder. The MP4 orientation hint describes the take
  orientation; Flutter presents the preview with that same quarter turn and
  inversely rotates focus taps. Portrait clips therefore play back exactly as
  framed without a second pixel path.
- Rotating while idle updates the preview immediately. Deciding to rotate
  during a take applies after the clip is finalized: a recording never changes
  orientation mid-stream.
- A circular rotate control beside the viewfinder-quality pill flips the app
  between portrait and landscape without reopening the camera. Its icon shows
  the destination orientation, and it uses the same bridge rotation path as a
  physical turn.

### 5.6 Picture profile card

| Element | Native call | Status |
|---|---|---|
| Profile chips: Standard / SlateLook + user profiles, plus **New** | `setProfile`, `createPictureProfile` | Current |
| Rename / delete the active user profile | `renamePictureProfile`, `deletePictureProfile` | Current |
| White balance mode | `setWhiteBalanceMode` | Current |
| Manual white balance (2000–10000 K, tint ±100), sliders snap to the chosen preset; dragging enters manual | `setManualWhiteBalance` | Current |
| Input calibration status | device database / InputProfile | Current (display only) |
| Creative LUT selection / reload | `setCreativeLut`, `reloadLuts` | Future (native path exists, UI hidden) |
| LUT SAF picker | requires plugin/native picker | Future |
| Detail controls (V/H, crispening, highlight) | spatial detail pass | Future |
| Creative vignette | shader effect | Future |
| Colour-space menu (SDR/HLG) | gated by probe | Future |
| Recordable log / S-Gamut delivery | needs a truthful log container | Future |
| ColorChecker capture workflow | `ColorCalibration` solver; capture tooling | Solver current, workflow Future |

### 5.6.1 Picture profile card

| Element | Native call | Status |
|---|---|---|
| Built-in Standard / SlateLook + saved user profiles | `setProfile` | Current |
| Create / rename / delete user profile | `createPictureProfile`, `renamePictureProfile`, `deletePictureProfile` | Current |
| Black level −15…+15 | `setProfileSettings` | Current |
| Gamma preset | `setProfileSettings` | Current |
| Black gamma range + level | `setProfileSettings` | Current |
| Knee auto/manual, point/slope/max point/sensitivity | `setProfileSettings` | Current |
| Color mode | `setProfileSettings` | Current |
| Saturation, colour phase, colour depth R/G/B/C/M/Y | `setProfileSettings` | Current |
| Per-parameter reset icon → default for that control | `resetProfileParameter` | Current |
| Reset all parameters | `resetProfile` | Current |
| White balance modes | `setWhiteBalanceMode` | Current |
| Manual white balance (2000–10000 K, tint ±100), sliders snap to the chosen preset; dragging enters manual | `setManualWhiteBalance` | Current |

Standard is the camera look (identity, no creative processing). SlateLook is
tuned to a Sony S-Cinetone-style picture profile. Built-ins are read-only. Saved
user profiles are editable copies: editing a parameter persists it immediately,
selecting another profile keeps each look independent, and deleting the active
profile falls back to Standard. Every editor row has a reset icon that restores
that one parameter; **Reset all parameters** restores the whole profile to the
library defaults.

Creative LUT selection and reload are not exposed while the LUT workflow is
deferred; the native `setCreativeLut` / `reloadLuts` paths stay in the bridge.

Gamma presets are look curves in the delivery domain, not container transfers.
S-Log/HLG/S-Gamut gamut modes are not offered until a recordable-log delivery
exists. Detail controls are deferred.

### 5.6.2 Viewfinder resolution (in the preview)

| Element | Native call | Status |
|---|---|---|
| `VF Full / 1080 / 720` pill, top-right over the preview | `setPreviewQuality` | Current |
| Exact preview output in the menu | `previewOutputWidth/Height` | Current |

The control sits in the viewfinder, not the console, because it only affects
what is displayed. The pill shows the active cap (`VF 720`); the menu lists Full,
1080p and 720p with the resolved output size and check mark. Full matches the
recording mode. The quality is a height cap that preserves the recording aspect;
the camera stream, encoder and recorded file stay at the recording mode, and
only the preview EGL surface and viewport change.

### 5.7 Monitoring card (no left rail)

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

All monitor toggles live in the Control Deck's Monitoring card. Overlays render
over the preview, and the histogram/waveform cards appear only while enabled.
Framing guides draw only the thirds grid lines, confined to the displayed image
rectangle, so they stay aligned with the recorded aspect when the preview is
letterboxed.

### 5.8 Capture tab

| Element | Native call | Status |
|---|---|---|
| Edge mode | `setEdgeMode` | Current |
| Noise reduction | `setNoiseReduction` | Current |
| Vignette correction (per sensor) | `setShadingCorrection` | Current |
| Antibanding | `setAntibanding` | Current |
| Output summary | preview size + frame rate from state | Current |
| HLG gate status | current bit depth + probe reason | Current |
| Shoot mode | Single vs Movie/Vlog common mode | **Current** |
| Recording mode | per-camera size/fps/bit-depth incl. 4K/1080p/720p, 16:9/4:3 and 4K 4:3 families, probe-gated | **Current** |
| Resolution / frame-rate selector | replaced by the recording-mode catalog | **Current** |
| Codec / bitrate selector | fixed HEVC, bitrate scales with mode | Future |
| Audio source and level | Audio card: input, source, gain, stereo meter | **Current** (ADR 0028) |
| Stabilisation / OIS / EIS | per-sensor toggles in Focus & stabilisation card | **Current** |
| RAW / DNG burst | Camera2 still capture not implemented | Future |
| Photo mode | not implemented | Future |

### 5.9 Device tab

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

### 5.10 Future AI tab

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

### 5.11 Media and future settings

| Element | Notes |
|---|---|
| Clip library / playback | Current: `:core:storage` MediaLibrary + Flutter clips screen |
| Export / share sheet | Current: Android share intent |
| Storage-free warning | Current: free space in the library header |
| Multi-select / batch delete | Future |
| Rename / album | Future |
| Proxy / transcode | Future |
| SD / USB-C SSD destination | Future |
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

### Console section accents

Each `DeckCard` carries one muted accent. It is rendered as a 5.5 % background
wash, a tinted frame and the card title colour, so sections read as distinct
groups without adding chrome:

| Section | Accent |
|---|---|
| Recording mode | amber `#E8B44A` |
| Shoot set & profiles | green `#4CC38A` |
| Exposure | blue `#6EA8FE` |
| Picture profile | violet `#C084FC` |
| Focus & stabilisation | cyan `#22D3EE` |
| Monitoring | pink `#F472B6` |
| Processing | orange `#FB923C` |
| Device | slate `#94A3B8` |
| Last clip | lime `#A3E635` |

- Corner radius: 14 for cards, 12 for controls, pill for chips.
- Spacing: 4/8/12/16/24.
- Numeric readouts use tabular figures where available.
- Minimum touch target: 44 logical pixels.
- Preview overlays are compact translucent pills; no full-frame dark gradient.
- Icons: Material Symbols equivalents (`record`, `tune`, `exposure`, `center_focus`, `palette`, `monitor`, `camera`, `memory`, `psychology`).

## 8. Implementation decision

The final implementation is:

- a full-bleed preview with a compact top status strip and a flat bottom dock;
- monitor toggles in the collapsible Control Deck on the right;
- a collapsible, tabbed Control Deck on the right with six tabs:
  **Exposure, Focus, Colour, Monitor, Capture, Device**;
- optional overlay cards for histogram and waveform;
- no AI tab; the media library opens as a separate clips screen;
- future options listed in this document and reserved structurally, not exposed
  as dead buttons.

The next section of the implementation is a rewrite of the Flutter widget tree
to this layout, keeping `CameraState` and `SlateCamHostApi` as the only bridge.
