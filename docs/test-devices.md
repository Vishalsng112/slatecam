# Test devices

Real hardware is the source of truth. Emulators are only for CI smoke tests.
This list is the Phase 1 test bench; every entry must run the prober before its
capabilities are trusted.

## Reference devices

| Device | SoC | Role | Why it matters |
|---|---|---|---|
| POCO X6 Pro 5G | MediaTek Dimensity 8300-Ultra | **Adversarial device** | Xiaomi/MediaTek HALs are the classic case of advertised capability that does not survive a real encode. If the prober is too trusting, this device proves it. |
| OnePlus Pad 3 | Qualcomm Snapdragon 8 Gen 3 | **Reference device** | Cleaner HAL behaviour and a different vendor stack (Qualcomm `c2.*` encoders) from the MediaTek path. Also catches tablet-specific layout and camera-set assumptions. |

### What each device must prove

**POCO X6 Pro 5G**

- Whether `DYNAMIC_RANGE_TEN_BIT` is real at UHD, and at which frame rates.
- Whether `MediaCodecList` advertises HEVC Main10 that the HAL then rejects.
- Whether 24 and 25 fps are offered at 4K, not just 30.
- Thermal behaviour under a 10-minute 4K encode.
- Whether the camera HAL lies about `MANUAL_SENSOR` or clamps manual exposure.

**OnePlus Pad 3**

- Baseline behaviour for a Qualcomm encoder path (HEVC Main10, HLG metadata).
- Logical multi-camera handling on a device with a different camera topology.
- Large-screen/tablet layout of the preview and controls.
- Gyro timestamp source for metadata alignment.

## Adding a device

1. Install the prober build on the device.
2. Export a `summary` report and attach it to a device report issue.
3. Record a short verification clip if the device claims the cinema tier.
4. Add a row to this table with the role it plays in the test bench.

A device that only ever produces `standard` or `basic` is still useful: it is
the evidence that the app degrades honestly instead of hiding missing features.

## First report — POCO X6 Pro 5G (2026-11-23)

Prober 1.0.0, app 0.1.0, Android 16 (`BP2A.250605.031.A3`), SoC `MT6897`.

| Area | Reported |
|---|---|
| Tier | **`cinema`** — verified, no missing requirements |
| Camera 0 (back) | `level_3`, `manual_sensor`, `manual_post_processing`, `dynamic_range_ten_bit` |
| Camera 1 (front) | `level_3`, same manual/ten-bit capabilities, no UHD presets |
| Camera set | only IDs `0` and `1`, no physical IDs, no logical multi-camera |
| 4K | 30 fps maximum; `uhd60_*` correctly reported unsupported |
| 1080p | 120 fps available through constrained high-speed streams |
| 10-bit | claimed at UHD and FHD 24/25/30; UHD then **verified** at 3840×2160 |
| HEVC encoders | `c2.mtk.hevc.encoder`, `OMX.MTK.VIDEO.ENCODER.HEVC`, secure variants: all claim profile `main` only |
| Verification encode | `c2.mtk.hevc.encoder`, 3840×2160, 11 frames, 75,341 bytes, `profile=2` (Main10), `transfer=7` (HLG), 299 ms |
| HLG10 camera session | camera 0, `OutputConfiguration` with HLG10 at 3840×2160, configured in 748 ms |
| Display | 120 Hz, wide gamut, HDR10 / HLG / HDR10+ / Dolby Vision, 500 nits |
| Audio | 48 kHz, 24-bit PCM supported, not low-latency |
| Native page size | 4 KB |

### Findings

1. **MediaTek under-reports Main10.** No HEVC encoder on this ROM lists
   Main10 in `CodecCapabilities`, yet `c2.mtk.hevc.encoder` produces a Main10
   stream with HLG colour metadata when asked. Before verification the tier was
   `standard`; after it, `cinema`. This is the strongest possible argument for
   the claimed-versus-verified design: trusting the platform would have
   disabled 10-bit recording on a phone that can actually do it.
2. **Only two camera IDs are exposed, with no physical IDs.** The ultrawide and
   macro lenses are not reachable through Camera2 on this ROM. Lens switching
   and dual-camera support must be probed per device and described honestly;
   marketing sensor counts are not a capability report.

### Rules this report produced

- A verified encode may promote a device to `cinema` even when the encoder
  advertises no Main10 profile.
