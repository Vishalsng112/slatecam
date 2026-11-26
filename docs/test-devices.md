<!-- historical: current status lives in docs/roadmap.md; shipped work in CHANGELOG.md -->
> **Historical evidence log.** Current phase status is in
> [`roadmap.md`](roadmap.md); shipped changes are in [`../CHANGELOG.md`](../CHANGELOG.md).
> Older sections may describe superseded states (for example HLG being gated off
> before the 2026-11-23 fix).

# Test devices

Real hardware is the source of truth. Emulators are only for CI smoke tests.
This list is the Phase 1 test bench; every entry must run the prober before its
capabilities are trusted.

## Reference devices

| Device | SoC | Role | Why it matters |
|---|---|---|---|
| POCO X6 Pro 5G | MediaTek Dimensity 8300-Ultra | **Adversarial device** | Xiaomi/MediaTek HALs are the classic case of advertised capability that does not survive a real encode. If the prober is too trusting, this device proves it. |
| OnePlus Pad 3 | Qualcomm Snapdragon 8 Gen 3 | **Reference device** | Cleaner HAL behaviour and a different vendor stack (Qualcomm `c2.*` encoders) from the MediaTek path. Also catches tablet-specific layout and camera-set assumptions. |
| Xiaomi Pad 6 | Qualcomm Snapdragon 870 | **Second tablet / 8-bit-only** | HyperOS/MIUI stack with no `DYNAMIC_RANGE_TEN_BIT` and no 4K 4:3 stream; proves the probe gates legacy/8-bit tablets without device branches. |

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

## Focus peaking (2026-11-23)

Luma-gradient edge detection runs in the preview shader and marks edges red.
Sensitivity (threshold) and strength (opacity) are uniforms driven from the
control panel, and peaking is gated by the overlay flag so it never reaches a
recording. At 85% sensitivity the POCO marks the bottle, monitor bezel and shelf
edges clearly; lower sensitivity removes the noise on textured surfaces.

Temporal decay (how long a peak lingers after focus moves) is not implemented;
it needs a ping-pong mask buffer and is recorded in the backlog.

## Prober 1.7.0 — per-size 10-bit verification and hvcC parsing (2026-11-23)

Both test devices were re-probed with schema **1.8.0** after two changes to the
verification path: the 10-bit checks now run at both UHD and 1920×1080, and the
SPS parser reads profile **and bit depth** from Annex B, 4-byte length-prefixed
and `hvcC` records.

### OnePlus Pad 3 — `cinema`, both sizes verified

| Check | Result |
|---|---|
| `hevc_main10_encode` (UHD) | **pass** — `c2.qti.hevc.encoder`, `sps_profile=2`, `sps_bit_depth=10` |
| `hevc_main10_encode_fhd` | **pass** — same codec, `sps_profile=2`, `sps_bit_depth=10` |
| `camera_hlg10_session` (UHD) | **pass** — camera 0 |
| `camera_hlg10_session_fhd` | **pass** — camera 0 |
| `concurrent_camera_session` | **pass** — cameras 0+1 |
| Tier | **`cinema`** |

New operational data: HEVC width/height range 96–8192 with 2-pixel alignment,
bitrate 1–160 Mbps, P010 buffer input, HDR editing unsupported, UHD 120.9 fps
(informational ceiling), gyro 200 Hz (5 ms), built-in mic 8–48 kHz mono/stereo,
RAW 4160×3120, max frame duration 260 ms, lens pose and intrinsics reported.

### POCO X6 Pro 5G — `standard`

| Check | Result |
|---|---|
| `hevc_main10_encode` (UHD) | **fail** — `profile_idc=1`, `bit_depth=8` on every hardware encoder |
| `hevc_main10_encode_fhd` | **fail** — same |
| `camera_hlg10_session` (UHD/FHD) | **pass** — the camera side is capable |
| `concurrent_camera_session` | **pass** |
| Tier | **`standard`** (missing `hevc_main10_encoder`) |

The improved parser makes the failure evidence explicit: the codec header claims
`profile=2`, the SPS says Main profile with 8-bit depth. The earlier conclusion
("over-reports Main10") stands, now with bit depth in the report.

### Findings

1. **`csd-0` is not always a raw NAL stream.** MediaCodec normally returns an
   `hvcC` configuration record, which the old parser could not read at all
   (`profile_idc` came back null). The parser now handles hvcC, Annex B and
   length-prefixed data, and requires 10-bit depth in the SPS for a Main10 pass.
2. **`VideoCapabilities.isSizeSupported()` is unreliable.** The POCO's encoder
   reports 16-pixel height alignment, which excludes 1080, while surface
   recording at 1080p works. The encoder section now reports the width/height
   ranges, alignment and a per-size `aligned` flag instead of silently dropping
   the size. A size in range but out of alignment is *claimed*, not verified.
3. **The 10-bit production recorder is not ready.** On the Pad, the encoder's
   `outputFormat` at `INFO_OUTPUT_FORMAT_CHANGED` contains no `csd-0`, so the
   muxer writes a video track with no codec configuration (ffprobe:
   `codec_name=unknown`). The prober proves the hardware path; the recorder's
   HLG route needs its own fix before 10-bit recording is enabled. This is a
   pipeline bug, not a capability verdict, and the runtime gate stays closed
   with that reason.
4. **Capability is gated per size and per camera.** A UHD pass no longer marks
   FHD presets verified, and a session verified on camera 0 does not credit
   camera 1. The report's per-camera preset map is the source of truth.

## Lens roles and hidden cameras — prober 1.8.0 / schema 1.9.0 (2026-11-23)

The prober now derives relative lens roles from focal length + sensor
geometry, using CameraX's convention (`intrinsic_zoom_ratio` 1.0 = default,
< 1 = wider, > 1 = narrower). It also scans numeric IDs beyond `cameraIdList`
and records the OEM auxiliary-camera policy.

### OnePlus Pad 3 — two lenses exposed, none hidden

| ID | Facing | Role | Basis | 35 mm eq | Zoom | Sensor |
|---|---|---|---|---|---|---|
| 0 | back | default | only_camera | 25.2 mm | 1.000 | 4160×3120 |
| 1 | front | default | only_camera | 26.0 mm | 1.000 | 3264×2448 |

`vendor.camera.aux.packagelist` contains only `org.codeaurora.snapcam`, but no
hidden ID was readable or openable on this build. The Pad genuinely exposes two
cameras.

### POCO X6 Pro 5G — three rear lenses, four hidden IDs openable

Only IDs 0 and 1 are listed, but IDs 2–5 are readable **and openable** by this
package even though `vendor.camera.aux.packagelist` lists only Xiaomi
test/factory apps. That is build-specific evidence, not an API guarantee.

| ID | Exposed | Openable | Facing | Role | Basis | 35 mm eq | Zoom | Sensor |
|---|---|---|---|---|---|---|---|---|
| 0 | yes | — | back | default | fov_ratio | 25.1 mm | 1.000 | 4624×3472 |
| 1 | yes | — | front | default | only_camera | 24.3 mm | 1.000 | 2304×1728 |
| 2 | no | **yes** | back | **ultrawide** | fov_ratio | 15.6 mm | **0.621** | 3264×2448 |
| 3 | no | **yes** | back | **macro** | fixed_focus_small_sensor | 24.7 mm | 0.984 | 1600×1200 |
| 4 | no | **yes** | back | default | fov_ratio | 25.1 mm | 1.000 | 4624×3472 |
| 5 | no | **yes** | back | default | fov_ratio | 25.1 mm | 1.000 | 4624×3472 |

- ID 2 is the 8 MP ultra-wide: 15.6 mm equivalent, 0.621× the main lens.
- ID 3 is the 2 MP macro: fixed focus, 1.9 MP, normal field of view.
- IDs 4 and 5 are logical multi-camera groups over physical cameras `0 + 2`,
  with zoom-ratio ranges 0.6–10.0 and 1.0–10.0. These are the correct cameras
  to open for a continuous ultra-to-max-zoom experience; ID 2 is the
  ultrawide-only stream.
- There is no telephoto lens on this device. Max zoom is digital above 1×.

### Contract for a future zoom UI

- Use `role` + `intrinsic_zoom_ratio` for the lens stops, and 35 mm-equivalent
  focal length for the labels (15.6 / 25.1 mm, not "0.6×").
- Offer a hidden lens only when the report says `openable: true`; a readable ID
  is not enough.
- Prefer a logical camera with a zoom-ratio range that spans the requested
  range (POCO ID 4) instead of opening several physical IDs in sequence.
