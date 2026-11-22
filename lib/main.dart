import 'dart:math' show max;

import 'package:flutter/material.dart';

import 'src/pigeon/slatecam.g.dart';

void main() => runApp(const SlateCamApp());

class SlateCamApp extends StatelessWidget {
  const SlateCamApp({super.key});

  static const accent = Color(0xFFE8B44A);
  static const background = Color(0xFF0B0B0C);

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'SlateCam',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        brightness: Brightness.dark,
        scaffoldBackgroundColor: background,
        colorScheme: ColorScheme.fromSeed(
          seedColor: accent,
          brightness: Brightness.dark,
        ),
      ),
      home: const CaptureScreen(),
    );
  }
}

/// Dart renders camera state and sends commands. It never owns the camera.
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

  @override
  void initState() {
    super.initState();
    SlateCamFlutterApi.setUp(_StateListener(_onState));
    // A camera app should be ready to shoot, not wait for a tap.
    Future<void>.delayed(const Duration(milliseconds: 600), () {
      if (mounted && _textureId == null && !_busy) _openCamera();
    });
  }

  void _onState(CameraState state) {
    if (!mounted) return;
    setState(() => _state = state);
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

  @override
  Widget build(BuildContext context) {
    final state = _state;
    return Scaffold(
      body: SafeArea(
        child: Row(
          children: [
            Expanded(child: _buildPreview(state)),
            SizedBox(width: 340, child: _buildControls(state)),
          ],
        ),
      ),
    );
  }

  Widget _buildPreview(CameraState? state) {
    if (_textureId == null || state == null || !state.isOpen) {
      return ColoredBox(
        color: Colors.black,
        child: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Text(
                'SLATECAM',
                style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                      letterSpacing: 8,
                      fontWeight: FontWeight.w600,
                    ),
              ),
              const SizedBox(height: 8),
              Text(
                'Open-source cinema camera',
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
              ),
              const SizedBox(height: 28),
              FilledButton(
                onPressed: _busy ? null : _openCamera,
                child: Text(_busy ? 'Opening…' : 'Open camera'),
              ),
              if (_bridgeError != null) ...[
                const SizedBox(height: 16),
                SizedBox(
                  width: 300,
                  child: Text(
                    _bridgeError!,
                    textAlign: TextAlign.center,
                    style: TextStyle(color: Theme.of(context).colorScheme.error),
                  ),
                ),
              ],
            ],
          ),
        ),
      );
    }

    final quarterTurns = (state.previewRotationDegrees % 360) ~/ 90;
    Widget preview = SizedBox(
      width: state.previewWidth.toDouble(),
      height: state.previewHeight.toDouble(),
      child: Texture(textureId: _textureId!),
    );
    if (quarterTurns != 0) {
      preview = RotatedBox(quarterTurns: quarterTurns, child: preview);
    }

    return ColoredBox(
      color: Colors.black,
      child: ClipRect(
        child: SizedBox.expand(
          child: FittedBox(
            fit: BoxFit.cover,
            child: preview,
          ),
        ),
      ),
    );
  }

  Widget _buildControls(CameraState? state) {
    return ColoredBox(
      color: const Color(0xFF121214),
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text('CONTROLS', style: Theme.of(context).textTheme.labelLarge),
                if (state?.isOpen == true)
                  TextButton(
                    onPressed: _closeCamera,
                    child: const Text('Close'),
                  ),
              ],
            ),
            if (state != null && state.isOpen) ...[
              const SizedBox(height: 8),
              SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  style: FilledButton.styleFrom(
                    backgroundColor: state.isRecording
                        ? const Color(0xFFB3261E)
                        : null,
                  ),
                  onPressed: state.isRecording
                      ? () => _host.stopRecording()
                      : () => _host.startRecording(),
                  icon: Icon(state.isRecording ? Icons.stop : Icons.circle),
                  label: Text(
                    state.isRecording
                        ? 'Stop  ${_formatDuration(state.recordingSeconds)}'
                        : 'Record',
                  ),
                ),
              ),
              if (state.recordingError != null)
                Padding(
                  padding: const EdgeInsets.only(top: 6),
                  child: Text(
                    state.recordingError!,
                    style: TextStyle(color: Theme.of(context).colorScheme.error),
                  ),
                ),
              if (state.clipPath != null)
                Padding(
                  padding: const EdgeInsets.only(top: 6),
                  child: SelectableText(
                    'Saved: ${state.clipPath}',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ),
            ],
            if (state == null) ...[
              const SizedBox(height: 12),
              const Text('No camera state yet.'),
            ] else ...[
              if (state.error != null) ...[
                const SizedBox(height: 8),
                Text(
                  state.error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              ],
              const SizedBox(height: 12),
              _label('ISO', '${state.iso}'),
              Slider(
                min: state.isoMin.toDouble(),
                max: state.isoMax.toDouble(),
                value: state.iso.toDouble().clamp(
                      state.isoMin.toDouble(),
                      state.isoMax.toDouble(),
                    ),
                onChanged: (value) =>
                    setState(() => state.iso = value.round()),
                onChangeEnd: (value) => _host.setIso(value.round()),
              ),
              _label(
                'Shutter',
                '${(state.shutterNs / 1000000).toStringAsFixed(2)} ms',
              ),
              Slider(
                min: state.shutterMinNs.toDouble(),
                max: _shutterCeiling(state).toDouble(),
                value: state.shutterNs.toDouble().clamp(
                      state.shutterMinNs.toDouble(),
                      _shutterCeiling(state).toDouble(),
                    ),
                onChanged: (value) =>
                    setState(() => state.shutterNs = value.round()),
                onChangeEnd: (value) => _host.setShutterNs(value.round()),
              ),
              _label(
                'Focus',
                state.focusMax <= 0
                    ? 'fixed'
                    : '${state.focusDiopters.toStringAsFixed(2)} dioptre',
              ),
              Slider(
                max: state.focusMax > 0 ? state.focusMax : 1,
                value: state.focusMax > 0
                    ? state.focusDiopters.clamp(0, state.focusMax)
                    : 0,
                onChanged: state.focusMax <= 0
                    ? null
                    : (value) =>
                        setState(() => state.focusDiopters = value),
                onChangeEnd: state.focusMax <= 0
                    ? null
                    : (value) => _host.setFocusDiopters(value),
              ),
              _label('White balance', state.whiteBalanceMode),
              DropdownButton<String>(
                isExpanded: true,
                value: state.whiteBalanceModes.contains(state.whiteBalanceMode)
                    ? state.whiteBalanceMode
                    : null,
                items: [
                  for (final mode in state.whiteBalanceModes)
                    DropdownMenuItem(value: mode, child: Text(mode)),
                ],
                onChanged: (value) {
                  if (value == null) return;
                  setState(() => state.whiteBalanceMode = value);
                  _host.setWhiteBalanceMode(value);
                },
              ),
              _label('Profile', state.profile),
              DropdownButton<String>(
                isExpanded: true,
                value: state.profileNames.contains(state.profile)
                    ? state.profile
                    : null,
                items: [
                  for (final name in state.profileNames)
                    DropdownMenuItem(value: name, child: Text(name)),
                ],
                onChanged: (value) {
                  if (value == null) return;
                  setState(() => state.profile = value);
                  _host.setProfile(value);
                },
              ),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('Zebra'),
                value: state.zebra,
                onChanged: (value) {
                  setState(() => state.zebra = value);
                  _host.setZebra(value);
                },
              ),
              const SizedBox(height: 8),
              Text('HISTOGRAM', style: Theme.of(context).textTheme.labelSmall),
              SizedBox(
                height: 64,
                child: CustomPaint(
                  painter: _HistogramPainter(state.histogram),
                  size: Size.infinite,
                ),
              ),
              const SizedBox(height: 12),
              Text('WAVEFORM', style: Theme.of(context).textTheme.labelSmall),
              SizedBox(
                height: 64,
                child: CustomPaint(
                  painter: _WaveformPainter(
                    state.waveform,
                    state.waveformColumns,
                    state.waveformBins,
                  ),
                  size: Size.infinite,
                ),
              ),
              const SizedBox(height: 16),
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      onPressed: () => _host.setAutoExposure(),
                      child: Text(state.autoExposure ? 'AE ✓' : 'AE auto'),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: OutlinedButton(
                      onPressed: () => _host.setAutoFocus(),
                      child: Text(state.autoFocus ? 'AF ✓' : 'AF auto'),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 20),
              Text(
                'Camera state is owned by Kotlin.\n'
                'Dart only sends commands and renders snapshots.',
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                      height: 1.5,
                    ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  String _formatDuration(int seconds) {
    final minutes = (seconds ~/ 60).toString().padLeft(2, '0');
    final remainder = (seconds % 60).toString().padLeft(2, '0');
    return '$minutes:$remainder';
  }

  double _shutterCeiling(CameraState state) {
    const oneFrameAt24 = 1000000000 / 24;
    return state.shutterMaxNs < oneFrameAt24
        ? state.shutterMaxNs.toDouble()
        : oneFrameAt24.toDouble();
  }

  Widget _label(String title, String value) {
    return Padding(
      padding: const EdgeInsets.only(top: 12, bottom: 4),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(title, style: Theme.of(context).textTheme.bodyMedium),
          Text(value, style: Theme.of(context).textTheme.bodySmall),
        ],
      ),
    );
  }
}