- A verdict is credited only to the codec that produced output. The other HEVC
  encoders stay `not_run` rather than inheriting a pass.
- A preset verdict covers only the size that actually encoded. The 4K pass does
  not promote FHD, and an unsupported mode never inherits a pass.
- The device database stores both the claim and the verification result, because
  they disagree on this device and probably on many more.

## Second report — OnePlus Pad 3 (2026-11-23)

Prober 1.0.0, app 0.1.0, Android 17 (API 37), SoC `SM8750P` (Snapdragon 8 Elite).

| Area | Reported |
|---|---|
| Tier | `cinema` — claimed and verified |
| Cameras | IDs `0` (back) and `1` (front), both `level_3`, both ten-bit, concurrent |
| Sensor (back) | 4160×3120, 4.66×3.49 mm, ISO 100–1600, exposure 20.8 µs – 260 ms |
| Presets | UHD 24/25/30 in 10-bit and 8-bit; FHD 24/25/30; no 60/120/240 modes |
| HEVC encoders | `c2.qti.hevc.encoder` and five siblings, all advertise Main10 |
| Verification | `c2.qti.hevc.encoder`, 4K Main10 HLG, 11 frames, 24,134 bytes, 348 ms |
| Display | 60 Hz active mode, wide gamut, HDR10 / HLG / HDR10+ / Dolby Vision, 500 nits |
| Audio | 48 kHz, 24-bit PCM, low latency |
| Native page size | 4 KB |

### Vendor comparison

| | POCO X6 Pro 5G | OnePlus Pad 3 |
|---|---|---|
| SoC | MediaTek MT6897 | Qualcomm SM8750P |
| Android | 16 (API 36) | **17 (API 37)** |
| Main10 advertised | no | yes |
| Main10 verified | **yes** | yes |
| Tier | `cinema` | `cinema` |
| Camera session | HLG10 at 3840×2160, 748 ms | not run |
| Encode session | 4K Main10 HLG, 299 ms | 4K Main10 HLG, 348 ms |

Both devices are cinema-capable and disagree completely about whether they can
be trusted to say so. That is why the database stores the claim and the
verification as separate fields rather than collapsing them into one flag.

### Probe refinements this report suggests

- `display.refresh_hz` reported only the active mode (60 on this tablet, and
  120 on the POCO when it happened to be in its high-rate mode). Schema 1.2.0
  adds `max_refresh_hz`; the POCO now correctly reports 60 active / 120 maximum.
- The tablet offers no high-speed streams at all, so 120/240 presets correctly
  report `no_stream_config` rather than a frame-rate limit. The distinction
  matters for the UI copy.
- The Pad confirms the build runs on the target API 37, which the POCO
  (API 36) could not.

### Phase 2 control data (schema 1.3.0)

The POCO now reports the full control surface:

| Field | Value |
|---|---|
| AE target fps ranges | `10–10`, `15–15`, `15–20`, `20–20`, `24–24`, `10–30`, `14–30`, `30–30` |
| Exposure compensation | `-24 … +24`, step `1/6` EV (about ±4 EV) |
| AE / AWB lock | available |
| Max regions | AE 1, AF 1, AWB 1 |
| Focus distance calibration | `uncalibrated` |
| Zoom ratio | `1.0 … 10.0` |
| Recommended 10-bit profile | `hlg10` |
| Rotate and crop | `none` only |

**25p cannot be frame-locked on this device.** There is an exact `24–24` range
and an exact `30–30` range, but no `25–25`. Recording at 25 fps is still
possible inside the `10–30` range, but the camera will not hold a fixed 25 fps.
This is exactly the distinction `ae_target_fps_ranges` exists to expose: the
stream map says 25 fps is a supported stream, while the AE ranges say it cannot
be locked.

## Phase 2 verification — preview and manual controls (2026-11-23)

Tested on the POCO X6 Pro 5G (Android 16, MT6897).

| Item | Result |
|---|---|
| Preview path | camera → OES texture → GL shader → `SurfaceProducer` → Flutter `Texture`, live |
| Preview size | 1920×1080 selected from the stream map |
| Rotation | `sensor=90`, `display=ROTATION_90` → 270°; Dart rotates and centre-crops |
| Manual ISO | 400 → 4176 visibly brightens the image and raises noise |
| Manual shutter | 20.00 ms → 14.20 ms applied through the repeating request |
| Manual focus | dioptre slider defocuses and refocuses the lens |
| White balance | mode list populated from `CONTROL_AWB_AVAILABLE_MODES` |
| Background/foreground | camera released on pause, reopened on resume, same process, settings preserved |

