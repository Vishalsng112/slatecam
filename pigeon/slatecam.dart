import 'package:pigeon/pigeon.dart';

/// One selectable lens, derived from the probe report rather than a model list.
class LensOption {
  LensOption({
    required this.id,
    required this.label,
    required this.role,
    required this.facing,
    required this.zoomRatio,
    required this.focalLength35mm,
    required this.openable,
    required this.exposed,
    required this.logical,
    required this.stopRatio,
    required this.physicalCameraId,
  });

  /// Camera2 ID; may be absent from cameraIdList on vendor-hidden lenses.
  /// For a logical stop this is the logical multi-camera ID.
  String id;
  /// Short UI label, e.g. `0.6x - 16mm`, `1x - 25mm`, `MACRO`.
  String label;
  /// default | ultrawide | tele | macro
  String role;
  String facing;
  /// Zoom relative to the assessed default lens; < 1 is wider, > 1 narrower.
  double zoomRatio;
  /// 35 mm-equivalent focal length; 0 when unknown.
  double focalLength35mm;
  /// True when this lens was opened successfully by the prober.
  bool openable;
  /// True when the ID appears in CameraManager.cameraIdList.
  bool exposed;
  /// True when this option is a zoom stop on a logical multi-camera.
  bool logical;
  /// Target CONTROL_ZOOM_RATIO for a logical stop; 0 for a physical lens.
  double stopRatio;
  /// Physical sensor behind the option; empty when unknown.
  String physicalCameraId;
}

/// One recording mode offered by the selected camera.
class RecordingMode {
  RecordingMode({
    required this.id,
    required this.label,
    required this.width,
    required this.height,
    required this.fps,
    required this.tenBit,
    required this.enabled,
    required this.reason,
  });

  String id;
  String label;
  int width;
  int height;
  int fps;
  /// True when the mode records 10-bit HLG.
  bool tenBit;
  bool enabled;
  /// Why the mode is disabled; empty when enabled.
  String reason;
}

/// A named lens set for Movie/Vlog mode.
class ShootProfile {
  ShootProfile({
    required this.id,
    required this.name,
    required this.lensCount,
  });

  String id;
  String name;
  int lensCount;
}

/// A lens the user can include in the Movie/Vlog shoot set.
class ShootLens {
  ShootLens({
    required this.id,
    required this.label,
    required this.facing,
    required this.role,
    required this.selected,
  });

  String id;
  String label;
  String facing;
  String role;
  bool selected;
}

/// Editable settings for a user picture profile.
class ProfileSettings {
  ProfileSettings({
    required this.blackLevel,
    required this.gamma,
    required this.blackGammaRange,
    required this.blackGammaLevel,
    required this.kneeMode,
    required this.kneeAutoMaxPoint,
    required this.kneeAutoSensitivity,
    required this.kneePoint,
    required this.kneeSlope,
    required this.colorMode,
    required this.saturation,
    required this.colorPhase,
    required this.colorDepthR,
    required this.colorDepthG,
    required this.colorDepthB,
    required this.colorDepthC,
    required this.colorDepthM,
    required this.colorDepthY,
  });

  /// -15..+15 linear pedestal.
  int blackLevel;
  /// standard | movie | still | cine1..cine4 | itu709 | itu709_800 | scinetone
  String gamma;
  /// wide | middle | narrow
  String blackGammaRange;
  /// -7..+7
  int blackGammaLevel;
  /// auto | manual
  String kneeMode;
  /// 90..100
  int kneeAutoMaxPoint;
  /// high | mid | low
  String kneeAutoSensitivity;
  /// 75..105
  int kneePoint;
  /// -5..+5
  int kneeSlope;
  /// movie | still | cinema | pro | itu709_matrix | mono | bt2020 | bt709
  String colorMode;
  /// -32..+32
  int saturation;
  /// -7..+7
  int colorPhase;
  /// -7..+7 per hue axis.
  int colorDepthR;
  int colorDepthG;
  int colorDepthB;
  int colorDepthC;
  int colorDepthM;
  int colorDepthY;
}

/// One selectable picture profile. Built-ins are read-only; user profiles can
/// be renamed, edited and deleted.
class PictureProfileInfo {
  PictureProfileInfo({
    required this.id,
    required this.name,
    required this.builtIn,
  });

  String id;
  String name;
  bool builtIn;
}

