# Changelog

All notable changes to SlateCam are recorded here. Shipped work belongs in this
file; forward-looking work belongs in [`docs/backlog.md`](docs/backlog.md), and
phase order/status belongs in [`docs/roadmap.md`](docs/roadmap.md).

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The app is
pre-1.0; build numbers are the debug APK `versionCode` used during device tests.

## [Unreleased]

### Added — Logical zoom controller and verdict (build 2160)
- Eligible **exposed** logical multi-cameras now drive lens chips through
  `CONTROL_ZOOM_RATIO` in one session: `selectLensStop` switches to the logical
  camera only when needed, then hands 0.6×/1× stops to `SmoothZoom`; pinch and
  slider use the same ratio target. No session recreation and no frame pump
  inside the logical zoom range.
- The first open prefers the logical camera when it covers the current lens, so
  recording starts on the handoff session. Macro and uncovered lenses remain on
  the existing session-switch + `SwitchFramePump` fallback.
- `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` is read from capture results and
  surfaces in `CameraState`; the active sensor gets a dot on its lens chip.
- `logicalZoomHandoff` now captures a short camera stream while sweeping the
  discovered stops and passes only when frame gaps, exposure and WB jumps stay
  bounded and two physical IDs are observed. The controller path is gated on
  that recorded verdict, including for vendor-hidden logical cameras.
- POCO X6 Pro logical camera `4` passes the verdict (102 frames, 3.4 s, median
  33 ms, max gap 34 ms, exposure 1.15×, WB 1.46×, physical 0/2 observed) and
  front→back returns to it correctly.
- Hidden macro `3` remains excluded: opening it invalidates the vendor camera
  list until process restart. Hidden ultrawide/tele stay eligible.

### Added — Logical zoom handoff discovery (build 2150)
- The probe now detects logical multi-cameras whose `CONTROL_ZOOM_RATIO` range
  reaches below 1.0 and records the reachable physical lens stops in
  `cameras[].logical_zoom`: `handoff_eligible`, `ratio_range`, `stops`,
  `uncovered_ratios` and a reason when rejected.
- Stops come from each physical camera's intrinsic ratio (default 1.0 if
  metadata is missing); lenses outside the logical range stay explicit fallback
  candidates. This is discovery only — the controller path stays gated on the
  future `logicalZoomHandoff` recording verdict.
- POCO X6 Pro probe: logical camera `4` (physical `0`, `2`) reports stops at
  `0.621` (ultra-wide) and `1.0`, eligible; logical camera `5` is rejected
  because its range starts at 1.0.
- Probe schema 1.12.0; see ADR 0029.

### Fixed — HLG preview brightness (build 2149)
- The HLG preview now applies the BT.2100 OOTF and normalises the 1000-nit
  reference to a 100-nit SDR display before BT.709 encoding, with a soft
  highlight shoulder. Previously HLG scene-linear values went straight to
  BT.709, making the viewfinder about 40% darker than SDR on the same scene.
- Preview only: the recorded HLG stream remains Main10 / BT.2020 /
  arib-std-b67, and metering still uses the delivery signal.
- Pad 3 A/B on the same scene: preview mean luma 57 → 106 (SDR 102); the
  10-bit clip still verifies as HLG after the change.

### Removed — In-app colour calibration (build 2147)
- Removed the guided ColorChecker screen, calibration bypass capture, solver
  API and save path. The per-lens `InputProfile`/`CalibrationEntry` loader
  stays identity; no calibration matrix is applied by the removed flow.

### Changed — Console card balance (build 2146)
- In the two-column console, Processing now sits in the right column beside
  Focus & Stabilisation instead of stacking below it, removing the empty
  lower-right gap on tablet layouts.

### Added — Audio input, monitoring and metering (build 2140)
- New **Audio** card in the console: input selection, pre-roll stereo
  peak/RMS meter with peak hold and a latching clip indicator, and an
  input-gain slider (−12…+24 dB, applied before the meter and encoder).
- Inputs are enumerated live from `AudioManager` (built-in mics, USB,
  wired, Bluetooth); telephony/dock/remote-submix pseudo-inputs are filtered
  out. The selected device is persisted by a stable type/address/name key,
  routed with `AudioRecord.setPreferredDevice`, and re-resolved on hotplug.
