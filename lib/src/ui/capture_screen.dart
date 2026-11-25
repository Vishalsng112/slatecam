import 'dart:async' show Timer;
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../pigeon/slatecam.g.dart';
import 'control_deck.dart';
import 'painters.dart';
import 'playback_screen.dart';
import 'ui_tokens.dart';
import 'widgets.dart';
import 'zoom_plugin.dart';

class CaptureScreen extends StatefulWidget {
  const CaptureScreen({super.key});

  @override
  State<CaptureScreen> createState() => _CaptureScreenState();
}

class _CaptureScreenState extends State<CaptureScreen>
    with WidgetsBindingObserver {
  final SlateCamHostApi _host = SlateCamHostApi();

  CameraState? _state;
  int? _textureId;
  bool _busy = false;
  String? _bridgeError;
  bool _permissionMissing = false;
  Timer? _autoOpenTimer;

  bool _layoutInitialised = false;
  bool _deckOpen = true;
  bool _showGuides = false;
  bool _showHistogram = false;
  bool _showWaveform = false;
  final ValueNotifier<CameraTelemetry?> _telemetry =
      ValueNotifier<CameraTelemetry?>(null);
  final ValueNotifier<AudioTelemetry?> _audio = ValueNotifier<AudioTelemetry?>(
    null,
  );

  String? _lastClipPath;
  String? _lastError;

  Offset? _focusReticle;
  Timer? _focusReticleTimer;
  double _scaleStartZoom = 1;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    SlateCamFlutterApi.setUp(_StateListener(_onState, _onTelemetry, _onAudio));
    _autoOpenTimer = Timer(const Duration(milliseconds: 600), _autoOpen);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _autoOpenTimer?.cancel();
    _focusReticleTimer?.cancel();
    _telemetry.dispose();
    _audio.dispose();
    super.dispose();
  }

  /// Android resets runtime permissions when app data is cleared, so a first
  /// launch (or a reinstall) must not try to open the camera before the user
  /// has answered the prompt. Opening early throws a SecurityException and
  /// leaves nothing to recover with, so we ask first and retry on resume.
  Future<void> _autoOpen({bool prompt = true}) async {
    if (!mounted || _textureId != null || _busy) return;
    final probeMode = await _host.isProbeMode();
    if (!mounted) return;
    if (probeMode) {
      setState(() => _bridgeError = 'Probe running — preview disabled');
      return;
    }
    final granted = await _host.cameraPermissionGranted();
    if (!mounted) return;
    if (!granted) {
      if (prompt) await _host.requestCameraPermissions();
      if (!mounted) return;
      setState(() {
        _permissionMissing = true;
        _bridgeError = null;
      });
      return;
    }
    setState(() => _permissionMissing = false);
    await _openCamera();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // The permission prompt pauses and resumes the activity; re-checking here
    // is what makes the first-run flow recover after the user taps Allow.
    if (state == AppLifecycleState.resumed && _textureId == null && !_busy) {
      _autoOpen(prompt: false);
    }
    if (state == AppLifecycleState.resumed && _textureId != null) {
      // Permission can be granted from the system prompt/settings while the
      // camera stays open; re-enumerate inputs and retry the monitor.
      _host.refreshAudioInputs();
    }
  }

  void _onState(CameraState state) {
    if (!mounted) return;
    setState(() => _state = state);
    _notifyClipAndError(state);
  }

  /// Merges the high-frequency readouts into the last full state. This keeps
  /// the live part of the UI updating without re-sending the heavy recording
  /// mode / profile / device lists on every analysis tick.
  void _onTelemetry(CameraTelemetry telemetry) {
    final state = _state;
    if (!mounted || state == null) return;
    state.iso = telemetry.iso;
    state.shutterNs = telemetry.shutterNs;
    state.shutterAngle = telemetry.shutterAngle;
    state.focusDiopters = telemetry.focusDiopters;
    state.whiteBalanceMode = telemetry.whiteBalanceMode;
    state.whiteBalanceKelvin = telemetry.whiteBalanceKelvin;
    state.whiteBalanceTint = telemetry.whiteBalanceTint;
    state.zoomRatio = telemetry.zoomRatio;
    state.isRecording = telemetry.isRecording;
    state.recordingSeconds = telemetry.recordingSeconds;
    state.clipPath = telemetry.clipPath;
    state.recordingError = telemetry.recordingError;
    state.frameRate = telemetry.frameRate;
    state.gyroSampleCount = telemetry.gyroSampleCount;
    state.exposureMode = telemetry.exposureMode;
    state.meteringSpeed = telemetry.meteringSpeed;
    state.zebra = telemetry.zebra;
    state.falseColor = telemetry.falseColor;
    state.peaking = telemetry.peaking;
    state.peakingSensitivity = telemetry.peakingSensitivity;
    state.peakingStrength = telemetry.peakingStrength;
    state.tenBit = telemetry.tenBit;
    state.hlgAvailable = telemetry.hlgAvailable;
    state.hlgReason = telemetry.hlgReason;
    state.activePhysicalCameraId = telemetry.activePhysicalCameraId;
    state.histogram = telemetry.histogram;
    state.waveform = telemetry.waveform;
    state.waveformColumns = telemetry.waveformColumns;
    state.waveformBins = telemetry.waveformBins;
    _telemetry.value = telemetry;
    _notifyClipAndError(state);
  }

  /// The audio meter is deliberately separate from camera telemetry so the
  /// 10 Hz update only rebuilds the meter, not the console or the viewfinder.
  void _onAudio(AudioTelemetry audio) {
    if (!mounted) return;
    _audio.value = audio;
  }

  void _notifyClipAndError(CameraState state) {
    if (state.clipPath != null && state.clipPath != _lastClipPath) {
      _lastClipPath = state.clipPath;
      _showSnack('Saved clip: ${state.clipPath}', UiTokens.success);
    }
    final error = state.recordingError ?? state.error;
    if (error != null && error != _lastError) {
      _lastError = error;
      _showSnack(_openErrorMessage(error), UiTokens.danger);
    }
  }

  void _showSnack(String message, Color color) {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      final messenger = ScaffoldMessenger.maybeOf(context);
      messenger?.showSnackBar(
        SnackBar(
          content: Text(message, style: TextStyle(color: color)),
          backgroundColor: UiTokens.surfaceRaised,
          behavior: SnackBarBehavior.floating,
          duration: const Duration(seconds: 4),
        ),
      );
    });
  }

  Future<void> _openCamera() async {
    setState(() {
      _busy = true;
      _bridgeError = null;
    });
    try {
      final id = await _host.openCamera();
      if (!mounted) return;
      setState(() {
        _textureId = id;
        _permissionMissing = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _bridgeError = _openErrorMessage(error);
        _permissionMissing = _isPermissionError(error);
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  bool _isPermissionError(Object error) =>
      '$error'.toLowerCase().contains('permission');

  String _openErrorMessage(Object error) {
    if (_isPermissionError(error)) {
      return 'SlateCam needs camera access to show the viewfinder.';
    }
    return '$error';
  }

  Future<void> _closeCamera() async {
    await _host.closeCamera();
    if (!mounted) return;
    setState(() => _textureId = null);
  }

  void _openDeck() {
    setState(() => _deckOpen = true);
  }

  Future<void> _openClips() async {
    if (_state?.isRecording ?? false) {
      _showSnack('Stop recording before opening clips.', UiTokens.danger);
      return;
    }
    if (_textureId != null) {
      await _closeCamera();
    }
    if (!mounted) return;
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (context) => PlaybackScreen(host: _host),
      ),
    );
    if (mounted) _autoOpen(prompt: false);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) {
            final portrait = constraints.maxHeight > constraints.maxWidth;
            if (!_layoutInitialised && constraints.maxWidth > 0) {
              _layoutInitialised = true;
              // Portrait keeps the console docked below the viewfinder so both
              // are usable; landscape docks it beside the image on wide screens.
              _deckOpen = constraints.maxWidth >= 760 || portrait;
            }

            final state = _state;
            final live = state != null && state.isOpen && _textureId != null;

            if (!live) return _launchView();

            final preview = _previewView(state);
            final deckWidth = constraints.maxWidth >= 1400
                ? 760.0
                : constraints.maxWidth >= 1000
                ? 600.0
                : constraints.maxWidth >= 760
                ? 560.0
                : math.min(360.0, math.max(300.0, constraints.maxWidth * 0.6));
            final deck = ControlDeck(
              state: state,
              host: _host,
              onClose: () => setState(() => _deckOpen = false),
              onCloseCamera: _closeCamera,
              onOpenClips: _openClips,
              showGuides: _showGuides,
              onGuidesChanged: (value) => setState(() => _showGuides = value),
              showHistogram: _showHistogram,
              onHistogramChanged: (value) {
                setState(() => _showHistogram = value);
                _host.setAnalysisOverlayVisible(value || _showWaveform);
              },
              showWaveform: _showWaveform,
              onWaveformChanged: (value) {
                setState(() => _showWaveform = value);
                _host.setAnalysisOverlayVisible(value || _showHistogram);
              },
              telemetry: _telemetry,
              audio: _audio,
            );

            if (portrait) {
              // One responsive arrangement for phones and tablets: the same
              // preview, zoom rail and console widgets, stacked instead of
              // duplicated for a second orientation.
              final deckHeight = math.min(
                460.0,
                math.max(300.0, constraints.maxHeight * 0.48),
              );
              return Column(
                children: [
                  Expanded(child: preview),
                  if (_deckOpen) SizedBox(height: deckHeight, child: deck),
                ],
              );
            }

            if (constraints.maxWidth >= 760) {
              return Row(
                children: [
                  Expanded(child: preview),
                  if (_deckOpen) SizedBox(width: deckWidth, child: deck),
                ],
              );
            }

            return Stack(
              children: [
                preview,
                if (_deckOpen)
                  Positioned(
                    top: 0,
                    right: 0,
                    bottom: 0,
                    width: deckWidth,
                    child: deck,
                  ),
              ],
            );
          },
        ),
      ),
    );
  }

  Widget _launchView() {
    return ColoredBox(
      color: UiTokens.background,
      child: Stack(
        children: [
          const Positioned.fill(
            child: DecoratedBox(
              decoration: BoxDecoration(
                gradient: RadialGradient(
                  center: Alignment(0.2, -0.3),
                  radius: 1.2,
                  colors: [Color(0xFF202018), UiTokens.background],
                ),
              ),
            ),
          ),
          Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    'SLATECAM',
                    style: Theme.of(context).textTheme.headlineMedium,
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'Open-source cinema camera',
                    style: Theme.of(context).textTheme.bodyMedium
                        ?.copyWith(color: UiTokens.muted),
                  ),
                  const SizedBox(height: 28),
                  FilledButton.icon(
                    onPressed: _busy
                        ? null
                        : () =>
                              _permissionMissing ? _autoOpen() : _openCamera(),
                    icon: _busy
                        ? const SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : Icon(
                            _permissionMissing
                                ? Icons.lock_open_outlined
                                : Icons.videocam_outlined,
                          ),
                    label: Text(
                      _busy
                          ? 'Opening…'
                          : _permissionMissing
                          ? 'Grant camera access'
                          : 'Open camera',
                    ),
                  ),
                  const SizedBox(height: 12),
                  Text(
                    '4:3 · 1080p · HEVC · P / S / M exposure',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                  if (_permissionMissing) ...[
                    const SizedBox(height: 18),
                    Container(
                      constraints: const BoxConstraints(maxWidth: 440),
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: UiTokens.surfaceRaised,
                        borderRadius: BorderRadius.circular(UiTokens.radius),
                        border: Border.all(color: UiTokens.muted),
                      ),
                      child: Text(
                        'Camera access is required to show the viewfinder. '
                        'If no prompt appears, allow Camera for SlateCam in '
                        'the system settings.',
                        textAlign: TextAlign.center,
                        style: TextStyle(color: UiTokens.muted),
                      ),
                    ),
                  ],
                  if (_bridgeError != null && !_permissionMissing) ...[
                    const SizedBox(height: 18),
                    Container(
                      constraints: const BoxConstraints(maxWidth: 440),
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: UiTokens.danger.withValues(alpha: 0.1),
                        borderRadius: BorderRadius.circular(UiTokens.radius),
                        border: Border.all(
                          color: UiTokens.danger.withValues(alpha: 0.4),
                        ),
                      ),
                      child: Text(
                        _bridgeError!,
                        textAlign: TextAlign.center,
                        style: const TextStyle(color: UiTokens.danger),
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _previewView(CameraState state) {
    return ClipRect(
      child: Stack(
        fit: StackFit.expand,
        children: [
          ColoredBox(color: Colors.black, child: _previewTexture(state)),
          ValueListenableBuilder<CameraTelemetry?>(
            valueListenable: _telemetry,
            builder: (context, _, _) => _statusStrip(state),
          ),
          _viewfinderQuality(state),
          if (_showHistogram || _showWaveform)
            ValueListenableBuilder<CameraTelemetry?>(
              valueListenable: _telemetry,
              builder: (context, _, _) => _monitorCards(state),
            ),
          ValueListenableBuilder<CameraTelemetry?>(
            valueListenable: _telemetry,
            builder: (context, _, _) => _zoomControl(state),
          ),
          ValueListenableBuilder<CameraTelemetry?>(
            valueListenable: _telemetry,
            builder: (context, _, _) => _bottomDock(state),
          ),
          ValueListenableBuilder<CameraTelemetry?>(
            valueListenable: _telemetry,
            builder: (context, telemetry, _) {
              final recording = telemetry?.isRecording ?? state.isRecording;
              if (!recording) return const SizedBox.shrink();
              return Positioned.fill(
                child: IgnorePointer(
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                      border: Border.all(color: UiTokens.danger, width: 3),
                    ),
                  ),
                ),
              );
            },
          ),
        ],
      ),
    );
  }

  Widget _zoomControl(CameraState state) {
    return Positioned(
      top: 64,
      right: 4,
      bottom: 76,
      width: 76,
      child: Center(
        child: FittedBox(
          fit: BoxFit.scaleDown,
          child: ZoomPlugin(state: state, host: _host),
        ),
      ),
    );
  }

  /// Viewfinder resolution picker. It only changes the preview surface: the
  /// camera stream, encoder and recorded file stay at the recording mode.
  Widget _viewfinderQuality(CameraState state) {
    final output = state.previewOutputWidth > 0
        ? '${state.previewOutputWidth}×${state.previewOutputHeight}'
        : '—';
    final label = switch (state.previewQuality) {
      '1080' => 'VF 1080',
      '720' => 'VF 720',
      _ => 'VF Full',
    };
    return Positioned(
      top: 8,
      right: 8,
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          _rotationToggle(),
          const SizedBox(width: 6),
          PopupMenuButton<String>(
            tooltip: 'Viewfinder resolution · preview $output',
            color: UiTokens.surfaceRaised,
            onSelected: _host.setPreviewQuality,
            itemBuilder: (context) => [
              _qualityItem(
                'full',
                'Full',
                'Preview $output · recording size',
                state.previewQuality == 'full',
              ),
              _qualityItem(
                '1080',
                '1080p',
                'Cap the viewfinder at 1080 high',
                state.previewQuality == '1080',
              ),
              _qualityItem(
                '720',
                '720p',
                'Cap the viewfinder at 720 high',
                state.previewQuality == '720',
              ),
            ],
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 6),
              decoration: BoxDecoration(
                color: UiTokens.surfaceGlass,
                borderRadius: BorderRadius.circular(999),
                border: Border.all(color: UiTokens.border),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Icon(
                    Icons.visibility_outlined,
                    size: 12,
                    color: UiTokens.muted,
                  ),
                  const SizedBox(width: 4),
                  Text(
                    label,
                    style: const TextStyle(
                      fontSize: 10,
                      fontWeight: FontWeight.w700,
                      color: UiTokens.muted,
                    ),
                  ),
                  const Icon(
                    Icons.expand_more,
                    size: 13,
                    color: UiTokens.muted,
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  /// One-tap orientation switch. The activity stays alive; the bridge
  /// recomputes preview rotation and the MP4 hint for the new display.
  Widget _rotationToggle() {
    final portrait = MediaQuery.orientationOf(context) == Orientation.portrait;
    final label = portrait ? 'Rotate to landscape' : 'Rotate to portrait';
    return Tooltip(
      message: label,
      child: Semantics(
        button: true,
        label: label,
        child: Material(
          color: UiTokens.surfaceGlass,
          shape: const CircleBorder(),
          child: InkWell(
            customBorder: const CircleBorder(),
            onTap: () {
              SystemChrome.setPreferredOrientations(
                portrait
                    ? const [
                        DeviceOrientation.landscapeLeft,
                        DeviceOrientation.landscapeRight,
                      ]
                    : const [DeviceOrientation.portraitUp],
              );
            },
            child: SizedBox(
              width: 32,
              height: 32,
              child: Icon(
                portrait ? Icons.screen_rotation : Icons.stay_current_portrait,
                size: 16,
                color: UiTokens.muted,
              ),
            ),
          ),
        ),
      ),
    );
  }

  PopupMenuItem<String> _qualityItem(
    String value,
    String title,
    String detail,
    bool selected,
  ) {
    return PopupMenuItem<String>(
      value: value,
      height: 54,
      child: Row(
        children: [
          Icon(
            selected ? Icons.check : Icons.circle_outlined,
            size: 13,
            color: selected ? UiTokens.accent : UiTokens.muted,
          ),
          const SizedBox(width: 8),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(title, style: const TextStyle(fontSize: 12)),
              Text(
                detail,
                style: const TextStyle(fontSize: 10, color: UiTokens.muted),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _previewTexture(CameraState state) {
    // The GL pipeline renders the same sensor-upright frame for preview and
    // encoder. Flutter applies the display quarter turn from the MP4 hint, so
    // the viewfinder is exactly the composition that will play back.
    final outputWidth = state.previewOutputWidth > 0
        ? state.previewOutputWidth
        : state.previewWidth;
    final outputHeight = state.previewOutputHeight > 0
        ? state.previewOutputHeight
        : state.previewHeight;
    final turns = (state.recordingOrientationHint ~/ 90) % 4;
    final rotated = turns.isOdd;
    final imageAspect = rotated
        ? outputHeight / outputWidth
        : outputWidth / outputHeight;
    Widget texture = SizedBox(
      width: outputWidth.toDouble(),
      height: outputHeight.toDouble(),
      child: Texture(textureId: _textureId!),
    );
    if (turns != 0) {
      texture = RotatedBox(quarterTurns: turns, child: texture);
    }

    // Portrait fills the viewfinder edge to edge like a phone camera; the
    // overflow is cropped instead of leaving side bars. Landscape keeps the
    // full frame visible beside the console.
    final cover = MediaQuery.orientationOf(context) == Orientation.portrait;
    if (!cover) {
      return Center(
        child: AspectRatio(
          aspectRatio: imageAspect,
          child: LayoutBuilder(
            builder: (context, constraints) {
              final size = constraints.biggest;
              return _previewGesture(
                state: state,
                turns: turns,
                size: size,
                fit: BoxFit.contain,
                normalize: (point) => (
                  (point.dx / size.width).clamp(0.0, 1.0),
                  (point.dy / size.height).clamp(0.0, 1.0),
                ),
                texture: texture,
              );
            },
          ),
        ),
      );
    }

    return LayoutBuilder(
      builder: (context, constraints) {
        final size = constraints.biggest;
        final paneAspect = size.width / size.height;
        return _previewGesture(
          state: state,
          turns: turns,
          size: size,
          fit: BoxFit.cover,
          normalize: (point) {
            var nx = (point.dx / size.width).clamp(0.0, 1.0);
            var ny = (point.dy / size.height).clamp(0.0, 1.0);
            if (paneAspect > imageAspect) {
              // Scaled to width: the top/bottom overflow is cropped.
              final visible = imageAspect / paneAspect;
              ny = ((ny - (1 - visible) / 2) / visible).clamp(0.0, 1.0);
            } else {
              // Scaled to height: the left/right overflow is cropped.
              final visible = paneAspect / imageAspect;
              nx = ((nx - (1 - visible) / 2) / visible).clamp(0.0, 1.0);
            }
            return (nx, ny);
          },
          texture: texture,
        );
      },
    );
  }

  Widget _previewGesture({
    required CameraState state,
    required int turns,
    required Size size,
    required BoxFit fit,
    required (double, double) Function(Offset) normalize,
    required Widget texture,
  }) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTapUp: (details) {
        final (x, y) = normalize(details.localPosition);
        _focusAt(x, y, turns, details.localPosition);
      },
      onScaleStart: (_) => _scaleStartZoom = state.zoomRatio,
      onScaleUpdate: (details) {
        if (details.pointerCount < 2) return;
        final zoom = (_scaleStartZoom * details.scale)
            .clamp(state.zoomMin, state.zoomMax)
            .toDouble();
        _host.setZoom(zoom);
      },
      child: Stack(
        fit: StackFit.expand,
        children: [
          FittedBox(fit: fit, clipBehavior: Clip.hardEdge, child: texture),
          if (_showGuides)
            IgnorePointer(child: CustomPaint(painter: GuidesPainter())),
          if (_focusReticle != null)
            Positioned(
              left: _focusReticle!.dx - 16,
              top: _focusReticle!.dy - 16,
              child: IgnorePointer(
                child: Container(
                  width: 32,
                  height: 32,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    border: Border.all(color: UiTokens.accent, width: 1.5),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }

  void _focusAt(double dx, double dy, int turns, Offset reticle) {
    // Undo the presentation rotation before handing the point to the GL
    // viewfinder-to-buffer transform.
    final (x, y) = switch (turns % 4) {
      1 => (dy, 1 - dx),
      2 => (1 - dx, 1 - dy),
      3 => (1 - dy, dx),
      _ => (dx, dy),
    };
    _host.setFocusPoint(x, y);
    setState(() => _focusReticle = reticle);
    _focusReticleTimer?.cancel();
    _focusReticleTimer = Timer(const Duration(milliseconds: 900), () {
      if (mounted) setState(() => _focusReticle = null);
    });
  }

  /// Debug status only: the output format plus record and error state. Every
  /// other control lives in the control deck, so the preview image stays
  /// unobstructed.
  Widget _statusStrip(CameraState state) {
    final recording = state.isRecording;
    final error = state.recordingError ?? state.error;
    final output =
        '${state.previewWidth}×${state.previewHeight} · ${state.frameRate}p · '
        'HEVC${state.tenBit ? ' · 10-bit' : ''}';
    return Positioned(
      top: 8,
      left: 8,
      right: 108,
      child: Align(
        alignment: Alignment.topLeft,
        child: Wrap(
          spacing: 6,
          runSpacing: 6,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            StatusPill(
              color: UiTokens.info,
              child: Text(
                output,
                style: const TextStyle(
                  color: UiTokens.info,
                  fontSize: 10,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
            if (recording)
              StatusPill(
                color: UiTokens.danger,
                icon: Icons.fiber_manual_record,
                child: Text(
                  'REC ${formatDuration(state.recordingSeconds)}'
                  '${state.gyroSampleCount > 0 ? ' · gyro ${state.gyroSampleCount}' : ''}',
                  style: const TextStyle(
                    color: UiTokens.danger,
                    fontSize: 10,
                    fontWeight: FontWeight.w800,
                  ),
                ),
              ),
            if (error != null)
              Tooltip(
                message: error,
                child: StatusPill(
                  color: UiTokens.danger,
                  icon: Icons.warning_amber,
                  child: const Text(
                    'ERROR',
                    style: TextStyle(
                      color: UiTokens.danger,
                      fontSize: 10,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _monitorCards(CameraState state) {
    return Positioned(
      left: 8,
      bottom: 112,
      width: 224,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (_showHistogram)
            _monitorCard(
              'HISTOGRAM',
              SizedBox(
                height: 54,
                child: CustomPaint(
                  painter: HistogramPainter(state.histogram),
                  size: Size.infinite,
                ),
              ),
            ),
          if (_showHistogram && _showWaveform) const SizedBox(height: 6),
          if (_showWaveform)
            _monitorCard(
              'WAVEFORM',
              SizedBox(
                height: 54,
                child: CustomPaint(
                  painter: WaveformPainter(
                    state.waveform,
                    state.waveformColumns,
                    state.waveformBins,
                  ),
                  size: Size.infinite,
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _monitorCard(String title, Widget child) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(8, 6, 8, 6),
      decoration: BoxDecoration(
        color: UiTokens.surfaceGlass,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: UiTokens.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: Theme.of(context).textTheme.labelSmall
                ?.copyWith(fontSize: 8),
          ),
          const SizedBox(height: 2),
          child,
        ],
      ),
    );
  }

  Widget _bottomDock(CameraState state) {
    return Positioned(
      left: 8,
      right: 8,
      bottom: 8,
      child: Row(
        children: [
          _RecordButton(
            recording: state.isRecording,
            onPressed: state.isRecording
                ? () => _host.stopRecording()
                : () => _host.startRecording(),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(
                children: [
                  _ModePill(
                    modes: state.exposureModes,
                    selected: state.exposureMode,
                    onChanged: (mode) => _host.setExposureMode(mode),
                  ),
                  const SizedBox(width: 6),
                  ReadoutChip(
                    label: 'ISO',
                    value: state.exposureMode == 'S'
                        ? 'auto ${state.iso}'
                        : '${state.iso}',
                    icon: Icons.iso,
                    onTap: () => _openDeck(),
                  ),
                  const SizedBox(width: 6),
                  ReadoutChip(
                    label: 'SHUTTER',
                    value: state.exposureMode == 'P'
                        ? 'auto'
                        : formatShutterFraction(state.shutterNs),
                    icon: Icons.shutter_speed,
                    onTap: () => _openDeck(),
                  ),
                  const SizedBox(width: 6),
                  ReadoutChip(
                    label: 'WB',
                    value: state.whiteBalanceMode == 'manual'
                        ? '${state.whiteBalanceKelvin}K'
                        : humanizeWireValue(state.whiteBalanceMode),
                    icon: Icons.thermostat,
                    onTap: () => _openDeck(),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(width: 6),
          _deckToggle(),
        ],
      ),
    );
  }

  Widget _deckToggle() {
    final label = _deckOpen ? 'Hide controls' : 'Show controls';
    return Tooltip(
      message: label,
      child: Semantics(
        button: true,
        label: label,
        child: Material(
          color: UiTokens.surfaceGlass,
          shape: const CircleBorder(),
          child: InkWell(
            customBorder: const CircleBorder(),
            onTap: () => setState(() => _deckOpen = !_deckOpen),
            child: SizedBox(
              width: 44,
              height: 44,
              child: Icon(
                _deckOpen ? Icons.chevron_right : Icons.tune,
                color: UiTokens.accent,
                size: 20,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _StateListener implements SlateCamFlutterApi {
  _StateListener(this._onState, this._onTelemetry, this._onAudio);

  final void Function(CameraState) _onState;
  final void Function(CameraTelemetry) _onTelemetry;
  final void Function(AudioTelemetry) _onAudio;

  @override
  Future<void> onState(CameraState state) async => _onState(state);

  @override
  Future<void> onTelemetry(CameraTelemetry telemetry) async =>
      _onTelemetry(telemetry);

  @override
  Future<void> onAudio(AudioTelemetry audio) async => _onAudio(audio);
}

class _RecordButton extends StatelessWidget {
  const _RecordButton({required this.recording, required this.onPressed});

  final bool recording;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      label: recording ? 'Stop recording' : 'Start recording',
      child: Material(
        color: Colors.transparent,
        shape: const CircleBorder(),
        child: InkWell(
          customBorder: const CircleBorder(),
          onTap: onPressed,
          child: Container(
            width: 54,
            height: 54,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: recording ? UiTokens.danger : Colors.transparent,
              border: Border.all(
                color: recording ? UiTokens.danger : UiTokens.accent,
                width: 2,
              ),
            ),
            child: Center(
              child: recording
                  ? const Icon(Icons.stop, color: Colors.white, size: 26)
                  : Container(
                      width: 30,
                      height: 30,
                      decoration: const BoxDecoration(
                        color: UiTokens.danger,
                        shape: BoxShape.circle,
                      ),
                    ),
            ),
          ),
        ),
      ),
    );
  }
}

class _ModePill extends StatelessWidget {
  const _ModePill({
    required this.modes,
    required this.selected,
    required this.onChanged,
  });

  final List<String> modes;
  final String selected;
  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(3),
      decoration: BoxDecoration(
        color: UiTokens.surfaceRaised,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: UiTokens.border),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          for (final mode in modes)
            InkWell(
              borderRadius: BorderRadius.circular(999),
              onTap: () => onChanged(mode),
              child: AnimatedContainer(
                duration: const Duration(milliseconds: 140),
                padding: const EdgeInsets.symmetric(
                  horizontal: 10,
                  vertical: 8,
                ),
                decoration: BoxDecoration(
                  color: selected == mode
                      ? UiTokens.accent
                      : Colors.transparent,
                  borderRadius: BorderRadius.circular(999),
                ),
                child: Text(
                  mode,
                  style: TextStyle(
                    color: selected == mode
                        ? const Color(0xFF241800)
                        : UiTokens.muted,
                    fontSize: 11,
                    fontWeight: FontWeight.w800,
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}