class _StateListener implements SlateCamFlutterApi {
  _StateListener(this._onStateCallback);

  final void Function(CameraState) _onStateCallback;

  @override
  Future<void> onState(CameraState state) async => _onStateCallback(state);
}

class _HistogramPainter extends CustomPainter {
  _HistogramPainter(this.bins);

  final List<int> bins;

  @override
  void paint(Canvas canvas, Size size) {
    if (bins.isEmpty) return;
    final peak = bins.reduce(max);
    if (peak == 0) return;
    final barWidth = size.width / bins.length;
    final paint = Paint()..color = const Color(0xFFE8B44A);
    for (var i = 0; i < bins.length; i++) {
      final height = (bins[i] / peak) * size.height;
      canvas.drawRect(
        Rect.fromLTWH(i * barWidth, size.height - height, barWidth * 0.8, height),
        paint,
      );
    }
  }

  @override
  bool shouldRepaint(covariant _HistogramPainter oldDelegate) =>
      oldDelegate.bins != bins;
}

class _WaveformPainter extends CustomPainter {
  _WaveformPainter(this.data, this.columns, this.bins);

  final List<int> data;
  final int columns;
  final int bins;

  @override
  void paint(Canvas canvas, Size size) {
    if (columns <= 0 || bins <= 0 || data.length != columns * bins) return;
    final peak = data.reduce(max);
    if (peak == 0) return;

    final cellWidth = size.width / columns;
    final cellHeight = size.height / bins;
    final paint = Paint();

    for (var bin = 0; bin < bins; bin++) {
      for (var column = 0; column < columns; column++) {
        final count = data[bin * columns + column];
        if (count == 0) continue;
        paint.color = Color.fromRGBO(
          232,
          180,
          74,
          (count / peak).clamp(0.0, 1.0),
        );
        canvas.drawRect(
          Rect.fromLTWH(
            column * cellWidth,
            size.height - (bin + 1) * cellHeight,
            cellWidth,
            cellHeight,
          ),
          paint,
        );
      }
    }
  }

  @override
  bool shouldRepaint(covariant _WaveformPainter oldDelegate) => true;
}