/// A recorded clip in the SlateCam media library.
class MediaClip {
  MediaClip({
    required this.id,
    required this.name,
    required this.uri,
    required this.durationMs,
    required this.sizeBytes,
    required this.width,
    required this.height,
    required this.dateAddedMs,
  });

  String id;
  String name;
  String uri;
  int durationMs;
  int sizeBytes;
  int width;
  int height;
  int dateAddedMs;
}

class MediaStorageInfo {
  MediaStorageInfo({required this.freeBytes, required this.totalBytes});

  int freeBytes;
  int totalBytes;
}

/// One selectable audio input.
class AudioInputOption {
  AudioInputOption({
    required this.key,
    required this.name,
    required this.type,
    required this.detail,
  });

  /// Persisted identity; `default` means the system route.
  String key;
  String name;
  /// Built-in mic / USB audio / wired headset / ...
  String type;
  /// e.g. `48 kHz · 2 ch`
  String detail;
}

/// One audio meter snapshot in dBFS.
class AudioTelemetry {
  AudioTelemetry({
    required this.peakDbL,
    required this.peakDbR,
    required this.rmsDbL,
    required this.rmsDbR,
    required this.clipped,
    required this.active,
  });

  double peakDbL;
  double peakDbR;
  double rmsDbL;
  double rmsDbR;
  bool clipped;
  /// True while the monitor or recorder is actually capturing.
  bool active;
}

/// Immutable camera snapshot. Kotlin owns this state; Dart only renders it.
class CameraState {
  CameraState({
    required this.isOpen,
    required this.error,
    required this.cameraId,
    this.activePhysicalCameraId,
    required this.cameraIds,
    required this.cameraLabels,
    required this.iso,
    required this.isoMin,
    required this.isoMax,
    required this.shutterNs,
    required this.shutterMinNs,
    required this.shutterMaxNs,
    required this.shutterStepNs,
    required this.focusDiopters,
    required this.focusMin,
    required this.focusMax,
    required this.focusShiftSpeed,
    required this.whiteBalanceMode,
    required this.whiteBalanceModes,
    required this.whiteBalanceKelvin,
    required this.whiteBalanceTint,
    required this.manualWhiteBalanceSupported,
    required this.autoExposure,
    required this.autoFocus,
    required this.autoFocusSupported,
    required this.focusMode,
    required this.focusModes,
    required this.focusScenario,
    required this.focusScenarios,
    required this.focusShiftSensitivity,
    required this.focusTransitionSpeed,
    required this.previewWidth,
    required this.previewHeight,
    required this.previewOutputWidth,
    required this.previewOutputHeight,
    required this.previewQuality,
    required this.previewRotationDegrees,
    /// Clockwise MP4 orientation hint; 0 when portrait is encoded natively.
    required this.recordingOrientationHint,
    /// Quarter turns the viewfinder applies for the current display.
    required this.previewQuarterTurns,
    required this.zoomRatio,
    required this.zoomMin,
    required this.zoomMax,
    required this.lenses,
    required this.recordingMode,
    required this.recordingModes,
    required this.shootMode,
    required this.shootLenses,
    required this.shootProfiles,
    required this.activeShootProfile,
    required this.tenBit,
    required this.hlgAvailable,
    required this.hlgReason,
    required this.isRecording,
    required this.recordingSeconds,
    required this.clipPath,
    required this.recordingError,
    required this.shutterAngle,
    required this.frameRate,
    required this.gyroSampleCount,
    required this.profile,
    required this.pictureProfiles,
    required this.profileSettings,
    required this.creativeLut,
    required this.creativeLuts,
    required this.zebra,
    required this.falseColor,
    required this.peaking,
    required this.peakingSensitivity,
    required this.peakingStrength,
    required this.edgeMode,
    required this.edgeModes,
    required this.noiseReduction,
    required this.noiseReductionModes,
    required this.antibanding,
    required this.antibandingModes,
    required this.shadingCorrection,
    required this.shadingSupported,
    required this.oisSupported,
    required this.oisEnabled,
    required this.eisSupported,
    required this.eisEnabled,
    required this.exposureMode,
    required this.exposureModes,
    required this.meteringMode,
    required this.meteringModes,
    required this.meteringSpeed,
    required this.deviceKey,
    required this.deviceQuirks,
    required this.deviceCalibrationActive,
    required this.deviceCalibrationSource,
    required this.deviceDatabaseVersion,
    required this.deviceReportOptIn,
    required this.deviceReportRunning,
    required this.deviceReportPath,
    required this.deviceReportError,
    required this.audioInputs,
    required this.audioInputKey,
    required this.audioGainDb,
    required this.audioPermissionGranted,
    required this.audioAvailable,
    required this.audioError,
    required this.audioSource,
    required this.audioSampleRate,
    required this.audioChannels,
    required this.histogram,
    required this.waveform,
    required this.waveformColumns,
    required this.waveformBins,
  });

