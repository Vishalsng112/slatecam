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
  });

  /// Camera2 ID; may be absent from cameraIdList on vendor-hidden lenses.
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

/// Immutable camera snapshot. Kotlin owns this state; Dart only renders it.
class CameraState {
  CameraState({
    required this.isOpen,
    required this.error,
    required this.cameraId,
    required this.cameraIds,
    required this.cameraLabels,
    required this.iso,
    required this.isoMin,
    required this.isoMax,
    required this.shutterNs,
    required this.shutterMinNs,
    required this.shutterMaxNs,
    required this.focusDiopters,
    required this.focusMin,
    required this.focusMax,
    required this.focusShiftSpeed,
    required this.whiteBalanceMode,
    required this.whiteBalanceModes,
    required this.autoExposure,
    required this.autoFocus,
    required this.autoFocusSupported,
    required this.previewWidth,
    required this.previewHeight,
    required this.previewRotationDegrees,
    required this.zoomRatio,
    required this.zoomMin,
    required this.zoomMax,
    required this.lenses,
    required this.recordingMode,
    required this.recordingModes,
    required this.shootMode,
    required this.shootLenses,
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
    required this.profileNames,
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
    required this.exposureMode,
    required this.exposureModes,
    required this.deviceKey,
    required this.deviceQuirks,
    required this.deviceCalibrationActive,
    required this.deviceCalibrationSource,
    required this.deviceDatabaseVersion,
    required this.deviceReportOptIn,
    required this.deviceReportRunning,
    required this.deviceReportPath,
    required this.deviceReportError,
    required this.histogram,
    required this.waveform,
    required this.waveformColumns,
    required this.waveformBins,
  });

  bool isOpen;
  String? error;
  String cameraId;
  List<String> cameraIds;
  List<String> cameraLabels;
  int iso;
  int isoMin;
  int isoMax;
  int shutterNs;
  int shutterMinNs;
  int shutterMaxNs;
  double focusDiopters;
  double focusMin;
  double focusMax;
  double focusShiftSpeed;
  String whiteBalanceMode;
  List<String> whiteBalanceModes;
  bool autoExposure;
  bool autoFocus;
  bool autoFocusSupported;
  int previewWidth;
  int previewHeight;
  int previewRotationDegrees;
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
  List<String> profileNames;
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
  String exposureMode;
  List<String> exposureModes;
  String deviceKey;
  List<String> deviceQuirks;
  bool deviceCalibrationActive;
  String? deviceCalibrationSource;
  int deviceDatabaseVersion;
  bool deviceReportOptIn;
  bool deviceReportRunning;
  String? deviceReportPath;
  String? deviceReportError;
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

  void closeCamera();

  void setIso(int iso);

  void setShutterNs(int nanoseconds);

  void setShutterAngle(double degrees);

  void setFocusDiopters(double diopters);

  void setFocusShiftSpeed(double dioptersPerSecond);

  void setWhiteBalanceMode(String mode);

  void setAutoExposure();

  /// Selects the exposure mode: P (HAL AE), S (fixed shutter, auto ISO), or M.
  void setExposureMode(String mode);

  /// Switches to another CameraManager camera ID.
  void setCameraId(String id);

  /// Selects a lens from the probe report. Hidden lenses are allowed when the
  /// probe marked them openable.
  void selectLens(String id);

  /// Applies a zoom ratio to the active camera (optical or digital).
  void setZoom(double ratio);

  /// Switches recording mode: size, frame rate and bit depth.
  void selectRecordingMode(String id);

  /// Selects single-camera or movie/vlog multi-camera mode.
  void setShootMode(String mode);

  /// Includes/excludes a lens from the persisted Movie/Vlog shoot set.
  void setShootLens(String id, bool selected);

  void setAutoFocus();

  void startRecording();

  void stopRecording();

  void setProfile(String profileId);

  void setZebra(bool enabled);

  void setFalseColor(bool enabled);

  void setPeaking(bool enabled, double sensitivity, double strength);

  void setEdgeMode(String mode);

  void setNoiseReduction(String mode);

  void setAntibanding(String mode);

  /// Opt-in for persisted device reports. Off by default.
  void setDeviceReportOptIn(bool enabled);

  /// Runs the prober on a background thread, closing preview first.
  void runDeviceReport();

  /// Reloads the bundled + user device database and reapplies calibration/quirks.
  void reloadDeviceDatabase();

  void reloadLuts();

  /// True when the app was launched to run a probe: preview must stay closed.
  bool isProbeMode();
}

@FlutterApi()
abstract class SlateCamFlutterApi {
  void onState(CameraState state);
}
