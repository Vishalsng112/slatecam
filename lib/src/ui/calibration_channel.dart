import 'dart:async';

import '../pigeon/slatecam.g.dart';

/// Fans the calibration frame pushed by the bridge out to whichever screen is
/// listening. The Pigeon FlutterApi is registered once for the app, so a
/// route-local listener cannot own it directly.
class CalibrationChannel {
  CalibrationChannel._();

  static final StreamController<CalibrationFrame> _frames =
      StreamController<CalibrationFrame>.broadcast();
  static final StreamController<String> _errors =
      StreamController<String>.broadcast();

  static Stream<CalibrationFrame> get frames => _frames.stream;
  static Stream<String> get errors => _errors.stream;

  static void pushFrame(CalibrationFrame frame) => _frames.add(frame);
  static void pushError(String message) => _errors.add(message);
}