- Keep `exposed`/`openable` per device in the report: a ROM update can remove
  the hidden IDs without notice.

## Zoom plugin — lens switching and continuous zoom (2026-11-23)

`ZoomPlugin` (`lib/src/ui/zoom_plugin.dart`) sits above the bottom dock and is
driven entirely by the probe report. It exposes lens chips and a per-camera zoom
slider through the new Pigeon calls `selectLens(id)` and `setZoom(ratio)`.

### OnePlus Pad 3

| Item | Result |
|---|---|
| Lens chips | one: `1.0× · 25mm` (the only exposed back lens) |
| Slider | 1–10×, applied through `CONTROL_ZOOM_RATIO` |
| 10× test | preview visibly crops/zooms, readout `10×`, no error |
| Hidden lenses | none offered |

### POCO X6 Pro 5G

| Item | Result |
|---|---|
| Lens chips | `0.6× · 16mm` (ultra-wide, hidden), `1.0× · 25mm` (main), `MACRO` (hidden) |
| Ultra-wide selection | opens camera **2**; header becomes `0.6× · 16mm · hidden`; HAL logs `Primary camera id: 2`; FOV visibly widens |
| Macro selection | opens camera **3**; header `MACRO · hidden`; close-focus preview |
| Recording on macro | HEVC Main 1920×1080 30 fps, 12.17 Mbps, AAC-LC, **BT.709**; file playable |
| Slider | continuous per-camera zoom, readout normalised to the default lens |

Hidden chips carry a small "eye-off" mark and a tooltip; they appear only when
the probe marked the lens `openable`. This is still build-specific evidence —
a ROM update can remove them.

The macro recording also confirms the explicit SDR colour signalling: the POCO
clip is tagged `bt709` / `bt709` / `bt709` instead of the vendor default.

## Orientation and aspect fix (2026-11-23)

**Symptom:** the viewfinder looked horizontally stretched and the recorded file
did not match the preview.

**Measured facts** (both devices, landscape, back camera):

| Field | Value |
|---|---|
| Camera stream | 1920×1080 |
| Preview EGL surface | 1920×1080 |
| SurfaceTexture transform | 90° reflection matrix |
| Display rotation | `ROTATION_90`; preview rotation value 270 |
| Recorded buffer | 1920×1080, rotation metadata 90 (sideways pixels) |
| Preview path | GL matrix + Flutter `RotatedBox` 270 + 16:9 `cover` |

The recorder wrote sideways pixels and relied on MP4 rotation metadata, while
the preview applied another rotation and crop in Flutter. Players and the
viewfinder therefore disagreed, and the preview was stretched.

**Fix (ADR 0006):** rotate in GL from sensor + display orientation, encode
upright pixels with no rotation hint, and stop rotating in Flutter. The
viewfinder uses the capture aspect and `BoxFit.contain`.

| Device | Preview | Encoded raw frame | Result |
|---|---|---|---|
| Pad 3 back | upright, correct proportions | 1920×1080 upright, no `rotation` tag | pass |
| POCO back | upright, correct proportions | 1920×1080 upright, no `rotation` tag | pass |
| POCO front | upright | — | pass (not mirrored) |

The front camera uses sensor 270° in landscape, which is why the back-camera
rotation rule alone put it sideways; the sensor-based rule covers both.

### Follow-up

- Add a recording-aspect selector (16:9 / 4:3) that picks a matching stream and
  encoder size. The transform is already aspect-preserving.
- Mirror the front *preview* only if desired; recordings stay unmirrored.
- Re-run this test with the tablet rotated 180° (`ROTATION_270`) to exercise the
  `sensor + 180°` branch.

## Phase 3 completion on the Pad 3 (2026-11-23)

Only the OnePlus Pad 3 was connected for this pass; the POCO cross-device check
is recorded in the later POCO section.

### 10-bit HLG recording

| Item | Result |
|---|---|
| Encoder | `c2.qti.hevc.encoder` (same codec the prober verified) |
| Config | `csd-0` absent from `outputFormat`; recorder built `hvcC` from VPS/SPS/PPS in the first access unit |
| Encoder surface | non-recordable RGBA8888; the 10-bit RGBA config is offered by EGL but every swap fails with `EGL_BAD_SURFACE` |
| Container | HEVC **Main 10**, `yuv420p10le`, BT.2020 NC, **arib-std-b67** (HLG), TV range |
| Duration/frames | 60 s / 1859 frames at 1080p30 |
| Bitrate | 15.06 Mbps |
| Thermal | status 0 before/after; battery 31.3 °C; no dropped frames (1859/1859) |

The SPS guard still runs on the real bitstream; HLG is only tagged when the
encoded SPS is Main10 at 10-bit. The GL intermediate is RGBA8888 today, so the
*encoded stream* is 10-bit but the source precision is 8-bit until a true 10-bit
EGL/FBO path exists (quality follow-up).

### Recording modes

Probe-driven: size must exist in the camera stream map, fps must have an exact
AE range, and 10-bit must be `verified: pass` for that size/fps.

| Mode | State on Pad 3 |
|---|---|
| 1920×1080 · 24p · 16:9 · 10-bit HLG | enabled, recorded (Main10, avg 24/1 over 10 s) |
| 1920×1080 · 30p · 16:9 · 10-bit HLG | enabled, 60 s clip verified |
| 1920×1080 · 24p/30p · 16:9 · 8-bit SDR | enabled, 60 s at 24p: 1488 frames, 11.3 Mbps |
| 1440×1080 · 24p/30p · 4:3 · 8-bit SDR | enabled, 4:3 clip is 1440×1080 Main BT.709 |
| 1920×1080 · 25p | disabled — no `25–25` AE range; the stream supports 25 but cannot be frame-locked |
| 1440×1080 · 10-bit | disabled — 4:3 10-bit is not probe-verified yet |
| 1280×720 · 16:9 · 8-bit | enabled |

Mode changes resize the producer, the camera stream and the encoder together;
the transform preserves aspect, so 4:3 is pillarboxed in the landscape viewfinder
rather than stretched.

### Remaining before the phase can be signed off across devices

1. Re-run this checklist on the POCO; it must stay 8-bit because its SPS fails
   Main10.
2. 20-minute thermal run (60 s is clean).
3. Probe and enable 4:3 10-bit if the hardware verifies it.
4. Optional quality: true 10-bit GL intermediate.

## Phase 5.5 foundation — shoot profiles and Movie/Vlog mode (2026-11-23)

Tested on the Pad 3 only; the POCO lens set is the real multi-lens exercise and
is still pending.

### Per-camera capability modes

- Modes are built from live `CameraCharacteristics` per camera: size, exact AE
  frame-rate range, 10-bit claim, AF modes and zoom range.
- 10-bit is enabled only when the probe verified that camera at that size/rate.
- Switching from the back lens (30p 10-bit) to the front while idle
  automatically fell back to **1920×1080 30p 8-bit** instead of sending the
  front camera a 10-bit request it had not verified.
- Attempting an invalid configuration now produces a reason, not a failed
  camera session.

### Movie/Vlog common mode

The shoot set is every probe-visible, openable, non-macro lens (Pad: back +
front). On the Pad the strict common mode is **1920×1080 30p 8-bit**, because the
front camera's 10-bit is `not_run` in the probe report.

| Test | Result |
|---|---|
| Back → front during recording | accepted; recorder/encoder/audio/gyro stayed alive |
| Front → back during recording | accepted; clip continued |
| Long switch clip | 229.6 s, 6876 frames, 1920×1080 Main 8-bit, one file |
| Short regression clip | 7.87 s, 236 frames, one file |
| Crashes / session failures | none |
| 4K single-camera | 3840×2160 30p **Main10 HLG**, 111 frames / 3.7 s, 49.97 Mbps |

### Autofocus and colour

- AF modes are read per camera; a lens without AF is forced to manual and the
  AF action is disabled in the focus tab.
- On every lens switch the bridge refreshes the device snapshot and applies that
  camera's calibrated input profile before the shared creative profile/LUT.
  No measured calibration matrices ship yet, so colour parity currently means
  identity for every lens.

### Remaining for Phase 5.5

1. Probe and verify 10-bit per lens (including hidden/openable ones) and add
   4:3 checks; then the common movie mode can be 10-bit where all lenses qualify.
2. Shoot-set editor (include/exclude lenses, save profiles) instead of the
   current all-non-macro default.
3. Measured pairwise ColorChecker matrices in the device database.
4. POCO cross-device pass over main, ultra-wide and macro lenses (completed in
   the later POCO section).
5. Optional seamless pre-open switch to remove the short visual gap.

