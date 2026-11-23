import 'package:pigeon/pigeon.dart';

/// Immutable camera snapshot. Kotlin owns this state; Dart only renders it.
class CameraState {
  CameraState({
    required this.isOpen,
    required this.error,
    required this.iso,
    required this.isoMin,
    required this.isoMax,
    required this.shutterNs,
    required this.shutterMinNs,
    required this.shutterMaxNs,
    required this.focusDiopters,
    required this.focusMin,
    required this.focusMax,
    required this.whiteBalanceMode,
    required this.whiteBalanceModes,
    required this.autoExposure,
    required this.autoFocus,
    required this.previewWidth,
    required this.previewHeight,
    required this.previewRotationDegrees,
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
    required this.edgeMode,
    required this.edgeModes,
    required this.noiseReduction,
    required this.noiseReductionModes,
    required this.antibanding,
    required this.antibandingModes,
    required this.histogram,
    required this.waveform,
    required this.waveformColumns,
    required this.waveformBins,
  });

  bool isOpen;
  String? error;
  int iso;
  int isoMin;
  int isoMax;
  int shutterNs;
  int shutterMinNs;
  int shutterMaxNs;
  double focusDiopters;
  double focusMin;
  double focusMax;
  String whiteBalanceMode;
  List<String> whiteBalanceModes;
  bool autoExposure;
  bool autoFocus;
  int previewWidth;
  int previewHeight;
  int previewRotationDegrees;
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
  String edgeMode;
  List<String> edgeModes;
  String noiseReduction;
  List<String> noiseReductionModes;
  String antibanding;
  List<String> antibandingModes;
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

  void setWhiteBalanceMode(String mode);

  void setAutoExposure();

  void setAutoFocus();

  void startRecording();

  void stopRecording();

  void setProfile(String profileId);

  void setZebra(bool enabled);

  void setFalseColor(bool enabled);

  void setEdgeMode(String mode);

  void setNoiseReduction(String mode);

  void setAntibanding(String mode);

  void reloadLuts();

  /// True when the app was launched to run a probe: preview must stay closed.
  bool isProbeMode();
}

@FlutterApi()
abstract class SlateCamFlutterApi {
  void onState(CameraState state);
}