  bool isOpen;
  String? error;
  String cameraId;
  /// LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID of the latest result.
  String? activePhysicalCameraId;
  List<String> cameraIds;
  List<String> cameraLabels;
  int iso;
  int isoMin;
  int isoMax;
  int shutterNs;
  int shutterMinNs;
  int shutterMaxNs;

  /// Flicker-safe exposure step for the active anti-banding mode, or null when
  /// anti-banding is off and the shutter is continuous.
  int? shutterStepNs;
  double focusDiopters;
  double focusMin;
  double focusMax;
  double focusShiftSpeed;
  String whiteBalanceMode;
  List<String> whiteBalanceModes;
  /// Colour temperature for manual white balance, in kelvin (2000..10000).
  int whiteBalanceKelvin;
  /// Green/magenta trim for manual white balance (-100..100).
  int whiteBalanceTint;
  /// True when the sensor advertises AWB off, so manual WB can be offered.
  bool manualWhiteBalanceSupported;
  bool autoExposure;
  bool autoFocus;
  bool autoFocusSupported;
  /// Wire focus mode: off | auto | continuous_video | continuous_picture ...
  String focusMode;
  /// Focus modes reported by the active camera, excluding unsupported values.
  List<String> focusModes;
  /// interview | vlogging | wedding | cinematic
  String focusScenario;
  List<String> focusScenarios;
  /// Software focus subject-shift sensitivity, 0..1.
  double focusShiftSensitivity;
  /// Software focus transition speed, 0..1.
  double focusTransitionSpeed;
  int previewWidth;
  int previewHeight;
  /// Actual preview surface size; may be a downsampled viewfinder.
  int previewOutputWidth;
  int previewOutputHeight;
  /// full | 1080 | 720
  String previewQuality;
  int previewRotationDegrees;
  /// Clockwise MP4 orientation hint; 0 when portrait is encoded natively.
  int recordingOrientationHint;
  /// Quarter turns the viewfinder applies for the current display.
  int previewQuarterTurns;
  /// Current optical/digital zoom ratio applied to the active camera.
  double zoomRatio;
  double zoomMin;
  double zoomMax;
  /// Lenses offered by the probe for this device, back lenses only.
  List<LensOption> lenses;
  /// Selected recording-mode id.
  String recordingMode;
  /// Modes available for the active camera.
  List<RecordingMode> recordingModes;
  /// single | movie — movie uses the common mode of all selected lenses.
  String shootMode;
  /// Probe-visible, openable lenses with their shoot-set selection.
  List<ShootLens> shootLenses;
  /// Named shoot profiles for this device.
  List<ShootProfile> shootProfiles;
  /// Currently active shoot profile id.
  String activeShootProfile;
  /// True while the camera session or the clip in flight really carries HLG10.
  bool tenBit;
  /// Probe verdict for this camera, size and frame rate.
  bool hlgAvailable;
  String hlgReason;
  bool isRecording;
  int recordingSeconds;
  String? clipPath;
  String? recordingError;
  double shutterAngle;
  int frameRate;
  int gyroSampleCount;
  String profile;
  List<PictureProfileInfo> pictureProfiles;
  /// Settings of the active profile; editable only for `custom`.
  ProfileSettings profileSettings;
  /// Selected creative LUT id, or empty for none.
  String creativeLut;
  /// LUTs available on this device, including `none`.
  List<String> creativeLuts;
  bool zebra;
  bool falseColor;
  bool peaking;
  double peakingSensitivity;
  double peakingStrength;
  String edgeMode;
  List<String> edgeModes;
  String noiseReduction;
  List<String> noiseReductionModes;
  String antibanding;
  List<String> antibandingModes;
  /// Lens-shading (vignette) correction for the active sensor.
  bool shadingCorrection;
  /// True when the active sensor offers FAST or HIGH_QUALITY shading.
  bool shadingSupported;
  /// Optical image stabilisation is available on this sensor.
  bool oisSupported;
  /// OIS preference; on by default where supported.
  bool oisEnabled;
  /// Electronic (video) stabilisation is available on this sensor.
  bool eisSupported;
  /// EIS preference; on by default where supported.
  bool eisEnabled;
  String exposureMode;
  List<String> exposureModes;
  /// multi | center | spot | average | highlight
  String meteringMode;
  List<String> meteringModes;
  /// 0 = slowest exposure response, 1 = fastest.
  double meteringSpeed;
  String deviceKey;
  List<String> deviceQuirks;
  bool deviceCalibrationActive;
  String? deviceCalibrationSource;
  int deviceDatabaseVersion;
  bool deviceReportOptIn;
  bool deviceReportRunning;
  String? deviceReportPath;
  String? deviceReportError;
  /// Selectable inputs, including a synthetic `default` entry.
  List<AudioInputOption> audioInputs;
  /// Persisted input key; `default` asks the platform for its route.
  String audioInputKey;
  /// Software input trim in dB, applied before metering and encoding.
  double audioGainDb;
  bool audioPermissionGranted;
  /// True when the monitor or recorder currently owns a working input.
  bool audioAvailable;
  String? audioError;
  /// Platform source actually used: `unprocessed`, `camcorder` or `mic`.
  String? audioSource;
  int audioSampleRate;
  int audioChannels;
  List<int> histogram;
  List<int> waveform;
  int waveformColumns;
  int waveformBins;
}