## Phase 3 + 5.5 on the POCO X6 Pro 5G (2026-11-23, build 2037)

### Phase 3 — recording

| Item | Result |
|---|---|
| Probe | `standard`; `hevc_main10_encode` and `_fhd` fail with `profile_idc=1`, `bit_depth=8`; HLG10 sessions pass; hidden IDs 2–5 openable |
| 10-bit gate | disabled with the quirk reason `poco-duchamp-force-8bit` plus the SPS verdict |
| Mode list | 4K 24/30, 1080p 24/30, 4:3 1440×1080 24/30, 720p 24/30, all 8-bit; 25p disabled (no exact AE range) |
| 4K 30p | 3840×2160 HEVC **Main**, 117 frames / 3.97 s, 40.2 Mbps, BT.709 |
| 4:3 30p | 1440×1080 HEVC **Main**, BT.709 |
| 1080p30 60 s | 1859 frames / 61.96 s, 11.99 Mbps, thermal 0, battery 39.8 °C |

No 10-bit/HLG clip was produced; the device stays SDR by report and quirk, as
intended.

### Phase 5.5 — movie mode

| Item | Result |
|---|---|
| Shoot set | main `0`, ultra-wide `2` (hidden/openable), front `1`; macro `3` excluded |
| Common mode | 1920×1080 30p 8-bit (10-bit blocked for the set) |
| Main → ultra-wide | accepted mid-recording; header `0.6× · 16mm · hidden`; REC continued |
| Macro while recording | rejected: “lens 3 is not part of the movie lens set”; REC continued |
| Ultra-wide → front | accepted mid-recording; header `1 · front`; REC continued |
| Clip | one file, 125.3 s, 3733 frames, 1920×1080 HEVC Main 8-bit |
| Crashes / session failures | none |

The POCO’s per-camera difference is visible in the UI: 4K modes are available on
the main sensor, the macro is not part of the movie set, and all 10-bit modes
carry the quirk/verdict reason.

## Phase 5.5 per-lens 10-bit / 4:3 verification (2026-11-23, prober 1.9.0)

Schema 1.10.0 adds 4:3 presets and a session verdict per lens. The prober now
runs the encoder at UHD/FHD/1440×1080 and configures HLG10 on each
exposed/openable lens at every supported 10-bit size.

### OnePlus Pad 3

| Check | Result |
|---|---|
| Encoder UHD/FHD/4:3 | pass — `profile_idc=2`, `bit_depth=10` at all three |
| Back camera | 10-bit verified at 3840×2160, 1920×1080, 1440×1080 |
| Front camera | 10-bit verified at 1920×1080 and 1440×1080 (no UHD stream) |
| Tier | `cinema` |
| Movie mode | 10-bit HLG common mode enabled at 1080p and 4:3; 4K disabled because the front lens has no UHD stream |
| Movie switch test | back → front kept one **Main10/HLG** clip, 166.6 s, 4990 frames |
| 4:3 10-bit | preset verified; available in single and common movie mode |

### POCO X6 Pro 5G

| Check | Result |
|---|---|
| Encoder UHD/FHD/4:3 | fail — SPS `profile_idc=1`, `bit_depth=8` |
| HLG10 sessions | pass on main, front, ultra-wide and macro at supported sizes |
| 10-bit presets | remain `not_run`; no lens can be promoted |
| Movie mode | stays 8-bit; goal is a common mode that honest hardware can satisfy |
| Tier | `standard` |

The Pad demonstrates the target behaviour: a 10-bit movie profile is offered
only after *every* lens in the set is verified at that size, and a switch keeps
one encoded clip alive.

## Phase 5.5 shoot-set editor (2026-11-24, build 2040)

- Shoot set is every probe-visible, openable, non-macro lens, de-duplicated by
  facing/role/focal length.
- Each lens is a selectable chip in Capture → Shoot set. Selection persists in
  the `shoot-profile` preferences across app restart.
- Movie mode requires at least two selected lenses; dropping to one returns to
  Single mode with “movie mode needs at least two selected lenses”. The editor
  remains visible so the set can be repaired.
- Pad: back 25 mm + front 26 mm; deselecting front triggered the min-two guard,
  re-selecting restored 10-bit HLG movie mode.
- POCO: back 16 mm (ultra-wide), back 25 mm, front 24 mm. Deselecting the
  ultra-wide left main + front, and the selection survived a force-stop/restart.

## Phase 5.5 Control Console and seamless switch (2026-11-24, build 2054)

### Control Console

- The six-tab deck is replaced by one console. RECORD/STOP, the camera stepper,
  the mode stepper and Single/Movie stay fixed while the cards scroll.
- Pad 3: console at 760 lp in two columns; camera stepper switched back→front
  and back again without opening a menu or scrolling.
- Movie-mode pass: tap Movie, tap RECORD, tap the camera stepper once, tap STOP.
  The same sequence is repeatable from a fixed screen position, which was not
  possible with the tab + dropdown layout.

### Viewfinder tap-to-focus and zoom (Pad 3, build 2076)

### Tap-to-focus

- SAF active: tapping the cup moved the focus ROI; `SlateCamFocus` re-searched
  and locked at 2.186 D (score 0.112) from 5.586 D. The reticle was captured at
  the tapped point.
- CAF active: the same tap produced
  `android.control.afRegions [2882 1829 3162 2109] [1000]` and
  `android.control.afTrigger` returned to `IDLE` after the one-shot trigger.
- The tap is mapped through the live `SurfaceTexture` transform and the
  stream-aspect/zoom crop, not a fixed sensor rotation.

### Pinch zoom

The preview uses one Flutter gesture area for tap and two-finger scale; the scale
path clamps `startZoom × scale` to the camera range and calls `setZoom`. The ADB
test harness can inject single taps but not multi-touch, so this pass verifies the
tap path on hardware and the pinch path by construction; the on-screen zoom
slider remains the precision fallback.

### Xiaomi Pad 6 — new test device (2026-11-24, build 2086)

- Device: Xiaomi Pad 6 (`23043RP34I`, `pipa`), Android 14 / SDK 34,
  Snapdragon 870 (SM8250), 1800×2880 at 400 dpi.
- Probe: **schema 1.11.0 / prober 1.10.0**, 381–445 ms. Tier **standard**,
  missing `dynamic_range_ten_bit`.
- Back camera 0 (4208×3120):
  - UHD 16:9 8-bit 24/25/30 supported; UHD 16:9 10-bit rejected because the
    camera does not advertise `DYNAMIC_RANGE_TEN_BIT`.
  - 1080p 16:9 and 1080p 4:3 (1440×1080) 8-bit at 24/25/30.
  - 4K 4:3 (3840×2880), 4096×3072 and 2880×2160 are **not offered**; the
    corresponding presets are correctly `supported=false`, so the 4:3 chip is
    inert at 4K and active at 1080p without a device branch.
- Recorded and bitstream-checked:
  - 4K 16:9: `3840×2160 Main yuv420p bt709/bt709`, 30 fps, 5 s, FP16
    intermediate `RGBA16F`.
  - 1080p 4:3: `1440×1080 Main yuv420p bt709/bt709`, 30 fps, FP16
    intermediate `RGBA16F`.
  - 1080p 16:9: `Main yuv420p bt709/bt709`.
- Focus/metering stable at the delivery analysis signal (`SlateCamFocus`
  locked, meter within ~0.01 EV of target).

#### Bugs this device exposed

1. **Fresh-install crash before the first probe.** With no probe report there
   are no eligible lenses, so `activeProfileId()` called `.first()` on an empty
   list and the app died on the first state push. It now returns the stored
   active profile only when it still exists, otherwise the first available
   profile or an empty id.
2. **Probe aborted on Android 14.** `CameraCharacteristics
   .COLOR_CORRECTION_AVAILABLE_MODES` is in the compile SDK but absent from the
   Android 14 framework, so every camera probe threw `NoSuchFieldError`. The
   key is now read reflectively and an absent key reports an empty list instead
   of failing the whole camera.

### Scene-linear colour shader and routing (Pad 3, build 2084)

| Check | Result |
|---|---|
| HLG 1080p | Main 10, `arib-std-b67`, BT.2020, 120 frames / ~4 s with FP16 intermediate |
| HLG 4K | 3840×2160 Main 10, `arib-std-b67`, BT.2020, 120 frames / ~4 s |
| SDR 1080p | Main, `bt709`, BT.709 primaries, 120 frames / ~4 s |
| Preview | normal SDR image; HLG preview uses HLG inverse + BT.2020→BT.709 |
| SlateLog | preview visibly flat/log; encoded clip remained standard HLG Main10 |
| Analysis/metering | focus locked, meter stable (0.424 luma, compensation 1) |
| FP16 | encoder intermediate 1920×1080 / 3840×2160 RGBA16F |
| Diagnostics | `profile … log=true`, `preview log route=true`; no shader compile errors |