- Audio is captured from the cleanest source the platform supports
  (`unprocessed → camcorder → mic`). The console shows the route, rate,
  channels and the actual source in use.
- Pre-roll metering runs while the camera is open. The monitor releases the
  microphone before recording and restarts after the take.
- `AudioMeter`/`AudioLevel` in `:core:codec` compute gain-corrected peak and
  RMS in dBFS and clamp/detect clipping; JVM tests cover silence, −6 dBFS,
  full scale, mono, gain, clipping and partial buffers.
- AAC audio is now 256 kbps at 48 kHz stereo (was 192 kbps). Recording still
  works without microphone permission, with an explicit state/error instead of
  silent omission.
- Meter data is pushed on a separate `onAudio` channel at ~10 Hz, so it never
  rebuilds the viewfinder or the full console.
- See ADR 0028.

### Added — Playback and media library (build 2127)
- The console header has a **Clips** action that opens a full-screen media
  library. The camera closes while reviewing and reopens on return; recording
  must be stopped first.
- Library: clips from `Movies/SlateCam`, newest first, with lazy thumbnails,
  duration, resolution, size, date and free-space. Empty/error states included.
- Player: play/pause, scrub, mute, current/total time, name/size/date, plus
  **Share** (system chooser) and **Delete** (confirmation; removes the MediaStore
  entry and the gyro sidecar). Deleting refreshes the library.
- New `MediaLibrary` in `:core:storage`; Pigeon media API
  (`listMediaClips`, `mediaStorageInfo`, `mediaClipThumbnail`, `deleteMediaClip`,
  `shareMediaClip`).
- Flutter uses the official `video_player` plugin (ExoPlayer on Android); the
  plugin subproject is explicitly allowlisted in the module graph.
- See ADR 0026.

### Changed — Vertical right-side zoom rail (build 2125)
- The zoom panel moved from a bottom-left horizontal bar to a **vertical strip
  on the preview's right edge**, centred between the top VF pill and the bottom
  dock.
- Lens chips are stacked at the top, followed by a vertical 48 lp track
  (drag up = zoom in), zoom markers and the ratio readout. The track keeps the
  full 48 lp touch target and the smooth camera ramp (ADR 0023).
- Monitor cards moved back down into the space freed at the bottom-left.

### Changed — Per-section console accents (build 2124)
- Every `DeckCard` now takes an optional tint. The console uses one muted
  pastel accent per section, applied as a 5.5% background wash, a tinted frame
  and a tinted card title, so Recording mode, Shoot set & profiles, Exposure,
  Picture profile, Focus, Monitoring, Processing, Device and Last clip are
  visually distinguishable at a glance.
- Accents: Recording = amber, Shoot set = green, Exposure = blue,
  Picture profile = violet, Focus = cyan, Monitoring = pink,
  Processing = orange, Device = slate, Last clip = lime.
- Layout, controls and state are unchanged.

### Added — User picture profiles (build 2122)
- The Picture profile card can now **create, rename, delete and select multiple
  saved looks**, alongside the read-only Standard and SlateLook built-ins.
- Each custom row has a **per-parameter reset icon**; the card has **Reset all
  parameters**. Edits persist immediately and each profile keeps independent
  settings.
- Profiles are stored in an ordered library in `shoot-profile` preferences. The
  legacy single Custom look migrates to a user profile named **Custom** on first
  load; a missing active id falls back to Standard.
- Pigeon: `CameraState.pictureProfiles` replaces `profileNames`;
  `createPictureProfile`, `renamePictureProfile`, `deletePictureProfile` and
  `resetProfileParameter` added. See ADR 0025.

### Fixed — Viewfinder judder from state-push rebuilds (build 2120)
- The camera stream was never dropping frames: GL draws were a clean 33 ms,
  while the viewfinder presentation had repeated **83–100 ms holds**. The cause
  was the full `CameraState` snapshot and a whole-tree Flutter rebuild at
  analysis/exposure rate.
- Added a small `CameraTelemetry` Pigeon channel for live readouts. `onState` is
  now only sent on configuration changes (open/close, camera, mode, profile,
  recording start/stop); telemetry carries ISO, shutter, WB, zoom, record timer,
  error, monitor flags and gated analysis arrays.