/// High-frequency camera readouts. Pushed much more often than [CameraState];
/// keep it small so the preview presentation thread is not blocked.
class CameraTelemetry {
  CameraTelemetry({
    required this.iso,
    required this.shutterNs,
    required this.shutterAngle,
    required this.focusDiopters,
    required this.whiteBalanceMode,
    required this.whiteBalanceKelvin,
    required this.whiteBalanceTint,
    required this.zoomRatio,
    required this.isRecording,
    required this.recordingSeconds,
    this.clipPath,
    this.recordingError,
    required this.frameRate,
    required this.gyroSampleCount,
    required this.exposureMode,
    required this.meteringSpeed,
    required this.zebra,
    required this.falseColor,
    required this.peaking,
    required this.peakingSensitivity,
    required this.peakingStrength,
    required this.tenBit,
    required this.hlgAvailable,
    required this.hlgReason,
    this.activePhysicalCameraId,
    required this.histogram,
    required this.waveform,
    required this.waveformColumns,
    required this.waveformBins,
  });

  int iso;
  int shutterNs;
  double shutterAngle;
  double focusDiopters;
  String whiteBalanceMode;
  int whiteBalanceKelvin;
  int whiteBalanceTint;
  double zoomRatio;
  bool isRecording;
  int recordingSeconds;
  String? clipPath;
  String? recordingError;
  int frameRate;
  int gyroSampleCount;
  String exposureMode;
  double meteringSpeed;
  bool zebra;
  bool falseColor;
  bool peaking;
  double peakingSensitivity;
  double peakingStrength;
  bool tenBit;
  bool hlgAvailable;
  String hlgReason;
  /// Physical sensor behind a logical camera, when the HAL reports it.
  String? activePhysicalCameraId;
  List<int> histogram;
  List<int> waveform;
  int waveformColumns;
  int waveformBins;
}

@HostApi()
abstract class SlateCamHostApi {
  /// Creates the preview texture and opens the camera. Returns the Flutter
  /// texture id to render with `Texture(textureId:)`.
  int openCamera();

  /// True when the app currently holds the camera runtime permission.
  bool cameraPermissionGranted();

  /// Shows the system camera prompt when the permission is missing. The answer
  /// arrives through the app lifecycle, not as a return value.
  void requestCameraPermissions();

  void closeCamera();

  void setIso(int iso);

  void setShutterNs(int nanoseconds);

  void setShutterAngle(double degrees);

  void setFocusDiopters(double diopters);


  void setWhiteBalanceMode(String mode);

  /// Sets the manual colour temperature and tint; switches to manual WB.
  void setManualWhiteBalance(int kelvin, int tint);

  void setAutoExposure();

  void setMeteringMode(String mode);

  void setMeteringSpeed(double speed);

  /// Tells the bridge whether histogram/waveform data is visible, so the
  /// high-frequency telemetry does not carry the arrays when nobody draws them.
  void setAnalysisOverlayVisible(bool visible);

  /// Selects the exposure mode: P (HAL AE), S (fixed shutter, auto ISO), or M.
  void setExposureMode(String mode);

  /// Switches to another CameraManager camera ID.
  void setCameraId(String id);