True 10-bit source is still gated by the RGBA8888 encoder surface; the FP16
intermediate is present and dithered.

### FP16 encoder intermediate (Pad 3, build 2081)

| Check | Result |
|---|---|
| Driver support | `GL_EXT_color_buffer_half_float` + `GL_OES_texture_half_float`; `RGBA16F` FBO complete |
| 1080p HLG | `FP16 encoder intermediate 1920x1080 (RGBA16F)`, 120 frames, clip `1920x1080 Main 10 yuv420p10le` |
| 4K HLG | `FP16 encoder intermediate 3840x2160 (RGBA16F)`, 120 frames, clip `3840x2160 Main 10 yuv420p10le` |
| Frame cadence | 60 frames per ~2 s at 30 fps during both clips; no swap errors |
| Orientation | recorded 4K frame upright and matching the viewfinder |
| Fallback | direct encoder path retained when FP16 is unavailable or the FBO is incomplete |

The encoder surface remains RGBA8888, so this is a processing-precision and
dithering step, not true 10-bit delivery. True 10-bit still needs a 10-bit/P010
encoder input path.

### Colour architecture foundation (Pad 3, build 2080)

- Probe report re-run on the Pad: **schema 1.11.0 / prober 1.10.0**.
- Both back and front cameras advertise:
  - `tone_map_modes`: `contrast_curve`, `fast`, `high_quality`
  - `max_tone_map_curve_points`: `512`
  - `color_correction_modes`: `transform_matrix`, `fast`, `high_quality`
- `gamma_value` is not advertised, which supports the decision not to force a
  custom tone-map/gamma mode. A manual CCM/WB path is advertised but remains
  gated behind the future scene-linear phase.
- The app starts normally after the probe; SAF remained locked and the probe
  report was written to `files/probe.json`.
- Calibration data remains empty, so per-lens WB/CCM/shadow defaults are
  identity. The schema and matching are covered by JVM tests.

### Colour science and viewfinder resolution (Pad 3, build 2079)

| Check | Result |
|---|---|
| Colour science card | Exposure, Contrast, Saturation, Temperature, Tint and Reset render in the console |
| Warm trim | dragging Temperature persisted 0.636 and visibly warmed the preview; reset returned it to neutral |
| Trim scope | applied through the shared shader, so preview and recording match |
| Preview 720 | selecting 720 on a 1920×1080 mode logged `output retargeted 1280x720`; the viewfinder showed `Preview 1280×720 · stream 1920×1080` |
| Recording after preview resize | 1920×1080 Main10 HLG clip recorded while the preview was 1280×720 |
| Persistence | `preview_quality`, colour trim values restored after restart; restored to Full/neutral for hand-off |

The preview output is a GL downsample of the capture stream; the camera session
and encoder are not reopened. Colour tuning is unit-tested in `ColorScienceTuning`
(exposure stops, neutral white balance, warm/magenta direction, clamping).

### Software focus stability (Pad 3, build 2078)

- SAF started by default (AF OFF) and locked at 4.90 D. The normalised focus
  score stayed in 0.195–0.200 for 15 s with no re-search; P metering held at
  0.425 luma / compensation 1.
- Tapping the cup re-ran the search and locked again; no post-tap hunting.
- Hardware tap-to-focus uses a 20% AF region and no AF trigger in CAF/CAF-P
  (the continuous mode follows the region itself); one-shot triggers remain for
  AF/Macro.
- The Laplacian score is divided by mean luma, so the 5 Hz AE compensation no
  longer looks like a focus change. The engine also EMA-smooths the score,
  requires a 0.5% improvement, waits 800 ms after a lock, and uses wider
  trigger/hold defaults.

### Software focus engine and scenarios (Pad 3, build 2075)

`SlateCamFocus` logs the score, commanded diopters, target, search/locked state
and the two normalized settings.

| Check | Result |
|---|---|
| SAF availability | back lens reports `minimumFocusDistance=10.0`, SAF shown |
| Startup restore | SAF restored from prefs; `CONTROL_AF_MODE [OFF]` |
| Initial acquisition | lens seeded at its reported 4.217 D, stepped to 4.430 (score 0.0915), reversed to 4.111 (0.0847), locked at 4.217 (0.0880) |
| Transition speed effect | Vlogging (0.75) used 0.65 D steps and locked in ~2.5 s; Cinematic (0.15) used 0.49 D steps about 3-4 s apart |
| Scenario defaults | Interview 25/35, Vlogging 80/75, Wedding 50/50, Cinematic 20/15 |
| Persistence | Vlogging 80/75 and SAF survived force-stop/restart |
| Focus lock | `android.lens.focusDistance` matched the commanded 4.217 D |
| Metering interaction | P meter stable at 0.463 luma / compensation 0 while SAF was locked; 100% speed no longer oscillates after the 5 Hz/one-step P throttle |
| Fixed-focus lens | SAF/scenario section hidden when no focus range is reported |

The focus search and the lens ramp are both time-driven; the controller waits
for the ramp to settle (plus 120 ms) before treating a sharpness sample as valid.
Without that, the lens result lag stalled the request at an intermediate
position. The final controller no longer lets the reported lens distance
overwrite the commanded distance after the first sample. The first result seeds
the engine at the real lens position; the manual focus slider and the hardware
focus modes clear the persisted SAF flag so a later restart does not override
the operator's choice.

### Exposure metering patterns and speed (Pad 3 + POCO, build 2068)

The meter uses the GPU luma grid, not Camera2 AE regions. Logcat tag
`SlateCamMeter` prints the measured/target/EV and applied state once per second.

| Check | Pad 3 | POCO X6 Pro |
|---|---|---|
| Default pattern | Multi | Multi |
| Speed | 50% | 50% |
| P compensation range | ±18 steps at 1/6 EV | range reported by HAL |
| Multi convergence | 0.506 luma / EV −0.17 / comp 1 -> 0.449 / EV 0.00 / comp 2 -> 0.467 / EV −0.05 (dead band) | 0.434 luma / EV 0.05 / comp 6 (stable) |
| Spot | 0.435 luma / EV 0.05 / comp −2 (stable) | — |
| Highlight | protect-only; flat scene held at compensation 0 | — |
| S loop | ISO 100 -> 1600 within 3 s when the scene needed more exposure | — |
| M | speed slider disabled; exposure untouched | — |
| Persistence | Spot selected, restart kept Spot; restored to Multi/50% | Multi/50% |

The P log shows the loop converging in two steps with the dead band stopping at
EV 0.05. The S log shows the same meter raising ISO when the manually set ISO
was too low; the loop is clamped by the sensor's ISO range at the top end.

### Focus modes, OIS and EIS (Pad 3 + POCO, build 2064)

| Check | Pad 3 back | POCO X6 Pro back |
|---|---|---|
| Focus modes advertised | off, auto, macro, continuous_video, continuous_picture, edof | off, auto, macro, continuous_video, continuous_picture |
| Default request | `CONTROL_AF_MODE [CONTINUOUS_VIDEO]` | `CONTROL_AF_MODE [CONTINUOUS_VIDEO]` |
| `AF` chip | `[AUTO]` | `[AUTO]` |
| `MF` chip | `[OFF]`, manual slider appears | `[OFF]`, manual slider appears |
| OIS advertised | `off` only -> control hidden | `off`/`on` -> control shown |
| OIS default | n/a | `LENS_OPTICAL_STABILIZATION_MODE [ON]` |
| OIS off -> on | n/a | `[OFF]` -> `[ON]`; persisted `OFF` across restart |
| EIS default | `VIDEO_STABILIZATION_MODE [ON]` | `VIDEO_STABILIZATION_MODE [ON]` |
| EIS off -> on | `[OFF]` -> `[ON]` | `[OFF]` -> `[ON]` |
| Shading default | `[HIGH_QUALITY]` | `[HIGH_QUALITY]` |

All values were read from `dumpsys media.camera` after each tap. The Pad front
lens advertises only `off` focus modes, so its card shows `MF` alone. OIS and EIS
are requested together on the POCO; no HAL-ignored warning appeared.

### 4K 4:3 and per-sensor vignette correction (Pad 3 + POCO, build 2062)

**4K 4:3 aspect**