- Flutter no longer rebuilds the preview and console on telemetry. Only the
  status pills, bottom dock, zoom control, monitor cards, recording border and
  the console's Exposure card listen through a `ValueNotifier`.
- Histogram/waveform arrays are sent only when those cards are visible, at 2 Hz.
- Measured on the Pad 3: idle viewfinder now **126/127 frames at 33.2–33.5 ms**
  with no 83/100 ms holds; recording is mostly 33.3 ms; histogram-on has ~1
  short hold/s. See ADR 0024.

### Changed — Touch-friendly zoom control (build 2106)
- The zoom control is now a two-row panel up to 560 lp wide: lens chips on top,
  then a full-width 48 lp zoom strip with a 22 lp thumb.
- The whole strip is an opaque tap/drag target — tap or drag anywhere on it, not
  just the small thumb. The thumb tracks the finger during the gesture and holds
  the target until the camera ramp catches up.
- Removed the horizontal scroll view around the zoom bar; it was stealing
  horizontal drags from the slider. Lens chips now wrap instead.
- Enlarged lens chips to a 36 lp minimum touch height.
- Fixed a single-tap zoom dropping immediately: the first ramp tick could see
  `dt = 0` and stop before moving. A tap now starts and completes the ramp like a
  drag.

### Added — Smooth zoom in core camera (build 2100)
- Slider and pinch zoom now feed a target; `core:camera`'s new `SmoothZoom`
  eases the applied ratio on the camera thread instead of snapping each input
  event into a capture request.
- The ramp chases exponentially in log space (τ = 90 ms) with a 6 log-units/s
  velocity cap, so small trims are immediate while a 1× → 10× move takes about
  0.38 s instead of a cut. One step per 33 ms camera frame keeps the recorded
  clip continuous.
- The preview, encoder and analysis frames all see the same intermediate crop;
  no second preview-only zoom path. The slider readout and thumb follow the
  applied value as it settles.
- Real-device evidence: Pad 3 build 2100 recorded 10× → 1× as
  9.7, 8.9, 7.2, 3.8, 2.9, 2.3, 1.4, 1.2, 1.1, 1.0 across ~0.7 s, and a zoom
  performed while recording is present in the saved MP4 frames.

### Added — Recording border (build 2099)
- While a clip is recording, a 3 lp danger-red border is drawn around the whole
  viewfinder pane. It disappears when recording stops, making the recorder state
  visible from any distance even when the REC timer is off-screen focus.

### Changed — Creative LUT UI hidden (build 2098)
- The **Creative LUT** dropdown and **Reload LUTs** action are removed from the
  Picture profile card. LUT loading/creation is deferred; the native
  `setCreativeLut` / `reloadLuts` paths remain in the bridge for later.
- Picture profile now flows directly from the profile controls into White
  balance.

### Changed — Aspect-true guides and VF pill restored (build 2097)
- Framing guides now paint inside the displayed image rectangle instead of the
  whole preview pane, so the thirds align with the 4:3/16:9 frame even when the
  preview is letterboxed with the deck open.
- Guides draw only the thirds grid lines; the safe-frame rectangle and centre
  crosshair are removed.
- The compact `VF Full / 1080 / 720` preview-quality pill is restored at the
  preview's top-right.

### Changed — Debug viewfinder cleanup (build 2096)
- The left/preview overlay now shows only what is needed for debugging:
  compact **output format**, **REC timer/gyro** and **ERROR** pills at the
  top-left, a flat exposure dock (record, P/S/M, ISO/shutter/WB), and the
  lens/zoom bar. Brand, camera dropdown, MOVIE pill, WB pill and the wide glass
  top bar are gone from the preview.
- The left monitor rail and VF-quality pill are removed from the viewfinder.
  Monitor toggles remain in the Control Deck's Monitoring card; histogram and
  waveform cards still render bottom-left when enabled there.
- The bottom dock is now a flat floating row with no enclosing panel, and the
  preview no longer uses the top/bottom dark gradient.

### Changed — 4:3 · 1080 · 30 fps default (build 2095)
- A fresh open now selects **1440×1080 (4:3) at 30 fps**, preferring a
  probe-verified 10-bit path, instead of 1920×1080 16:9. The same preference is
  used when a lens switch invalidates the current mode.
