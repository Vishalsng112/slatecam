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
}

@HostApi()
abstract class SlateCamHostApi {
  /// Creates the preview texture and opens the camera. Returns the Flutter
  /// texture id to render with `Texture(textureId:)`.
  int openCamera();

  void closeCamera();

  void setIso(int iso);

  void setShutterNs(int nanoseconds);

  void setFocusDiopters(double diopters);

  void setWhiteBalanceMode(String mode);

  void setAutoExposure();

  void setAutoFocus();
}

@FlutterApi()
abstract class SlateCamFlutterApi {
  void onState(CameraState state);
}