| Device | 4K 16:9 | 4K 4:3 selected | Recorded clip |
|---|---|---|---|
| Pad 3 (back) | 3840×2160 · 30p · 10b HLG | 4096×3072 · 30p · 8b SDR | 4096×3072 HEVC Main, 5.73 s |
| POCO X6 Pro | 3840×2160 · 30p · 8b | 2880×2160 · 30p · 8b | 2880×2160 HEVC Main |

- Pad supports 4096×3072 (4:3) and 4160×3120; it does not expose 3840×2880.
- POCO does not expose 4096×3072; 2880×2160 is its first 4K-class 4:3 stream.
- The selector now treats 4K as a family. Tapping 4:3 from 4K no longer drops to
  1080; tapping 16:9 returns to 3840×2160. 10-bit remains disabled for the new
  4:3 sizes until a probe run covers `dci43_*`, `uhd43_*` or `qhd43_*`.
- EGL retarget log: `3840x2160 -> 4096x3072` on Pad and
  `3840x2160 -> 2880x2160` on POCO, so preview and encoder sizes stay aligned.
- The bit-depth row lists only selectable depths. 4K 4:3 shows 8-bit alone
  until a probe report includes its 10-bit preset; 4K 16:9 still shows both.

**Vignette correction**

The controller had forced `SHADING_MODE_OFF` for every sensor. It now defaults
to correction and requests the best advertised mode.

| Check | Result |
|---|---|
| Pad request dump | `android.shading.mode [HIGH_QUALITY]` |
| Pad toggle off | `android.shading.mode [OFF]` |
| Pad toggle on | `android.shading.mode [HIGH_QUALITY]` |
| POCO request dump | `android.shading.mode [HIGH_QUALITY]` |
| Persistence | off -> force-stop/restart -> still `OFF` |
| Fixed-exposure corner/centre luma | 0.284 (off) -> 0.455 (on) |

The Processing card shows a per-sensor **Vignette correction** on/off pair. The
choice is stored per camera ID in `shoot-profile` prefs, so a lens that was
turned off stays off while a different lens starts corrected. If a sensor only
advertises `off`, the card says correction is not offered.

### Preview resize on aspect/resolution change (Pad 3 + POCO, build 2057)

**Symptom:** after switching from 16:9 to 4:3, the viewfinder showed a squashed
square image with a black band on the right. Switching to 4K showed only part of
the frame. Recordings were correct, so preview and recording disagreed.

**Cause:** `SurfaceProducer.setSize` resized the Flutter texture but the GL
renderer kept its existing EGL window surface at the old buffer size. The GL
viewport took the new dimensions, so a 1440×1080 viewport was drawn into the
left 75% of a 1920×1080 surface.

**Fix:** force a fresh producer surface and recreate the EGL window surface on
every mode change (`forcedNewSurface` + `GlPreviewRenderer.updateTarget`).
Logcat now reports the retarget:

| Transition | `output retargeted` |
|---|---|
| 1920×1080 -> 1440×1080 | 1440x1080 |
| 1440×1080 -> 1920×1080 | 1920x1080 |
| 1920×1080 -> 3840×2160 | 3840x2160 |
| 3840×2160 -> 1440×1080 | 1440x1080 |

- Pad: 4:3 now fills the 4:3 viewfinder with no black band; the outlet and cable
  keep their proportions. A 4:3 recording is `1440×1080` Main10 and matches the
  preview framing.
- POCO: the same 16:9 -> 4:3 switch retargets to `1440x1080` and fills the pane.
- Six rapid resolution/aspect cycles on the Pad left the process alive with no
  EGL or ImageReader errors.

Note: the sensor is 4:3 native (`4160×3120` on the Pad). Selecting a 16:9 stream
still crops the sensor vertically; that is camera behaviour, not display
scaling. Preview and encoded framing now agree in every offered aspect.

### 4K/1080 + aspect + circular exposure (Pad 3 + POCO, build 2056)

- Pad: resolution offers only `4K` and `1080`; choosing 4:3 produced
  `1440×1080 · 30p · 10b`, and choosing 4K returned to
  `3840×2160 · 30p · 10b` with the 16:9 circle.
- Pad: P/S/M are circles; selecting M left ISO/shutter manual and the header
  stayed on 4K.
- Focus card and the bottom-dock FOCUS chip are gone; continuous AF continues
  without UI.
- Device card is the last full-width item on both devices.

### Focal-length shoot-set circles (Pad 3 + POCO, build 2055)

- Pad: `25mm` and `26mm` circles; tapping `26mm` removed it from the set
  (`Default · 1`), and the remaining circle stayed selected.
- POCO: `16mm`, `25mm`, `24mm` circles; tapping `25mm` added it back and all
  three showed selected, matching the persisted profile.
- Circles use a 50 lp hit target with a 42 lp visual badge.

### Three-row recording-mode selector (Pad 3 + POCO, build 2054)

- Pad: from 1080p30 10-bit, selecting 4K → 24 fps → 8-bit produced
  `3840×2160 · 24p · 8b SDR`; the mode stepper showed `4K24p 8b`.
- Pad: switching back to Movie selected the common `1080p30 10-bit HLG`
  automatically (4K is not available across both lenses).
- POCO: console uses two columns at its width; the selector shows only
  probe-verified combinations and 10-bit stays disabled by the encoder quirk.

### Condensed circular controls (Pad 3 + POCO, build 2054)

- Fixed header: record circle at (1896,180), S at (2032,180), M at (2144,180),
  B0 at (2280,180), F1 at (2418,180) on the Pad. Movie -> record -> F1 kept one
  clip and the header switched to `F1`; stop produced
  `SlateCam_20261124_022632.mp4`.
- POCO shows the same header at its width, with the mode grid and colour /
  monitoring icon chips visible with little scrolling.

### Seamless switch measurement (Pad 3)

- One Main10 HLG clip: `SlateCam_20261124_014006.mp4`, 199.27 s, 5,969 frames.
- `ffprobe` frame PTS: median Δ 33.4 ms, 5,957 of 5,968 intervals in the 30 fps
  bucket, and a single 122 ms pause at the back→front switch. The recorder and
  audio/gyro never restarted.
- POCO X6 Pro: Movie → RECORD → camera stepper → STOP produced one 8-bit SDR
  clip; 10-bit remains withheld by `poco-duchamp-force-8bit`.

### Colour calibration

- `ColorCalibration` is unit-tested (9 cases) but no matrix is shipped: a
  display-referred capture on the test bench was not reliable enough to claim a
  ColorChecker measurement. The next step is a physical 24-patch target per
  lens; the solver accepts the patch means directly.

## Flicker-safe shutter grid (Pad 3 + Pad 6, build 2088)

Camera2 ignores `CONTROL_AE_ANTIBANDING_MODE` when AE is off, so M/S now snap
`SENSOR_EXPOSURE_TIME` to whole flicker periods itself. `SlateCamExposure`
reports every applied value.

| Mode | Pad 6 (`71f186d3`) | Pad 3 (`ef9be99a`) |
|---|---|---|
| 50 Hz (`stepNs=10000000`) | 10 / 30 / 40 ms | 10 / 20 / 40 ms |
| 60 Hz (`stepNs=8333333`) | 8.33 / 25 / 33.33 ms | 8.33 / 16.67 / 25 / 33.33 ms |
| auto (`stepNs=10000000`, region IN) | 20 ms | 10 / 20 ms |
| 180° at 30 fps on the 50 Hz grid | — | 20 ms (216°), not 16.67 ms |
| Switching 50 → 60 Hz | 30 ms re-snapped to 33.33 ms | 40 ms re-snapped to 41.67 ms |
| Slider | discrete ticks, hint `Flicker-safe 50 Hz · 1/100 · 10.00 ms steps` | same; hint also shows the resulting angle |

Both devices keep `Changelog`-level stability: no session restart, no exposure
errors, and the label matches the applied exposure (`1/100 · 10.00 ms`,
`1/120 · 8.33 ms`, `1/24 · 41.67 ms`). `auto` falls back to the region's mains
frequency because the detected 50/60 Hz value is not readable once AE is off;
both test devices report region `IN`, so auto uses the 50 Hz grid.

## Picture profiles and look pipeline (Pad 3, build 2091)

The Colour card is now **Picture profile** with three entries: `standard`,
`SlateLook`, `custom`. SlateLog and the flat monitoring view were removed. The
Colour science card and its five commands are gone. `SlateCamGl` reports the
resolved look.