- Capability gating is unchanged: a camera without 1440×1080 still falls back to
  an enabled 1080p mode, then to any enabled mode, so no unsupported size is
  ever requested.

### Changed — Viewfinder resolution and preview pacing (build 2094)
- The **Full / 1080 / 720** preview-quality choice moved out of the console and
  into the viewfinder: a `VF` pill at the preview's top-right opens a menu with
  the exact preview output and a note that the recording stays at the mode
  resolution. The console's Viewfinder card is gone.
- Preview frames are now presented on the display vsync instead of on camera
  arrival, and at most one draw is pending, so a slow frame is coalesced rather
  than queued. Camera delivery is unchanged at exactly 30 fps.
- The analysis pass (histogram, waveform, metering, software-AF score) runs at
  10 Hz instead of 15 Hz. On the Pad 3 each analysis frame spent ~9 ms in a
  synchronous `glReadPixels` on the render thread, and the bridge only publishes
  monitoring data at 5 Hz.
- Measured with a temporary per-frame colour probe: GL renders every camera
  frame (1.3–1.5 ms, ~5 ms average at 10 Hz analysis), while the display held
  frames irregularly — the remaining latency is the camera → Flutter texture →
  compositor path, not the pixel pipeline (ADR 0022).

### Fixed — first-run camera access (build 2093)
- A fresh install (or cleared app data, which resets runtime permissions) no
  longer tries to open the camera before the permission prompt is answered. The
  launch screen asks for access first, refuses to open without it, and the
  viewfinder opens automatically once access is granted.
- A denied prompt now shows a plain explanation and a **Grant camera access**
  action instead of a red `SecurityException: validateClientPermissionsLocked`
  banner, and the choice can be retried without restarting the app.
- `openCamera` is transactional: a failed attempt (permission, busy camera, bad
  mode) closes the half-open session and resets the GL renderer, so a retry can
  no longer wedge the app. Previously the first failed open left the preview
  pipeline started and the next tap did nothing until the process restarted.
- Pigeon adds `cameraPermissionGranted` and `requestCameraPermissions`.

### Added — Manual white balance (build 2092)
- White balance now includes a **Manual** control: 2000–10000 K in 100 K steps
  plus a green/magenta tint from −100 to +100, shown as sliders in the Picture
  profile card with a live `3500K` readout in the viewfinder bar.
- Manual locks the HAL AWB preset nearest the requested temperature and applies
  only the kelvin/tint residual in the existing scene-linear shader WB stage
  (alongside per-lens input calibration). Measured on the Pad 3: the CCT mode
  is not advertised (`availableModes [0,1,2]`), raw Camera2 gains leave a strong
  green cast because they cannot neutralise the sensor's native white point, and
  the reported sensor colour transforms do not compose to a usable CCM.
- The temperature and tint sliders are always shown when the sensor offers a
  preset; selecting a preset moves them to that preset's nominal CCT and tint 0
  (daylight → 5500 K), and touching either slider switches to manual.
- The preset follows the slider: crossing a preset boundary switches AWB in the
  same request; kelvin/tint changes inside a preset update only the shader, so
  dragging does not rebuild the capture request.
- Manual is offered only when the sensor advertises a standard WB preset; the
  raw `Off` chip is no longer shown, and a persisted stale `off` migrates to
  manual.
- The choice and its kelvin/tint persist in `shoot-profile` across restarts and
  survive a lens switch when the new sensor advertises presets; otherwise the
  mode falls back to auto.
- `ManualWhiteBalance` is pure Kotlin (preset table, Tanner Helland black-body
  approximation, green-normalised residual clamped 0.25–4) with 13 JVM tests.
- Pigeon adds `whiteBalanceKelvin`, `whiteBalanceTint`,
  `manualWhiteBalanceSupported` and `setManualWhiteBalance`.
- Temperatures are approximate until measured per-lens calibration data ships;
  absolute accuracy is the preset's nominal CCT plus the black-body model.