### Bugs this test found

1. **Startup probe and preview fought over the camera.** Running the Phase 1
   probe on launch opened camera 0 while preview was opening, which triggered a
   framework `RejectedExecutionException` on `CameraDeviceImpl.onClosed`. The
   probe no longer runs automatically; it belongs behind an explicit action.
2. **The deprecated `openCamera(handler)` overload tears down its own executor
   on close**, producing the same crash. Switched to the `Executor` overload
   with the module's own executor.
3. **Preview was rotated 90° and pillarboxed.** Fixed by exposing
   `previewRotationDegrees` in the state and using `RotatedBox` plus a
   `BoxFit.cover` crop in Dart.

> The Pad 3 was offline during Phase 2 verification (unstable USB and wireless
> adb). Preview and manual controls are proven on the POCO only. The Pad must
> still pass the same checklist before Phase 3 recording work is signed off.

## Phase 3 verification — 8-bit HEVC + AAC recording (2026-11-23)

Tested on the POCO X6 Pro 5G (Android 16, MT6897).

| Item | Result |
|---|---|
| Container | MP4, 5.01 s, 7,512,802 bytes |
| Video | HEVC **Main**, 1920×1080, ~30 fps, **11.98 Mbps** |
| Audio | AAC **LC**, 48 kHz, stereo, 192 kbps, non-silent |
| Content | ffmpeg-extracted frame matches the live preview |
| Storage | app-internal `files/recordings/` (MediaStore pending) |
| Source of frames | the same GL pipeline as preview, rendered a second time |

### Bugs this test found

1. **MediaTek ignored `KEY_BIT_RATE`.** The first clip was 141 MB for 8 seconds
   — 150 Mbps for a requested 12 Mbps — because the encoder defaulted to a
   quality mode. Setting `KEY_BITRATE_MODE = BITRATE_MODE_VBR` brought it to
   11.98 Mbps.
2. **The video content was garbage.** The EGL config lacked
   `EGL_RECORDABLE_ANDROID`, so the encoder received uninitialised buffers
   (a bright green/blue noise frame). Adding the attribute, with a fallback if
   unsupported, produced correct images.

Both are exactly the class of vendor-HAL problem the probe-first approach was
built for, and neither is discoverable without recording on real hardware.

## 10-bit HLG attempt — rejected by evidence (2026-11-23)

Requesting Main10 with BT.2020/HLG at 1080p on the POCO produced a file whose
MediaCodec **output format claimed** `profile=2` (Main10) and `transfer=7`
(HLG), but ffprobe reports the truth:

| Field | Actual bitstream |
|---|---|
| profile | **Main (8-bit)** |
| pix_fmt | `yuvj420p` (full range) |
| color_space / transfer / primaries | `bt2020nc` / `arib-std-b67` (HLG) / `bt2020` |

The encoder reported Main10 and emitted Main. Tagging that footage as HLG would
be false advertising, so the HLG path is disabled (`hlgEnabled = false`) and
recording stays 8-bit SDR, verified as `Main` / `yuv420p` / `bt709` / TV range.

### Confirmed with bitstream verification (prober 1.4.0)

A full probe run now reports:

| Check | Result |
|---|---|
| `hevc_main10_encode` | **fail** — `profile_idc=1` on every codec, while each header claimed `profile=2` |
| `camera_hlg10_session` | pass at 3840×2160 |
| Tier | **`standard`** — missing `hevc_main10_encoder` |

So the camera genuinely produces an HLG10 stream, but the encoder does not
produce Main10, despite advertising it in `MediaCodec.outputFormat`. **The
earlier `cinema` verdict is withdrawn.** The "MediaTek under-reports Main10"
conclusion was wrong: it over-reports it.

This is the strongest evidence yet for the project's rule that a codec's own
claim is not verification — the encoded bytes are.

### Exhaustive Main10 attempt (prober 1.4.0)

Every hardware HEVC encoder was tried at every size the verifier tests:

| Size | c2.mtk.hevc.encoder | OMX.MTK.VIDEO.ENCODER.HEVC | secure variant |
|---|---|---|---|
| 3840×2160 | `profile_idc=1` | `profile_idc=1` | no output |
| 1920×1080 | `profile_idc=1` | `profile_idc=1` | no output |
| 1280×720 | `profile_idc=1` | `profile_idc=1` | no output |

All three claim `profile=2` in `MediaCodec.outputFormat`. None emits a Main10
bitstream. **Main10 is unavailable on this device for surface-input HEVC**;
the POCO X6 Pro is `standard` tier, and HLG recording stays disabled.

The remaining candidate for the cinema tier is the OnePlus Pad 3 (Qualcomm),
which advertised Main10 on six codecs. It must be re-probed with 1.4.0 before
that claim is accepted.

## MediaStore output (2026-11-23)

Recordings now go through `MediaStore.Video` into a public folder rather than
app-internal storage:

| Item | Result |
|---|---|
| Path | `/sdcard/Movies/SlateCam/SlateCam_<timestamp>.mp4` |
| MediaStore row | created with `IS_PENDING=1`, cleared on finalize |
| Stream | HEVC Main 1920×1080, ~12 Mbps, AAC-LC 48 kHz stereo |
| Consecutive recordings | two in a row, both valid and playable |
| Fallback | app-internal `files/recordings/` if the MediaStore insert fails |
| 60 s run (informal) | thermal status `0` before and after; no throttling observed |

### Bugs found while wiring it

1. **Reading `csd-0` for the SPS check consumed the buffer** the muxer needed,
   so the video sample entry had no codec config and ffprobe reported
   `codec_name=unknown`, 0×0. Reading a `duplicate()` fixes it.
2. **The audio thread started before `running = true`**, so its loop exited
   immediately and every clip had a silent AAC track.
3. **Detaching the encoder surface left no current EGL drawable**, which made
   the *next* recording produce zero frames. Rebinding the preview surface
   after destroy fixes consecutive clips.

## Phase 4 verification — profiles and zebra (2026-11-23)

| Item | Result |
|---|---|
| `standard` | passthrough |
| `slatelog` | flat look, shadows lifted (0.09 toe), highlights held |
| `mono` | grayscale, correct exposure |
| Zebra | red diagonal stripes above threshold, **preview only** |
| Switching | applied via launch extras (`--es profile`, `--ez zebra`) |
| Log maths | unit-tested: 0 → 0.09, 18% → 0.40, 100% → 0.754, round-trip |

### Findings

1. **The first log curve crushed shadows.** `0.75 + 0.1415 * log2(x)` sends
   everything below ~2.5% linear to pure black, which looks wrong on a dark
   scene. Adding a 0.07 toe (`log2(x + 0.07)`) lifts black to 0.09 and keeps the
   18%/100% anchors.
2. **Zebra looked broken until the threshold was lowered for the test.** At 0.7
   and 0.9 the dark scene had no qualifying pixels. Verified at 0.35; the
   production default stays 0.7.
3. **The in-app profile dropdown did not open under `adb input tap`.** The
   control is visible and reachable by a real finger, but automated taps on the
   scrollable panel did not register. Profiles were verified via launch extras
   instead; the interaction needs a manual pass.

## Phase 4 tools — LUT, histogram, waveform (2026-11-23)

| Item | Result |
|---|---|
| GPU LUT | 17³ warm/cool LUT packed into a 2D texture, sampled in the shader |
| Histogram | 64-bin luma, from a 160×90 FBO readback, ~5 updates/s |
| Waveform | 64×16 luma heatmap, same readback |
| Overlays | zebra/histogram/waveform are preview-only; recordings get none |
| Verification | `--es profile warm` shows cooler shadows/warmer highlights; histogram and waveform update with the scene |

### Findings

1. **The first LUT upload produced a black preview.** `setLut` ran before the
   EGL context existed (profiles are applied at bridge install, before the
   camera opens), so texture creation silently failed and the shader sampled an
   incomplete texture. The renderer now parks the LUT and uploads it once
   `start()` has a context.
2. **The waveform's horizontal axis is sensor-oriented**, because the analysis
   pass renders the unrotated camera buffer while the preview is rotated in
   Dart. The histogram is unaffected; aligning waveform columns to the displayed
   image is a follow-up.