| Check | Result |
|---|---|
| Profile list | exactly `standard`, `slatelook`, `custom`; the SlateLog chip and its note are absent |
| Standard | `profile black=0.0 gamma=1.0/1.0/0.0 bg=0.0 knee=1.0/0.0 sat=1.0 colorOps=false`; UI says “Camera look: no creative processing.” |
| SlateLook | `gamma=1.0/1.02/0.32 bg=0.071 knee=0.95/0.4 sat=0.9375 colorOps=true` (S-Cinetone curve, soft knee, slightly muted, warm phase) |
| SlateLook recording | `1920×1080 Main 10 arib-std-b67 bt2020`, FP16 intermediate, 30 fps |
| Custom | persisted Cine4 settings selected and logged `gamma=1.14/0.88/0.40`; the editor opens |
| Removed log route | no `preview log route` diagnostics remain; the preview always uses the BT.709 display transform |
| Shader | no compile or runtime errors; Standard takes the identity path |

Pad 6 was offline for build 2091; its build-2090 evidence for the profile,
Custom and recording paths still applies, with SlateLog now removed. The gamma
presets are parameterised look curves, not bit-exact Sony transfers. Auto knee
is a static max-point/sensitivity mapping, and Detail controls are deferred.
S-Log/S-Gamut remain absent because the encoder writes truthful HLG/Rec.709
metadata.

## Manual white balance (Pad 3, build 2092)

Pad 3 (`ef9be99a`), Android 17 / SDK 37, debug APK build 2092. White balance
gains a Manual chip with temperature and tint sliders.

| Check | Result |
|---|---|
| Capability | `android.colorCorrection.availableModes` = `[0 1 2 ]` (TRANSFORM_MATRIX, FAST, HIGH_QUALITY); no CCT mode, no `colorTemperatureRange` |
| Camera2 gains honoured | forcing `RggbChannelVector(4, 1, 1, 0.25)` with AWB off turned the preview strongly red — the request keys are applied |
| Gains-only manual | green-normalised black-body gains at 5600 K still produced a strong green cast (sensor white point not neutralised) |
| Reported CCM | `SENSOR_COLOR_TRANSFORM1` = `1.523,-0.719,-0.234,-0.969,1.875,0.039,0.039,-0.141,0.734` at D65; feeding it back as `COLOR_CORRECTION_TRANSFORM` stayed green. Inverse composition collapses to a near-diagonal gain matrix, so no usable CCM is derivable |
| Preset base | `daylight` produced correct, neutral colour, so the HAL owns the CCM and manual anchors on it |
| Manual @ 5600 K | `wb: manual 5600K tint=0 preset=daylight cct=5500`, visually identical to the daylight preset |
| Temperature | 2600 K → `preset=incandescent cct=2856`, visibly cooler; 7600 K → `preset=shade cct=7500`, visibly warmer |
| Tint | +70 produced a clear magenta shift with the temperature unchanged |
| Preset snap | tapping `daylight` selected the chip and moved Temperature/Tint to `5500 K / 0`; the image matched the daylight preset |
| Drag enters manual | touching Temperature at 3600 K selected Manual: `wb: manual 3600K tint=0 preset=warm_fluorescent cct=3000` |
| Auto | switching back to Auto restored auto colour (residual reset to 1.0); the sliders keep the last value as the manual starting point |
| Persistence | force-stop and cold start restored `wb: manual 3500K tint=70 preset=warm_fluorescent cct=3000` with the same visible cast |
| Recording | 7.0 s 10-bit HLG clip in manual mode: `profile=2 standard=6 transfer=7 range=2` and `bit_depth=10`, MediaStore row `SlateCam_20261124_174739.mp4` 1920×1080 7029 ms, gyro sidecar 547 samples / 202 frames |
| Lens switch | back → front kept manual 3500 K +70 tint and re-applied the residual |
| 8-bit path | 1080p 8-bit SDR showed the same manual behaviour (the residual is a shader stage, independent of bit depth) |

The preset CCTs are nominal (standard Camera2 illuminant values). Absolute
temperature accuracy is therefore the preset's real CCT plus the black-body
approximation; measured per-lens calibration data is still required to close
the gap.

## First-run camera access (Pad 3, build 2093)

Pad 3 (`ef9be99a`), Android 17 / SDK 37, debug APK build 2093. Reproduces the
new-user path by uninstalling and reinstalling without `pm install -g`, so no
runtime permission is pre-granted.

| Check | Result |
|---|---|
| Fresh install, first launch | camera prompt shown, launch screen shows **Grant camera access**; no red `SecurityException` banner (build 2092 and earlier dumped `validateClientPermissionsLocked: … cannot open camera "0" without camera permission`) |
| Grant camera + audio | after Allow, the viewfinder opened by itself on activity resume (`SlateCamGl: first frame previewSurface=1920x1080`), no restart needed |
| Deny both | launch screen keeps the explanation and the **Grant camera access** action; nothing is opened or left half-started |
| Retry after denial | tapping **Grant camera access** re-prompts, and granting on the retry opens the preview |
| Relaunch with permission granted | auto-opens on the 600 ms launch timer as before |
| Background → foreground | preview reopens on resume (single session; no duplicate open) |

Root cause of the bad first-run state: `_openCamera` ran 600 ms after launch
regardless of permission. On a fresh install the open threw `SecurityException`
while the dialog was up, the renderer stayed started, and the next `openCamera`
call re-entered `GlPreviewRenderer.start()` on a live pipeline, which wedged the
app until the process was killed. `openCamera` now checks permission first, and
a failed open tears down the session and GL state so retrying is safe.

## Viewfinder pacing and resolution control (Pad 3, build 2094)

Pad 3 (`ef9be99a`), Android 17 / SDK 37, debug APK build 2094. Preview is a GL
surface rendered into Flutter's `SurfaceProducer`; measurement used temporary
per-frame timing logs and a temporary colour flag (removed after the pass).

| Check | Result |
|---|---|
| Camera cadence | `interval=33.333 ms`, `fps=30.0` across every 60-frame window at 720p and 1080p preview |
| Render cost, no analysis | 1.2–1.5 ms per preview frame (1280×720 and 1920×1080) |
| Analysis cost | synchronous `glReadPixels` added ~9 ms on every analysis frame; with `ANALYSIS_INTERVAL=2` the average draw was 6.4–7.9 ms |
| After the change | `ANALYSIS_INTERVAL=3` + vsync-paced draw: 4.7–5.5 ms average, camera still 30.0 fps |
| Presentation probe | a per-produced-frame colour flag showed irregular holds (1 vs 3–7 refreshes, occasional missing colours) while a 10 Hz time-based flag cycled through the same recording without a gap — production is clean, the display handoff is not vsync-locked |
| VF pill | `VF Full` → `VF 720` retargeted the producer (`output retargeted 1280x720`); `VF 720` → `VF Full` retargeted to `1920x1080` |
| Console | the Viewfinder card is gone; the console starts with Monitoring after Processing |
| Recording regression | 5.1 s 1080p clip (`SlateCam_20261124_194859.mp4`, 1920×1080) with `encoder frames=60` per 2 s during the new pacing, gyro sidecar written |
| Manual WB regression | dragging Temperature logged `wb: manual 4800K tint=0 preset=twilight cct=4500` |

Remaining latency is the camera → SurfaceTexture → Flutter texture → compositor
hop; the GL thread is not the bottleneck. The deeper split (native preview
surface on vsync) is tracked in the backlog and ADR 0022.

## Smooth zoom ramp (Pad 3, build 2100)

Pad 3 (`71f186d3`), Android 17 / SDK 37, debug APK build 2100. Active mode
1440×1080 · 30p · 4:3 on the 28 mm back lens, zoom range 1×–10×.

| Check | Result |
|---|---|
| Unit tests | `SmoothZoomTest` passes: snap, retarget, rate cap, monotonic pull-out, bounds clamp, settle, no-op and stop |
| Ramp measurement | 30 fps screen recording of a 300 ms slider drag 10× → 1×; readout stepped 10 → 9.7 → 8.9 → 7.2 → 3.8 → 2.9 → 2.3 → 1.4 → 1.2 → 1.1 → 1.0 across ~0.7 s, settling exactly on 1.0 |
| Recorded output | 2.9 s clip (`SlateCam_20261125_160514.mp4`) with an 800 ms zoom-in while recording; extracted frames at t=1.2/1.4/1.6/1.8/2.0 s show a progressively tighter crop, so the ramp is in the camera stream, not a preview-only effect |
| Reopen stability | switching resolution/bit depth holds the current zoom; switching lenses snaps to the new range's clamped value and cancels the ramp |
| UI | slider thumb and right-hand readout follow the applied value while it eases |

Optical hand-off between physical lenses remains vendor HAL behaviour; the ramp
smooths the requested ratio, not the HAL's internal camera switch.

## Zoom control usability (Pad 3, build 2106)

