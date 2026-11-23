import 'dart:async' show Timer;
import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../pigeon/slatecam.g.dart';
import 'control_deck.dart';
import 'painters.dart';
import 'ui_tokens.dart';
import 'widgets.dart';
import 'zoom_plugin.dart';

class CaptureScreen extends StatefulWidget {
  const CaptureScreen({super.key});

  @override
  State<CaptureScreen> createState() => _CaptureScreenState();
}

class _CaptureScreenState extends State<CaptureScreen> {
  final SlateCamHostApi _host = SlateCamHostApi();

  CameraState? _state;
  int? _textureId;
  bool _busy = false;
  String? _bridgeError;
  Timer? _autoOpenTimer;

  bool _layoutInitialised = false;
  bool _deckOpen = true;
  bool _showGuides = false;
  bool _showHistogram = false;
  bool _showWaveform = false;

  String? _lastClipPath;
  String? _lastError;

  @override
  void initState() {
    super.initState();
    SlateCamFlutterApi.setUp(_StateListener(_onState));
    _autoOpenTimer = Timer(const Duration(milliseconds: 600), () async {
      if (!mounted || _textureId != null || _busy) return;
      final probeMode = await _host.isProbeMode();
      if (!mounted) return;
      if (probeMode) {
        setState(() => _bridgeError = 'Probe running — preview disabled');
        return;
      }
      await _openCamera();
    });
  }

  @override
  void dispose() {
    _autoOpenTimer?.cancel();
    super.dispose();
  }