### Phase 4 surface additions (2026-11-23)

| Item | Result |
|---|---|
| False colour | verified: blue shadows, green mids, yellow highlights, red clip |
| Framing guides | thirds + centre cross + 90% safe area, drawn in Dart over the preview |
| Waveform orientation | columns now follow the display rotation; the bright plug appears left in both the preview and the waveform |
| User LUTs | `.cube` files dropped into `files/luts`, scanned on launch and by "Reload LUTs" |
| Analysis overlays | false colour, zebra, histogram and waveform never appear in recordings |

A system file picker for LUTs (SAF) is still a follow-up; the folder scan keeps
the pipeline testable without adding a Flutter plugin.

## Phase 2 completion — capture controls (2026-11-23)

| Control | Default | Device result |
|---|---|---|
| Sharpening (`EDGE_MODE`) | `off` | applied; no HAL-ignored warning |
| Noise reduction (`NOISE_REDUCTION_MODE`) | `off` | applied |
| Anti-banding (`CONTROL_AE_ANTIBANDING_MODE`) | `50hz` if offered, else `auto` | POCO offered no `50hz`, so `auto` |
| Hot pixel / shading / aberration / distortion | `off` where supported | applied silently |

Each request is compared against the first `CaptureResult`, and any mismatch
logs `HAL ignored <mode>`. Nothing was logged on this device, so the defaults
took effect. That check is the important one: several vendors advertise `OFF`
and then ignore it.

The probe now records the available mode lists for all seven controls
(schema 1.4.0), so the UI only offers what the device actually supports.

## Phase 3 audio source (2026-11-23)

The recorder now chooses the cleanest available input, in order:

1. `AudioSource.UNPROCESSED` if `PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED` is true
2. `AudioSource.CAMCORDER`
3. `AudioSource.MIC`

On the POCO X6 Pro the property is false, so the log shows
`audio source=camcorder`. The clip still verifies as AAC-LC, 48 kHz, stereo,
non-silent. No noise suppressor, AGC or echo canceller is attached: they are
destructive and belong in post-processing.

## Phase 5 — shutter angle and gyro (2026-11-23)

| Item | Result |
|---|---|
| Angle math | 180° @ 30 fps = 1/60 s, 360° = one frame, unit-tested |
| Live display | 20.00 ms at 30 fps reads **216°**, which is correct |
| Presets | 45 / 90 / 180 / 270 / 360 in the control panel |
| Gyro capture | 206 samples in a 5 s clip (~41 Hz), `SENSOR_INFO_TIMESTAMP_SOURCE = realtime` |
| Sidecar | `files/gyro/<clip>.gyro.json`, samples as `{t,x,y,z}` |
| Not done | Frame alignment and container embedding; the sidecar is keyed by clip filename |

### Gyro alignment (2026-11-23)

- Sidecar v2 now stores **per-frame rotations**: 206 raw samples integrated to
  145 frame intervals over 4.8 s, matching 30 fps.
- **Clock bug found:** frame timestamps used `System.nanoTime()` while sensor
  events use the `realtime` base (`SystemClock.elapsedRealtimeNanos()`). The two
  bases differed by roughly 2.5 days, so integration would have produced
  meaningless values. The renderer now uses `elapsedRealtimeNanos` for both the
  encoder timestamp and alignment, and `GyroRecorder` warns when the first
  sample and first frame differ by more than a second.
- With the phone stationary, rotations are zero, which is the correct result.

## Phase 5 — dual camera spike (2026-11-23)

| Item | Result |
|---|---|
| Concurrent pair | cameras **0 + 1** (back + front), from `getConcurrentCameraIds` |
| Sessions | both configured at 640×480 simultaneously |
| Check | `concurrent_camera_session: pass` (prober 1.5.0) |
| Probe mode | preview is disabled during a probe run, so the test cannot be fooled by our own camera |

Not done yet: streaming two previews, lens switching, and per-physical streams
via `OutputConfiguration.setPhysicalCameraId`.

### Crash found and fixed

The verifier shut down its callback executor immediately after `device.close()`.
This platform dispatches `onClosed` on that executor, so the task was rejected
and the process died with `RejectedExecutionException`. Both camera verifiers now
defer the executor shutdown by 500 ms. A probe-only launch mode also prevents the
preview from holding a camera while verification runs.