The old control was a 132 lp Material slider with a 12 lp thumb inside a
horizontal scroll view; a hand drag was usually claimed by the scroll view or
missed the thumb. Rebuilt as a two-row panel: lens chips on top, a 48 lp
full-width track with a 22 lp thumb below. The whole strip is an opaque
tap/drag target.

| Check | Result |
|---|---|
| Off-thumb tap at 50 % | set the target to 5.4× and settled there; image zoomed smoothly |
| Off-thumb drag to 80 % | settled at 8.0×; thumb tracked the finger and held until the ramp caught up |
| Single-tap regression | first ramp tick could see `dt = 0` and stop; fixed by posting the first tick after one frame and keeping `advance(0)` animated (test added) |
| Hand feel | lens chips now have a 36 lp minimum touch height; no horizontal scroll competes with the slider drag |

## Viewfinder judder — state push vs presentation (Pad 3, build 2120)

Pad 3 (`71f186d3`), Android 17 / SDK 37, 1440×1080 30p, 60 Hz display mode.
Measured with `dumpsys SurfaceFlinger --latency` on the Flutter SurfaceView and
temporary GL/bridge timing logs (removed after the pass).

| Check | Result |
|---|---|
| GL production | `draw` timestamps 33 ms apart for 277/289 frames; only three 49–82 ms warm-up stragglers, no steady-state drops |
| Before the fix | SurfaceFlinger deltas mostly 33.3 ms, with repeated 83.3/100 ms holds and 16.7 ms catch-up bursts — the classic dropped-present pattern |
| Root cause probe | suppressing periodic state pushes removed the holds; `setState` on telemetry alone reintroduced them even with a small payload |
| Idle after the fix | 126/127 frames at 33.2–33.5 ms, no 83/100 ms holds |
| Recording after the fix | 77 frames at 33.3 ms, 21 at 33.4, 16 at 16.7, three 83 ms holds in 5 s (encoder-path load, not state) |
| Histogram/waveform visible | 118/126 at ~33.3 ms, six 50 ms holds in 5 s from the 2 Hz array push |
| Live readouts | dock and console Exposure card still follow ISO/shutter; REC pill, timer and red border update without rebuilding the preview |

The pipeline and decision are in ADR 0024. The remaining analysis-array cost is
tracked in the backlog as a binary-payload/off-thread decode follow-up.

## User picture profiles (Pad 3, build 2122)

Pad 3 (`71f186d3`), Android 17 / SDK 37, 1440×1080 · 30p.

| Check | Result |
|---|---|
| Create | **New** created “Look 2” with identity settings and selected it; the chip appeared in the Picture profile card |
| Edit + persistence | black level moved to 9; after a force-stop/relaunch the TestLook chip was active with black level 9 |
| Per-parameter reset | the black-level reset icon returned only that parameter to 0 |
| Reset all | **Reset all parameters** restored knee/saturation/colour values to defaults |
| Rename | TestLook replaced the old name in the chip and editor |
| Delete | confirmation removed TestLook and fell back to Standard |
| Built-ins | Standard and SlateLook remain read-only; no rename/delete actions are shown for them |
| Migration | the pre-library `custom_picture_profile` value appeared as a user profile named **Custom** |

## Playback and media library (Pad 3, build 2127)

Pad 3 (`71f186d3`), Android 17 / SDK 37, 1440×1080 · 30p.

| Check | Result |
|---|---|
| Library | 15 clips listed newest-first from `Movies/SlateCam`, with thumbnails, duration, 1440×1080, size and date; header showed `66.5 GB free` |
| Playback | 0:39 clip opened and played; scrub to 0:20; pause/resume and mute worked |
| Share | share action opened the MIUI chooser with a temporary content-URI grant |
| Delete | recorded a 0:02 clip (15 clips), confirmed delete → 14 clips, snackbar, MediaStore folder no longer contains the file |
| Camera handoff | Clips closed the camera; returning reopened the preview with the same mode and lens |
| Empty/error states | present in code; no failure to report on this device |

## Pad 3 smoke test (build 2145)

OnePlus Pad 3 (`OPD2415`, device `OP6190L1`), Android SDK 37, fresh install
from the pushed commit.

| Check | Result |
|---|---|
| Cold launch | Camera opened, deck and preview rendered; process stayed alive |
| Console | Deck scrolled to Audio; input chips, source, gain and meter rendered |
| Clips | Library listed existing clips with thumbnails and free space |
| Playback | A 0:28 clip opened and played |
| Recording | 3 s take produced HEVC + AAC; both tracks finalized and the clip played back |
| Crash check | No `FATAL EXCEPTION`, `ANR in`, or force-finish for the app across the smoke run |

The built-in microphone was very quiet on this unit: the monitor reported
`SILENT INPUT` in the room and the 3 s test clip measured around −80 dBFS RMS.
The AAC track exists and is not digital silence; no USB or wired mic was
available to distinguish room/mic gain from a routing issue. The test clip was
removed; the app was left live.


## HLG preview brightness (OnePlus Pad 3, build 2149)

Same stationary scene, switching only the bit depth in the Recording Mode card;
preview content region and a fixed wall patch measured from the screenshots.

| Mode | Preview mean luma | Wall luma |
|---|---|---|
| 10-bit HLG before fix | 57.4 | 104.9 |
| 8-bit SDR | 103.2 | 163.1 |
| 10-bit HLG after fix | 106.0 | 200.8 |

The mapping uses the BT.2100 OOTF and a 203-nit-to-100-nit SDR reference
(K≈4.93). Preview mean now matches SDR; HLG highlights sit slightly higher,
which is expected headroom. A 2.7 s 10-bit take after the fix still probes as
HEVC Main 10, BT.2020, arib-std-b67; the test clip was removed.


## Logical zoom discovery (POCO X6 Pro 5G, build 2150)

POCO X6 Pro (`2311DRK48I`, device key `xiaomi/duchamp`), probe schema 1.12.0.

| Camera | Logical | Physical ids | Zoom range | Discovery |
|---|---|---|---|---|
| `4` | yes | `0`, `2` | 0.6–10.0 | **eligible**; stops 0.621 (ultra-wide) and 1.0 (default) |
| `5` | yes | `0`, `2` | 1.0–10.0 | rejected: `zoom_range_does_not_cover_ultrawide` |

The probe report carries `logical_zoom` with `handoff_eligible`, sorted stops,
uncovered ratios and the rejection reason. This is discovery only: no controller
or recording path is enabled yet, and the `logicalZoomHandoff` verdict has not
run. The physical front camera and the non-logical cameras are unchanged.

`LogicalZoomTest` covers eligible, non-logical, range-above-1.0, uncovered,
duplicate-ratio, missing-metadata and missing-range cases (8 tests).

## Logical zoom controller (POCO X6 Pro 5G, build 2160)

The vendor-hidden logical camera `4` (physical `0`/`2`, stops 0.621 and 1.0)
was exercised end to end.

| Check | Result |
|---|---|
| Initial session | Opens logical camera `4`; lens chips are logical stops, not camera IDs |
| 0.6× chip | Same camera `4`; `CONTROL_ZOOM_RATIO` to 0.621; `activePhysical=2`; no camera reopen |
| 1.0× chip | Same camera `4`; ratio 1.0; `activePhysical=0`; no camera reopen |
| Recording | 7.9 s take switched 1.0×→0.6×→1.0×; video PTS max delta 34 ms, audio 7.86 s / video 7.90 s |
| Probe verdict | `logicalZoomHandoff_c4` **pass**: 102 frames, 3.37 s, median 33 ms, max gap 34 ms, exposure 1.15×, WB 1.46×, physical 0/2 |
| Front round trip | F1 then 1.0× returned to logical `4` (one session switch, then zoom) |
| Hidden macro | Camera `3` is vendor-hidden; opening it invalidates the camera list until process restart. It is excluded from the UI |
| Fallback | 0.6×/1.0× physical ultrawide round trip works; the new path is used only when the recorded verdict passes |

`LogicalZoomMetricsTest` covers steady frames, timestamp gaps, exposure/WB
jumps, a single physical lens and too few frames. The test clips created for the
run were removed.

## Dual-session front↔back switch (Pad 3, build 2165)

OnePlus Pad 3, 1440×1080 · 30p · 10-bit HLG. Probe pair `0+1` passes
`dualSessionSwitch_c0_c1`.