  void _onState(CameraState state) {
    if (!mounted) return;
    setState(() => _state = state);

    if (state.clipPath != null && state.clipPath != _lastClipPath) {
      _lastClipPath = state.clipPath;
      _showSnack('Saved clip: ${state.clipPath}', UiTokens.success);
    }
    final error = state.recordingError ?? state.error;
    if (error != null && error != _lastError) {
      _lastError = error;
      _showSnack(error, UiTokens.danger);
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
      setState(() => _textureId = id);
    } catch (error) {
      if (!mounted) return;
      setState(() => _bridgeError = '$error');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _closeCamera() async {
    await _host.closeCamera();
    if (!mounted) return;
    setState(() => _textureId = null);
  }

  void _openDeck() {
    setState(() => _deckOpen = true);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) {
            if (!_layoutInitialised && constraints.maxWidth > 0) {
              _layoutInitialised = true;
              _deckOpen = constraints.maxWidth >= 760;
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
              showGuides: _showGuides,
              onGuidesChanged: (value) => setState(() => _showGuides = value),
              showHistogram: _showHistogram,
              onHistogramChanged: (value) =>
                  setState(() => _showHistogram = value),
              showWaveform: _showWaveform,
              onWaveformChanged: (value) =>
                  setState(() => _showWaveform = value),
            );

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
                    onPressed: _busy ? null : _openCamera,
                    icon: _busy
                        ? const SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.videocam_outlined),
                    label: Text(_busy ? 'Opening…' : 'Open camera'),
                  ),
                  const SizedBox(height: 12),
                  Text(
                    '1080p · HEVC · P / S / M exposure',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                  if (_bridgeError != null) ...[
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
          if (_showGuides)
            IgnorePointer(child: CustomPaint(painter: GuidesPainter())),
          IgnorePointer(
            child: DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [
                    Colors.black.withValues(alpha: 0.55),
                    Colors.transparent,
                    Colors.transparent,
                    Colors.black.withValues(alpha: 0.72),
                  ],
                  stops: const [0, 0.2, 0.68, 1],
                ),
              ),
            ),
          ),
          _topHud(state),
          _monitorRail(state),
          if (_showHistogram || _showWaveform) _monitorCards(state),
          _zoomControl(state),
          _bottomDock(state),
        ],
      ),
    );
  }

  Widget _zoomControl(CameraState state) {
    return Positioned(
      left: 8,
      right: 8,
      bottom: 82,
      child: Align(
        alignment: Alignment.centerLeft,
        child: SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: ZoomPlugin(state: state, host: _host),
        ),
      ),
    );
  }

  Widget _previewTexture(CameraState state) {
    // The GL pipeline already applies the camera/display rotation, so Flutter
    // must not rotate again. Keep the exact pixel aspect and let the frame
    // letterbox rather than stretch.
    final texture = SizedBox(
      width: state.previewWidth.toDouble(),
      height: state.previewHeight.toDouble(),
      child: Texture(textureId: _textureId!),
    );

    return Center(
      child: AspectRatio(
        aspectRatio: state.previewWidth / state.previewHeight,
        child: FittedBox(
          fit: BoxFit.contain,
          clipBehavior: Clip.hardEdge,
          child: texture,
        ),
      ),
    );
  }

  Widget _topHud(CameraState state) {
    final recording = state.isRecording;
    final error = state.recordingError ?? state.error;
    return Positioned(
      top: 8,
      left: 8,
      right: 8,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
        decoration: BoxDecoration(
          color: UiTokens.surfaceGlass,
          borderRadius: BorderRadius.circular(UiTokens.controlRadius),
          border: Border.all(color: UiTokens.border),
        ),
        child: Wrap(
          spacing: 8,
          runSpacing: 5,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            Text(
              'SLATECAM',
              style: Theme.of(context).textTheme.labelLarge
                  ?.copyWith(letterSpacing: 2.4, fontSize: 12),
            ),
            _cameraMenu(state),
            if (state.shootMode == 'movie')
              StatusPill(
                color: UiTokens.accent,
                icon: Icons.movie_outlined,
                child: const Text(
                  'MOVIE',
                  style: TextStyle(
                    color: UiTokens.accent,
                    fontSize: 10,
                    fontWeight: FontWeight.w800,
                  ),
                ),
              ),
            StatusPill(
              color: UiTokens.accent,
              child: Text(
                '${state.exposureMode} · ISO ${state.iso} · ${formatShutterFraction(state.shutterNs)}',
                style: const TextStyle(
                  color: UiTokens.accent,
                  fontSize: 10,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
            StatusPill(
              color: UiTokens.muted,
              child: Text(
                humanizeWireValue(state.whiteBalanceMode),
                style: const TextStyle(
                  color: UiTokens.muted,
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
              StatusPill(
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
            StatusPill(
              color: UiTokens.info,
              child: Text(
                '${state.previewWidth}×${state.previewHeight} · ${state.frameRate}p · '
                'HEVC${state.tenBit ? ' · 10-bit' : ''}',
                style: const TextStyle(
                  color: UiTokens.info,
                  fontSize: 10,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _cameraMenu(CameraState state) {
    final index = state.cameraIds.indexOf(state.cameraId);
    var label = index >= 0 && index < state.cameraLabels.length
        ? state.cameraLabels[index]
        : 'cam ${state.cameraId}';
    for (final lens in state.lenses) {
      if (lens.id == state.cameraId) {
        label = lens.exposed ? lens.label : '${lens.label} · hidden';
      }
    }
    return PopupMenuButton<String>(
      tooltip: 'Switch camera',
      color: UiTokens.surfaceRaised,
      onSelected: _host.setCameraId,
      itemBuilder: (context) => [
        for (var i = 0; i < state.cameraIds.length; i++)
          PopupMenuItem(
            value: state.cameraIds[i],
            child: Row(
              children: [
                Icon(
                  state.cameraIds[i] == state.cameraId
                      ? Icons.radio_button_checked
                      : Icons.radio_button_unchecked,
                  size: 16,
                  color: state.cameraIds[i] == state.cameraId
                      ? UiTokens.accent
                      : UiTokens.muted,
                ),
                const SizedBox(width: 8),
                Text(
                  i < state.cameraLabels.length
                      ? state.cameraLabels[i]
                      : 'camera ${state.cameraIds[i]}',
                ),
              ],
            ),
          ),
      ],
      child: StatusPill(
        color: UiTokens.muted,
        icon: Icons.cameraswitch_outlined,
        child: Text(
          '$label · db v${state.deviceDatabaseVersion}',
          style: const TextStyle(
            color: UiTokens.muted,
            fontSize: 10,
            fontWeight: FontWeight.w700,
          ),
        ),
      ),
    );
  }

  Widget _monitorRail(CameraState state) {
    return Positioned(
      left: 8,
      top: 70,
      bottom: 92,
      width: 92,
      child: Center(
        child: Container(
          padding: const EdgeInsets.all(4),
          decoration: BoxDecoration(
            color: UiTokens.surfaceGlass,
            borderRadius: BorderRadius.circular(14),
            border: Border.all(color: UiTokens.border),
          ),
          child: Wrap(
            spacing: 0,
            runSpacing: 0,
            children: [
              MonitorRailButton(
                icon: Icons.grid_3x3,
                tooltip: 'Framing guides',
                selected: _showGuides,
                onPressed: () => setState(() => _showGuides = !_showGuides),
              ),
              MonitorRailButton(
                icon: Icons.texture,
                tooltip: 'Zebra',
                selected: state.zebra,
                onPressed: () => _host.setZebra(!state.zebra),
              ),
              MonitorRailButton(
                icon: Icons.palette_outlined,
                tooltip: 'False colour',
                selected: state.falseColor,
                onPressed: () => _host.setFalseColor(!state.falseColor),
              ),
              MonitorRailButton(
                icon: Icons.center_focus_strong,
                tooltip: 'Focus peaking',
                selected: state.peaking,
                onPressed: () => _host.setPeaking(
                  !state.peaking,
                  state.peakingSensitivity,
                  state.peakingStrength,
                ),
              ),
              MonitorRailButton(
                icon: Icons.bar_chart,
                tooltip: 'Histogram',
                selected: _showHistogram,
                onPressed: () =>
                    setState(() => _showHistogram = !_showHistogram),
              ),
              MonitorRailButton(
                icon: Icons.show_chart,
                tooltip: 'Waveform',
                selected: _showWaveform,
                onPressed: () => setState(() => _showWaveform = !_showWaveform),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _monitorCards(CameraState state) {
    return Positioned(
      left: 108,
      bottom: 92,
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
      child: Container(
        height: 66,
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 5),
        decoration: BoxDecoration(
          color: UiTokens.surfaceGlass,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: UiTokens.border),
        ),
        child: Row(
          children: [
            _RecordButton(
              recording: state.isRecording,
              onPressed: state.isRecording
                  ? () => _host.stopRecording()
                  : () => _host.startRecording(),
            ),
            const SizedBox(width: 8),
            _ModePill(
              modes: state.exposureModes,
              selected: state.exposureMode,
              onChanged: (mode) => _host.setExposureMode(mode),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                child: Row(
                  children: [
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
                      value: humanizeWireValue(state.whiteBalanceMode),
                      icon: Icons.thermostat,
                      onTap: () => _openDeck(),
                    ),
                    const SizedBox(width: 6),
                    ReadoutChip(
                      label: 'FOCUS',
                      value: state.autoFocus
                          ? 'AF'
                          : formatDiopters(state.focusDiopters),
                      icon: Icons.center_focus_strong,
                      onTap: () => _openDeck(),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(width: 4),
            IconButton(
              tooltip: _deckOpen ? 'Hide controls' : 'Show controls',
              onPressed: () => setState(() => _deckOpen = !_deckOpen),
              icon: Icon(
                _deckOpen ? Icons.chevron_right : Icons.tune,
                color: UiTokens.accent,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _StateListener implements SlateCamFlutterApi {
  _StateListener(this._onState);

  final void Function(CameraState) _onState;

  @override
  Future<void> onState(CameraState state) async => _onState(state);
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
