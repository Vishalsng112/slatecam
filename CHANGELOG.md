# Changelog

All notable changes to SlateCam are recorded here. Shipped work belongs in this
file; forward-looking work belongs in [`docs/backlog.md`](docs/backlog.md), and
phase order/status belongs in [`docs/roadmap.md`](docs/roadmap.md).

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The app is
pre-1.0; build numbers are the debug APK `versionCode` used during device tests.

## [Unreleased]

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
- Phase 4: SlateLog, profiles, GPU LUT, zebra, false colour, peaking,
  histogram, waveform, framing guides.
- Phase 5: shutter angle, gyro sidecar/alignment, concurrent-camera
  verification, focus shift speed.
- Phase 7: versioned device database, quirks, calibration loading, opt-in
  reports.
- UI: cockpit layout, tablet two-column deck, monitor rail.

Detailed historical evidence is in [`docs/test-devices.md`](docs/test-devices.md).