### Changed — Picture profiles (build 2091)
- The Colour card is now **Picture profile** with three profiles:
  **Standard** (the camera's native look; identity, no creative processing),
  **SlateLook** (Sony S-Cinetone-inspired: soft shoulder, gentle knee, slightly
  muted saturation, warm skin) and **Custom** (user-editable, persisted).
- The SlateLog profile and the flat monitoring view are removed; `SlateLog.kt`
  and its tests are deleted. Recording always shows the active look on the
  preview and writes the same look into HLG/Rec.709.
- Custom exposes black level, gamma presets (standard/movie/still/cine1–4/
  ITU709/ITU709(800%)/S-Cinetone), black gamma range+level, knee auto/manual
  with max point/sensitivity or point/slope, color mode, saturation, colour
  phase, six-axis colour depth (R/G/B/C/M/Y) and reset.
- New scene-linear look pipeline: black level → black gamma → colour matrix/
  phase/depth → saturation → knee in working space, then the look-gamma curve
  and creative LUT in the delivery domain. Standard bypasses all of it.
- The Colour science card and its five commands (`setColorExposure` … `resetColorScience`) are removed; `ColorScience.kt`, `ToneShaper.kt` and the built-in WARM profile are deleted. Custom is the single creative path.
- Creative LUTs are now a separate persisted selection so the profile list stays
  at three.
- Pigeon adds `ProfileSettings`, `setProfileSettings`, `resetProfile`,
  `setCreativeLut`, `profileSettings`, `creativeLut` and `creativeLuts`.
- Gamma presets are look curves, not container transfers; S-Log/HLG/S-Gamut
  modes remain unavailable until a truthful recordable-log delivery exists.
  Auto knee is a static mapping; Detail controls are not yet exposed.
### Added — Phase 5.5 shoot profiles
- Per-camera capability reader (`CameraCapabilitiesReader`) for sizes, exact AE
  frame rates, autofocus modes, 10-bit claim and zoom range. Hidden/openable
  lenses are included.
- `RecordingModeCatalog`: 4K 16:9 (3840×2160), 4K 4:3 (4096×3072, 3840×2880,
  2880×2160), 1080p 16:9, 1440×1080/1920×1440 4:3 and 720p; 24/25/30 fps; 8-bit
  and probe-verified 10-bit. Invalid modes are disabled with a reason.
- Movie/Vlog shoot mode: strict common-mode intersection across probe-visible,
  openable, non-macro lenses.
- Validated switch-while-recording. Single mode rejects; movie mode accepts only
  when the target lens supports the active mode. Recorder/encoder/audio/gyro
  stay alive.
- Atomic configure-and-switch when idle, so a mode change and lens change do not
  race two session reopens.
- Autofocus is read per camera; a lens without AF is forced to manual and the UI
  disables the AF action.
- Per-camera input profile is applied on every lens switch; measured ColorChecker
  matrices are still data to add.
- Shoot-set editor: include/exclude any eligible lens; selection is persisted in
  `shoot-profile` and survives restart. Fewer than two selected lenses disables
  Movie mode with a reason; the editor stays visible so it can be fixed.
- Named shoot profiles: save the current lens set, apply, rename and delete.
  Saving an existing name (case-insensitive) updates it instead of duplicating;
  the last profile cannot be deleted. Profiles store probe-stable lens keys
  (`facing:role:focal`), so they still apply if camera ids are renumbered.
- `ShootProfileStore` is pure Kotlin behind a storage interface and covered by
  17 JVM tests (seeding, round-trip, name matching, active fallback, stale
  keys, last-profile guard).
- POCO multi-lens pass: movie mode main → ultra-wide → front in one continuous
  125.3 s clip (3733 frames). Selecting the macro is rejected with
  “lens 3 is not part of the movie lens set”; recording continues.
- Pad 10-bit movie mode: per-lens verification is complete, so the common mode
  includes 1080p and 4:3 10-bit HLG; a back→front switch kept one Main10 clip.
- Profile name prompt is a compact dialog that survives the POCO landscape IME;
  controller disposal no longer runs while the route is animating out.

### Added — Control Console and seamless switching
- Replaced the six-tab Control Deck with a single Control Console: fixed
  RECORD/STOP, previous/next camera and recording-mode steppers, and a
  Single/Movie toggle above two columns of cards. Camera, mode, shoot mode,
  exposure mode, angle, profile, white balance, processing and shoot profiles
  are direct chips/segments instead of dropdowns. The console starts open at
  760 lp on tablets and can still be collapsed.
- Condensed console pass: circular RECORD/STOP, circular S/M shoot-mode and
  per-lens badges (`B0`, `F1`) in a 44 lp hit target, icon-chip monitoring and
  three-column cards at >=720 lp (two at >=520 lp).
- Recording mode is now the industry-standard three-row selector: resolution
  (4K / 1080p / 4:3 / 720p) → frame rate (24/25/30) → bit depth (10-bit/8-bit).
  The closest enabled combination is chosen when a change is not available,
  and a size with no enabled mode reports the bridge's reason.
- Shoot-set lenses are focal-length circles (`16mm`, `25mm`, `26mm`) instead of
  verbose chips; the facing letter is added only when two lenses share a
  focal length. Tapping a circle includes/excludes that lens.
- Recording mode offers **4K** and **1080** resolution families with circular
  **16:9 / 4:3** aspect buttons. Changing aspect keeps the family: 4K 16:9 is
  3840×2160, and 4K 4:3 picks the first supported size from 3840×2880,
  4096×3072 and 2880×2160 instead of dropping to 1080. Pad 3 records
  4096×3072 30p 8-bit, POCO X6 Pro records 2880×2160 30p 8-bit. 10-bit for the
  new 4:3 sizes is probe-gated through the new `dci43_*`, `uhd43_*` and
  `qhd43_*` presets.
- Lens-shading (vignette) correction is now on by default and exposed as a
  per-sensor **Vignette correction** toggle in Processing. The controller
  requests `SHADING_MODE_HIGH_QUALITY` (else `FAST`) instead of forcing `OFF`,
  and the bridge remembers the choice per camera ID. Measured Pad 3
  corner/centre luma ratio rose from 0.284 to 0.455 with correction on.
- Focus is back as an explicit mode control: `MF`, `AF`, `CAF`, `CAF-P`,
  `MACRO` and `EDOF` appear only when the active camera reports them.
  Continuous-video AF remains the default, and selecting `MF` reveals the focus
  distance slider.
- OIS is exposed per sensor and enabled by default where the camera advertises
  `on` (POCO back). EIS is enabled by default where advertised and can be
  switched off. Both preferences persist per camera ID; the Pad 3 back only
  advertises OIS `off`, so its OIS control is hidden rather than faked.
- Exposure metering patterns: **Multi**, **Center**, **Spot**, **Average** and
  **Highlight**, plus a **Meter speed** slider from slow (0) to fast (1). The
  meter is a pure `:core:camera` engine fed by a 32×18 luma grid from the GPU
  analysis pass. P applies the error as AE compensation on top of the HAL AE; S
  applies it to ISO; M stays manual. Highlight is protect-only, so a flat scene
  is not pushed up to 0.85. The mode and speed persist across restarts.
- Software focus engine (**SAF**) for lenses with a real focus range: a pure
  contrast-detect hill-climb over `LENS_FOCUS_DISTANCE`, fed by a Laplacian
  sharpness score from the 320×180 analysis frame at 15 Hz. Adds normalized
  **Shift sensitivity** and **Transition speed** controls (0–1) and four
  shooting scenarios — **Interview**, **Vlogging**, **Wedding**, **Cinematic** —
  that set both values and activate SAF. Settings and the SAF choice persist
  across restarts. P-mode AE compensation is throttled to 5 Hz with one step per
  update so it cannot fight the focus search. No face or eye tracking.
- Viewfinder gestures: **tap to focus** at any point with a short reticle, and
  **pinch to zoom** within the lens zoom range. Taps are mapped through the live
  GL texture transform, not a guessed sensor rotation; hardware AF gets a
  weighted AF region plus a one-shot trigger, and SAF moves its contrast ROI.
  The existing zoom slider remains as a precise fallback.
- Staged colour architecture foundation: `ToneShaper` (linear pivot contrast,
  shadow toe, highlight knee), `PictureProfile` pivot/knee/toe data, and
  `InputProfile` per-lens WB/shadow trims. The device database accepts optional
  `lens_key`, `wb_r_offset`, `wb_b_offset` and `shadow_lift`, looked up by the
  stable lens key before the camera id. Missing data stays identity.
- Probe schema 1.11.0 records tone-map modes/curve points and colour-correction
  modes. No tone curve or manual AWB is forced; those remain gated on verified
  `CaptureResult` support and the future scene-linear path.
- Pad 3 probe evidence: schema 1.11.0 reports `contrast_curve`/`fast`/
  `high_quality`, 512 curve points and `transform_matrix`/`fast`/`high_quality`
  for both cameras; `gamma_value` is not advertised.
- Scene-linear colour shader: HLG/BT.709 input EOTF → linear Rec.2020 →
  per-lens WB/CCM/shadow → profile contrast/pivot/knee/toe/saturation → creative
  LUT → output routing. Encoder gets HLG or BT.709; analysis gets the delivery
  signal without monitoring transforms; preview gets BT.709 or SlateLog plus
  LUT/overlays. SlateLog is now a monitoring view only, so containers no longer
  carry a baked log signal under HLG/Rec.709 metadata.
- FP16 encoder intermediate: when the GL driver supports half-float render
  targets, the camera + look are rendered into RGBA16F at capture size and
  copied to the encoder with an ordered dither before the 8-bit surface. The
  preview/analysis path is unchanged. Falls back to the direct encoder draw when
  unsupported. Pad 2081 recorded 1080p and 4K HLG Main10 with the FP16 path.
- Colour science tuning: a live Colour science card with **Exposure**,
  **Contrast**, **Saturation**, **Temperature** and **Tint** sliders plus reset.
  The trim is applied on top of the active picture profile (including LUT
  profiles), baked into preview and recording, persisted, and unit-tested in
  `ColorScienceTuning`.
- Viewfinder resolution changer: **Full / 1080 / 720** caps the preview height
  while the camera stream and encoder stay at the recording mode size. Changing
  it retargets only the preview surface and is safe while recording. The
  Viewfinder card shows the preview output and the stream size.
- Software focus is now the default on lenses with a focus range, and hunting
  is suppressed: the Laplacian score is normalised by mean luma, smoothed with
  an EMA, searched with a 0.5% improvement threshold, and guarded by a lock
  cooldown plus wider trigger/hold defaults. The controller waits for the lens
  report after a move, and P-mode AE compensation pauses during a focus search.
  Tap-to-focus uses an 20% AF region and no redundant trigger in continuous
  modes, which stops the post-tap hunt.
- Exposure mode uses circular **P / S / M** buttons.
- The Focus card and the bottom-dock focus chip are removed for now; the camera
  keeps continuous autofocus. Manual focus returns with the tap-to-focus/AF-lock
  work.
- Device diagnostics always render last, after the shooting cards.
- `SwitchFramePump` repeats the last camera frame into the encoder while a
  movie-mode lens switch has no session, so the clip keeps a continuous
  timeline. Policy covered by 6 JVM tests.
- Pad movie switch measured at 5,969 frames / 199.3 s Main10 HLG with one
  122 ms pause; median frame interval 33.4 ms.
- `ColorCalibration` least-squares solver turns 24 observed/reference patches
  into an `InputProfile` (3×3 matrix + exposure trim) with plausible-fit
  rejection; covered by 9 JVM tests. No matrices are shipped without a measured
  target.

### Changed — Phase 3 recording completion (Pad 3)
- 10-bit HLG recording enabled by probe verification and SPS checks.
- Recorder builds `hvcC` from VPS/SPS/PPS in the first access unit when
  `outputFormat` has no `csd-0`.
- HLG encoder surface uses a non-recordable RGBA8888 EGL config; the 10-bit
  config is rejected by the driver at swap time.
- Recording modes: 16:9/4:3, 24/30 fps, 8/10-bit, selected in the Capture tab.
- Bitrate scales with resolution and frame rate.
- Verified on Pad 3: 4K 30p Main10 HLG; 1080p30 60 s (1859 frames, thermal 0);
  1080p24 8-bit 60 s (1488 frames); 4:3 1440×1080 8-bit.
- Verified on POCO X6 Pro: 4K 30p 8-bit (40.2 Mbps), 4:3 1440×1080 8-bit,
  1080p30 60 s (1859 frames, 12.0 Mbps, thermal 0). 10-bit stays disabled by the
  `poco-duchamp-force-8bit` quirk and the SPS Main/8-bit verdict.

### Changed — orientation and aspect (ADR 0006)
- GL applies sensor/display rotation to preview and encoder; encoded pixels are
  upright and the MP4 rotation hint is 0.
- Flutter no longer rotates the preview; it fits the capture aspect with
  `BoxFit.contain`, removing the horizontal stretch.
- Front camera uses its own sensor rotation (270° in landscape).

### Added — zoom/lens plugin
- `ZoomPlugin` above the transport dock: probe-verified back-lens chips
  (role + 35 mm equivalent), continuous zoom, hidden lenses marked.
- Bridge commands `selectLens`, `setZoom`; per-camera zoom state.

### Added — prober 1.9.0 / schema 1.10.0
- 4:3 presets (`fhd43_*`) and per-lens HLG10 verification: every
  exposed/openable lens gets its own `camera_hlg10_session_c<id>_<size>` verdict.
- Pad 3 now verifies 10-bit at 4K/FHD/4:3 on the back lens and FHD/4:3 on the
  front lens, enabling **10-bit HLG movie mode** across both.
- Movie mode back→front verified as one continuous 166.6 s Main10/HLG clip.
- Per-size 10-bit verification (`hevc_main10_encode_fhd`,
  `camera_hlg10_session_fhd`) and SPS bit-depth parsing for Annex B,
  length-prefixed and hvcC records.
- Lens roles (`default`/`ultrawide`/`tele`/`macro`), role basis/confidence,
  intrinsic zoom ratio and 35 mm equivalents.
- Hidden-camera scan with `exposed`/`openable`, and OEM auxiliary-camera policy
  (`camera_aux`).
- Encoder limits/HDR/P010, per-device audio inputs, gyro, RAW sizes, max frame
  duration and lens pose/intrinsics.

### Fixed
- Manual and shutter-priority exposure now snap to a flicker-safe grid because
  Camera2 ignores `CONTROL_AE_ANTIBANDING_MODE` once AE is off: 10 ms multiples
  at 50 Hz, 1/120 s multiples at 60 Hz, and the device region's mains period
  for `auto` (50 Hz fallback). The shutter slider is quantised to that grid,
  angle presets are snapped to the nearest safe exposure, and switching
  anti-banding re-snaps the held shutter. The Exposure card shows the active
  grid and resulting angle. `CameraState.shutterStepNs` exposes the step.
- Fresh install on a device with no probe report no longer crashes on the first
  state push: `activeProfileId()` handles an empty eligible-lens set instead of
  calling `.first()`.
- Probing on Android 14 no longer aborts every camera when
  `COLOR_CORRECTION_AVAILABLE_MODES` is absent from the platform framework; the
  key is read reflectively and reports an empty list when missing.
- Pad 3 front/back 10-bit configuration no longer fails the session; the mode
  falls back only where the target lens is not verified.
- Preview/recording framing now match; 4:3 no longer stretches.
- Changing resolution/aspect now recreates the preview EGL window surface. The
  old surface kept its previous buffer size, so a 4:3 (or 4K) mode was drawn
  into only part of the texture: the image looked cropped/squashed and the rest
  of the viewfinder was black. `SurfaceProducer.setSize` alone does not retarget
  the GL renderer; `forcedNewSurface` plus `updateTarget` is required.

## Earlier phases (0–4)
- Phase 0: identity, licence, module graph, probe schema.
- Phase 1: capability prober with claimed-vs-verified verdicts.
- Phase 2: Camera2 preview, manual ISO/shutter/focus/WB, P/S/M exposure.
- Phase 3: 8-bit HEVC + AAC recording, MediaStore, thermal guard.
- Phase 4: picture profiles, GPU LUT, zebra, false colour, peaking, histogram,
  waveform, framing guides.
- Phase 5: shutter angle, gyro sidecar/alignment, concurrent-camera
  verification, focus shift speed.
- Phase 7: versioned device database, quirks, calibration loading, opt-in
  reports.
- UI: cockpit layout, tablet two-column deck, monitor rail.

Detailed historical evidence is in [`docs/test-devices.md`](docs/test-devices.md).