  /// Selects a lens from the probe report. Hidden lenses are allowed when the
  /// probe marked them openable.
  void selectLens(String id);

  /// Selects a logical-camera zoom stop atomically (switch if needed, then
  /// drive CONTROL_ZOOM_RATIO). Used by the 0.6x/1x/... lens chips.
  void selectLensStop(String id, double ratio);

  /// Applies a zoom ratio to the active camera (optical or digital).
  void setZoom(double ratio);

  /// Taps to focus at a viewfinder position (0..1 in the displayed frame).
  void setFocusPoint(double x, double y);

  /// full | 1080 | 720. The recording stream stays at its mode size.
  void setPreviewQuality(String quality);

  /// Switches recording mode: size, frame rate and bit depth.
  void selectRecordingMode(String id);

  /// Selects single-camera or movie/vlog multi-camera mode.
  void setShootMode(String mode);

  /// Includes/excludes a lens from the persisted Movie/Vlog shoot set.
  void setShootLens(String id, bool selected);

  /// Saves the current lens set as a named profile (updates a same-name profile).
  void saveShootProfile(String name);

  /// Applies a named shoot profile.
  void applyShootProfile(String id);

  /// Renames a shoot profile.
  void renameShootProfile(String id, String name);

  /// Deletes a shoot profile.
  void deleteShootProfile(String id);

  void setFocusMode(String mode);

  /// Applies a scenario preset and switches to the software focus engine.
  void setFocusScenario(String scenario);

  void setFocusShiftSensitivity(double value);

  void setFocusTransitionSpeed(double value);

  void startRecording();

  void stopRecording();

  void setProfile(String profileId);

  /// Rewrites the active user profile's settings.
  void setProfileSettings(ProfileSettings settings);

  /// Resets the active user profile to default values.
  void resetProfile();

  /// Resets one parameter of the active user profile to its default.
  void resetProfileParameter(String key);

  /// Creates and selects a user profile; returns its id.
  String createPictureProfile(String name);

  void renamePictureProfile(String id, String name);

  /// Deletes a user profile. Built-ins cannot be deleted.
  void deletePictureProfile(String id);

  /// Selects a creative LUT, or `none` to disable it.
  void setCreativeLut(String lutId);

  void setZebra(bool enabled);

  void setFalseColor(bool enabled);

  void setPeaking(bool enabled, double sensitivity, double strength);

  void setEdgeMode(String mode);

  void setNoiseReduction(String mode);

  void setAntibanding(String mode);

  /// Enables or disables lens-shading (vignette) correction for the active
  /// sensor. The choice is persisted per camera ID.
  void setShadingCorrection(bool enabled);

  /// Optical stabilisation for the active sensor; persisted per camera ID.
  void setOis(bool enabled);

  /// Electronic (video) stabilisation; persisted per camera ID.
  void setEis(bool enabled);

  /// Opt-in for persisted device reports. Off by default.
  void setDeviceReportOptIn(bool enabled);

  /// Runs the prober on a background thread, closing preview first.
  void runDeviceReport();

  /// Reloads the bundled + user device database and reapplies calibration/quirks.
  void reloadDeviceDatabase();

  void reloadLuts();

  /// True when the app was launched to run a probe: preview must stay closed.
  bool isProbeMode();

  /// Recorded clips under Movies/SlateCam, newest first.
  List<MediaClip> listMediaClips();

  MediaStorageInfo mediaStorageInfo();

  /// Cached JPEG thumbnail path, or null when the clip has no readable frame.
  String? mediaClipThumbnail(String id);

  /// Deletes a SlateCam-owned clip (and its gyro sidecar).
  bool deleteMediaClip(String id);

  /// Opens the system share sheet for a clip.
  bool shareMediaClip(String id);

  /// Live audio inputs, with a synthetic `default` route first.
  List<AudioInputOption> listAudioInputs();

  /// Persists the input choice and restarts the monitor. Ignored while recording.
  void setAudioInput(String key);

  /// Applies a software input trim; takes effect immediately, even mid-take.
  void setAudioGain(double db);

  /// Re-enumerates inputs (after plugging/unplugging a microphone).
  void refreshAudioInputs();
}

@FlutterApi()
abstract class SlateCamFlutterApi {
  void onState(CameraState state);

  /// Fast-changing readouts; merges into the last [CameraState] on the Dart side.
  void onTelemetry(CameraTelemetry telemetry);

  /// Audio meter snapshot, pushed at ~10 Hz.
  void onAudio(AudioTelemetry audio);
}