| Check | Result |
|---|---|
| Standby | `standby camera=1 slot=1 mode=1440x1080@30/10` while recording |
| Switch A→B | `dual switch active=1 slot=1`; recorder continued, preview switched to front |
| Switch B→A | `dual switch active=0 slot=0`; recorder continued, preview returned to back |
| Clip | 11.78 s video / 11.78 s audio; video max PTS delta **44.6 ms**, no recorder teardown |
| Verdict | `dualSessionSwitch_c0_c1 pass`: 50 frames, 1.7 s, median 33 ms, max 73 ms, both stream IDs |
| Gate | Runtime required exposed IDs, `concurrent_with`, a matching mode and the verdict |

The switch skips the old frame-pump pause; the outgoing session stays alive as
the next standby. `dualSessionSwitch` does not compare exposure/WB across two
different sensors, because a front↔back boundary is expected to change scene.
The test clip was removed.

## Logical handoff FOV and jitter check (POCO X6 Pro, build 2169)

Compared the same still scene at 1.0× from:

- physical camera `0` (main, 25 mm) via the B0 top-bar switch, and
- logical camera `4` at `CONTROL_ZOOM_RATIO = 1.0` (which the HAL routes through
  the ultra-wide at that moment).

The outlet, cable and wall edges have identical position and scale in both
frames, so the logical camera's normalized FOV at 1.0× matches the physical
main lens. The handoff itself is HAL-owned and hysteretic:

| Direction | Physical-ID transition |
|---|---|
| zooming wide | ultra-wide at ~0.86× |
| zooming back | main at ~1.37× |

The app cannot force which physical sensor the logical camera selects. The
`activePhysicalCameraId` now travels on telemetry instead of forcing a full
`CameraState` and preview rebuild at that transition, removing the app-side
viewfinder remount jitter.

## Aspect-ratio switching (POCO X6 Pro, build 2172)

Tapped 16:9 ↔ 4:3 repeatedly at the 1080 and 4K families after a prior mode
change, with the stream and preview headers read from the app.

| Cycle | Stream | Preview surface | Result |
|---|---|---|---|
| 1080 16:9 → 4:3 | 1440×1080 | 1440×1080 | 4:3 letterboxed, no stretch |
| 1080 4:3 → 16:9 | 1920×1080 | 1920×1080 | 16:9 fills the frame |
| 1080 cycle ×2 | alternating | alternating | both directions correct |
| 4K 16:9 → 4:3 | 2880×2160 | 2880×2160 | 4:3 letterboxed |
| 4K 4:3 → 16:9 | 3840×2160 | 3840×2160 | 16:9 fills the frame |
| 4K cycle ×2 | alternating | alternating | both directions correct |

Recording started after the 4K switch (`REC 00:02`, red frame border), so the
stale `opened` flag no longer blocks the recorder. The regression was the
single lifecycle flag being cleared by transient session closes; the fix splits
pipeline readiness from live-session state.

## Unified focus interface (build 2174)

POCO X6 Pro, logical camera `4` (all HAL modes plus SAF):

| Action | Result | Persisted `focus_mode` |
|---|---|---|
| Fresh launch | CAF selected; CAF-P, AF, MACRO, MF, SAF listed | `continuous_video` |
| Tap SAF | SAF selected, Wedding scenario highlighted, software engine active | `software` |
| Tap MF | MF selected, manual slider shows 1.88 D | `off` |
| Tap CAF | CAF selected, scenario chips unhighlighted | `continuous_video` |
| Tap Wedding while CAF | switches to SAF and applies the Wedding preset | `software` |
| Restart | SAF and Wedding restored from the persisted preference | `software` |

Pad 3: fresh launch selects CAF and lists CAF, CAF-P, AF, MACRO, EDOF, MF and
SAF. The Wedding preset is not highlighted while CAF is active, confirming that
a scenario only describes SAF. Choosing SAF highlights the selected scenario.

## Portrait UI and recording (build 2180)

**POCO X6 Pro, logical camera 4, 1440×1080 4:3 30p 8-bit.**

| Check | Result |
|---|---|
| Portrait layout | Viewfinder above the console; all existing cards below |
| Landscape layout | Viewfinder beside the console; rotation live, no reopen |
| Portrait preview vs clip | Same composition and upright orientation |
| Portrait clip | `1440×1080`, MP4 rotation `-90`; auto-rotated playback `1080×1440` |
| Landscape clip | `1440×1080`, no rotation matrix; unchanged from before |
| Focus tap in portrait | Reticle at the tapped point; focus region follows the inverse rotation |
| Rotate during idle | Preview and console switch immediately |
| Rotate during take | Clip keeps its start orientation; new rotation applies after finalize |

**Pad 3:** portrait stacks the viewfinder above the console and the console uses
its three responsive columns; landscape remains side-by-side. The Pad reports a
different preview size and still rotates live without reopening the camera.

## One-tap orientation toggle (build 2181)

POCO X6 Pro: the rotate control sits left of the `VF Full` pill. Tapping it in
portrait moved the display to `ROTATION_90` and switched to the side-by-side
landscape console; tapping the same control again returned to `ROTATION_0` and
the stacked portrait console. The camera stayed open and the preview composition
followed the new orientation in both directions.

## Full-bleed portrait viewfinder (build 2182)

POCO X6 Pro: in portrait, both 1440×1080 4:3 and 1920×1080 16:9 fill the
viewfinder width with no black side bars; vertical overflow is cropped. Focus
taps use the cropped mapping before the GL transform. Landscape still shows the
complete frame beside the console.

## Orientation-aware aspect ratios (build 2186)

POCO X6 Pro, portrait:

| Selection | UI size | Encoded stream | MP4 hint | Playback |
|---|---|---|---|---|
| 3:4 | 1080×1440 | 1440×1080 | `-90` | 1080×1440 |
| 9:16 | 1080×1920 | 1920×1080 | `-90` | 1080×1920 |

The viewfinder composition matches the auto-rotated playback frame in both
cases. Landscape labels and dimensions remain `4:3` (1440×1080) and `16:9`
(1920×1080). This follows the standard Camera2 approach: sensors emit landscape
frames, and the MP4 orientation hint carries the take orientation.

## Uncrop and recording border (build 2188)

POCO X6 Pro portrait 3:4: the viewfinder shows the complete `1080×1440` frame
at full width; starting a recording draws the red border on the image edges, all
four corners visible. Portrait 9:16 shows the complete `1080×1920` frame; its
side margins are outside the red border and are never encoded. The playback
frame matches the viewfinder composition in both ratios.

## Portrait 9:16 and 4:3 open gate (build 2190)

POCO X6 Pro portrait:

| Selection | UI size | Preview | Encoded clip |
|---|---|---|---|
| 9:16 | 1080×1920 | tall, full frame | 1920×1080 + `-90` hint |
| 4:3 | 1440×1080 | landscape, letterboxed | 1440×1080, no hint |

The red recording border wraps the actual frame in both cases, with all four
corners visible. The 4:3 open gate is no longer mislabelled or converted to
3:4; 9:16 remains the vertical video mode.

## Portrait 9:16 and 4:3 upright text (build 2191)

POCO X6 Pro, monitor with left-to-right text in front of the camera:

| Selection | UI size | Preview | Clip | Text |
|---|---|---|---|---|
| 9:16 | 1080×1920 | tall, full frame | 1920×1080 + `-90` | left to right |
| 4:3 | 1080×1440 | tall, full frame | 1440×1080 + `-90` | left to right |

The red recording border wraps the frame on all four sides for both. The
previous `4:3` open-gate behaviour made upright text run top to bottom and was
removed.

## Recording border above controls (build 2192)

POCO X6 Pro portrait 4:3: while recording, the red border is visible on all
four sides of the video frame, including the bottom edge below the dock. The
border is drawn as a topmost overlay, so the transport, zoom rail and status
pills cannot hide an edge.

## Raw sensor feed diagnostic (build 2196, Pad 3)

With `SENSOR FEED` enabled on Pad 3 in portrait, the camera's native frame is a
landscape buffer in which the reference monitor text runs top-to-bottom. The
upright `4:3`/`9:16` presentations rotate that buffer by a quarter turn, which
is why they read left-to-right. This confirms that a true 4:3-wide take with
left-to-right text requires either the upright 3:4 phone convention or a
top/bottom crop of the rotated frame.

## Pad 3 final portrait decision (build 2197)

Chosen behaviour: upright 3:4 take labelled `4:3`. With the monitor as the
reference, Pad 3 portrait shows left-to-right text for both `9:16` and `4:3`.
During recording the red border is a topmost overlay and is present on all four
sides; pixel scan found the border rows/columns at the frame edges (105–2440
and 324–2075 in the 2400×3392 capture). The raw `SENSOR FEED` diagnostic
confirmed the native frame is landscape/sideways, which is why the quarter turn
is required.
