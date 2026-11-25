import 'dart:async';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';

import '../pigeon/slatecam.g.dart';
import 'calibration_channel.dart';
import 'ui_tokens.dart';

/// Guided ColorChecker capture and per-lens calibration.
///
/// The frame is captured through the renderer's calibration bypass, so the
/// measurement sees the camera/ISP signal before any input profile or creative
/// look. The solver runs in Kotlin in linear Rec.2020; this screen only samples
/// the patches the user aligns.
class CalibrationScreen extends StatefulWidget {
  const CalibrationScreen({
    super.key,
    required this.host,
    required this.textureId,
    required this.aspectRatio,
  });

  final SlateCamHostApi host;
  final int textureId;
  final double aspectRatio;

  @override
  State<CalibrationScreen> createState() => _CalibrationScreenState();
}

enum _Stage { framing, capturing, review, solving, result, error }

class _CalibrationScreenState extends State<CalibrationScreen> {
  static const _defaultQuad = <Offset>[
    Offset(0.05, 0.05),
    Offset(0.95, 0.05),
    Offset(0.95, 0.95),
    Offset(0.05, 0.95),
  ];

  _Stage _stage = _Stage.framing;
  CalibrationFrame? _frame;
  ui.Image? _image;
  List<Offset> _quad = List<Offset>.of(_defaultQuad);
  CalibrationSolution? _solution;
  String? _error;
  bool _saved = false;

  StreamSubscription<CalibrationFrame>? _frameSub;
  StreamSubscription<String>? _errorSub;
  Timer? _captureTimeout;

  int _dragCorner = -1;

  @override
  void initState() {
    super.initState();
    _frameSub = CalibrationChannel.frames.listen(_onFrame);
    _errorSub = CalibrationChannel.errors.listen(_onError);
  }

  @override
  void dispose() {
    _frameSub?.cancel();
    _errorSub?.cancel();
    _captureTimeout?.cancel();
    _image?.dispose();
    super.dispose();
  }

  void _onError(String message) {
    if (!mounted) return;
    _captureTimeout?.cancel();
    setState(() {
      _stage = _Stage.error;
      _error = message;
    });
  }

  Future<void> _onFrame(CalibrationFrame frame) async {
    if (!mounted || _stage != _Stage.capturing) return;
    _captureTimeout?.cancel();
    final image = await _decode(frame);
    if (!mounted) return;
    if (image == null) {
      setState(() {
        _stage = _Stage.error;
        _error = 'The captured frame could not be decoded.';
      });
      return;
    }
    _image?.dispose();
    setState(() {
      _frame = frame;
      _image = image;
      _quad = List<Offset>.of(_defaultQuad);
      _stage = _Stage.review;
      _solution = null;
      _saved = false;
    });
  }

  Future<ui.Image?> _decode(CalibrationFrame frame) async {
    try {
      final buffer = await ui.ImmutableBuffer.fromUint8List(frame.rgba);
      final descriptor = ui.ImageDescriptor.raw(
        buffer,
        width: frame.width,
        height: frame.height,
        pixelFormat: ui.PixelFormat.rgba8888,
      );
      final codec = await descriptor.instantiateCodec();
      final data = await codec.getNextFrame();
      codec.dispose();
      descriptor.dispose();
      buffer.dispose();
      return data.image;
    } catch (_) {
      return null;
    }
  }

  Future<void> _capture() async {
    setState(() {
      _stage = _Stage.capturing;
      _error = null;
      _solution = null;
      _saved = false;
    });
    _captureTimeout?.cancel();
    _captureTimeout = Timer(const Duration(seconds: 6), () {
      if (mounted && _stage == _Stage.capturing) {
        setState(() {
          _stage = _Stage.error;
          _error = 'Timed out waiting for a camera frame.';
        });
      }
    });
    try {
      await widget.host.captureCalibrationFrame();
    } catch (error) {
      _onError('Capture failed: $error');
    }
  }

  Future<void> _solve() async {
    final frame = _frame;
    if (frame == null) return;
    setState(() => _stage = _Stage.solving);
    try {
      final solution = await widget.host.solveCalibration(
        _samplePatches(frame),
        frame.cameraId,
        frame.lensKey,
      );
      if (!mounted) return;
      setState(() {
        _solution = solution;
        _stage = _Stage.result;
      });
    } catch (error) {
      _onError('Could not fit the matrix: $error');
    }
  }

  Future<void> _save() async {
    final solution = _solution;
    if (solution == null || !solution.accepted) return;
    try {
      await widget.host.saveCalibration(solution);
      if (!mounted) return;
      setState(() => _saved = true);
    } catch (error) {
      _onError('Could not save the calibration: $error');
    }
  }

  void _retake() {
    setState(() {
      _stage = _Stage.framing;
      _frame = null;
      _solution = null;
      _error = null;
      _saved = false;
      _image?.dispose();
      _image = null;
      _quad = List<Offset>.of(_defaultQuad);
    });
  }

  /// Averages a 9×9 disc inside each patch of the (possibly perspective)
  /// 6×4 chart quad. Values stay display-encoded; the bridge linearises them.
  List<CalibrationPatch> _samplePatches(CalibrationFrame frame) {
    final width = frame.width;
    final height = frame.height;
    final rgba = frame.rgba;
    final quad = _quad;

    Offset pointAt(double u, double v) {
      final top = Offset.lerp(quad[0], quad[1], u)!;
      final bottom = Offset.lerp(quad[3], quad[2], u)!;
      return Offset.lerp(top, bottom, v)!;
    }

    final patches = <CalibrationPatch>[];
    for (var row = 0; row < 4; row++) {
      for (var column = 0; column < 6; column++) {
        final u = (column + 0.5) / 6;
        final v = (row + 0.5) / 4;
        var sumR = 0.0;
        var sumG = 0.0;
        var sumB = 0.0;
        var count = 0;
        for (var dy = -4; dy <= 4; dy++) {
          for (var dx = -4; dx <= 4; dx++) {
            final du = u + dx * 0.04 / 6;
            final dv = v + dy * 0.04 / 4;
            final point = pointAt(
              du.clamp(0.0, 1.0),
              dv.clamp(0.0, 1.0),
            );
            final x = (point.dx * (width - 1)).round().clamp(0, width - 1);
            final y = (point.dy * (height - 1)).round().clamp(0, height - 1);
            final offset = (y * width + x) * 4;
            sumR += rgba[offset];
            sumG += rgba[offset + 1];
            sumB += rgba[offset + 2];
            count++;
          }
        }
        patches.add(
          CalibrationPatch(
            r: sumR / count / 255.0,
            g: sumG / count / 255.0,
            b: sumB / count / 255.0,
          ),
        );
      }
    }
    return patches;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: UiTokens.background,
      body: SafeArea(
        child: Column(
          children: [
            _header(context),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: _body(context),
              ),
            ),
            _actions(context),
          ],
        ),
      ),
    );
  }

  Widget _header(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(8, 8, 16, 4),
      child: Row(
        children: [
          IconButton(
            tooltip: 'Close',
            onPressed: () => Navigator.of(context).pop(),
            icon: const Icon(Icons.close, size: 20),
          ),
          const SizedBox(width: 4),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'COLOUR CALIBRATION',
                  style: Theme.of(context).textTheme.labelSmall,
                ),
                Text(
                  _saved
                      ? 'Saved · this lens now uses the measured matrix'
                      : '24-patch ColorChecker · per-lens input profile',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _body(BuildContext context) {
    if (_stage == _Stage.review || _stage == _Stage.solving) {
      return _review(context);
    }
    if (_stage == _Stage.result) {
      return _result(context);
    }
    return _framing(context);
  }

  Widget _framing(BuildContext context) {
    final capturing = _stage == _Stage.capturing;
    return Column(
      children: [
        Expanded(
          child: Center(
            child: AspectRatio(
              aspectRatio: widget.aspectRatio,
              child: ClipRRect(
                borderRadius: BorderRadius.circular(UiTokens.radius),
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    Texture(textureId: widget.textureId),
                    CustomPaint(
                      painter: _ChartOverlayPainter(
                        quad: _defaultQuad,
                        showHandles: false,
                      ),
                    ),
                    Positioned(
                      left: 8,
                      top: 8,
                      child: _badge('BYPASS CAPTURE'),
                    ),
                    if (capturing)
                      Container(
                        color: Colors.black.withValues(alpha: 0.55),
                        alignment: Alignment.center,
                        child: const Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            SizedBox(
                              width: 26,
                              height: 26,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            ),
                            SizedBox(height: 12),
                            Text('Capturing a clean frame…'),
                          ],
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ),
        ),
        const SizedBox(height: 12),
        _instruction(context),
      ],
    );
  }

  Widget _instruction(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: UiTokens.surface,
        borderRadius: BorderRadius.circular(UiTokens.radius),
        border: Border.all(color: UiTokens.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (_stage == _Stage.error) ...[
            Text(
              _error ?? 'Calibration failed.',
              style: const TextStyle(color: UiTokens.danger, fontSize: 12),
            ),
            const SizedBox(height: 8),
          ],
          const _Bullet(
            'Fill the preview with a 24-patch ColorChecker Classic, square-on.',
          ),
          const _Bullet('Light it evenly and softly; avoid glare and shadows.'),
          const _Bullet(
            'Keep the chart still: the capture is one frame from the live sensor path.',
          ),
          const _Bullet(
            'Auto exposure and white balance are measured as-is; the neutral patches set the trims.',
          ),
        ],
      ),
    );
  }

  Widget _review(BuildContext context) {
    final image = _image;
    final frame = _frame;
    if (image == null || frame == null) return const SizedBox.shrink();
    final solving = _stage == _Stage.solving;
    return Column(
      children: [
        Expanded(
          child: Center(
            child: AspectRatio(
              aspectRatio: frame.width / frame.height,
              child: LayoutBuilder(
                builder: (context, constraints) {
                  final size = constraints.biggest;
                  return GestureDetector(
                    behavior: HitTestBehavior.opaque,
                    onPanStart: (details) =>
                        _startDrag(details.localPosition, size),
                    onPanUpdate: (details) =>
                        _updateDrag(details.localPosition, size),
                    onPanEnd: (_) => _dragCorner = -1,
                    child: Stack(
                      fit: StackFit.expand,
                      children: [
                        RawImage(image: image, fit: BoxFit.fill),
                        CustomPaint(
                          painter: _ChartOverlayPainter(
                            quad: _quad,
                            showHandles: true,
                          ),
                        ),
                        if (solving)
                          Container(
                            color: Colors.black.withValues(alpha: 0.55),
                            alignment: Alignment.center,
                            child: const Column(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                SizedBox(
                                  width: 26,
                                  height: 26,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                  ),
                                ),
                                SizedBox(height: 12),
                                Text('Fitting the matrix…'),
                              ],
                            ),
                          ),
                      ],
                    ),
                  );
                },
              ),
            ),
          ),
        ),
        const SizedBox(height: 10),
        Text(
          'Drag the four corners onto the chart. The grid must sit inside each patch.',
          style: Theme.of(context).textTheme.bodySmall,
          textAlign: TextAlign.center,
        ),
      ],
    );
  }

  Widget _result(BuildContext context) {
    final solution = _solution;
    if (solution == null) return const SizedBox.shrink();
    final good = solution.accepted && solution.deltaEAfter <= 2.0;
    final colour = solution.accepted
        ? (good ? UiTokens.success : UiTokens.accent)
        : UiTokens.danger;
    return ListView(
      padding: const EdgeInsets.only(bottom: 12),
      children: [
        Row(
          children: [
            Expanded(
              child: _metric(
                'MEAN ΔE2000',
                '${solution.deltaEBefore.toStringAsFixed(1)} → '
                    '${solution.deltaEAfter.toStringAsFixed(2)}',
                colour,
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: _metric(
                'MAX AFTER',
                solution.deltaEMaxAfter.toStringAsFixed(2),
                solution.deltaEMaxAfter <= 4.0
                    ? UiTokens.success
                    : UiTokens.danger,
              ),
            ),
          ],
        ),
        const SizedBox(height: 10),
        Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: UiTokens.surface,
            borderRadius: BorderRadius.circular(UiTokens.radius),
            border: Border.all(color: UiTokens.border),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _row(
                'White balance',
                'R ${solution.wbROffset.toStringAsFixed(3)} · '
                    'B ${solution.wbBOffset.toStringAsFixed(3)}',
              ),
              _row('Exposure trim', solution.exposureTrim.toStringAsFixed(3)),
              _row('Lens key', solution.lensKey ?? 'not probed · camera id'),
              const Divider(height: 16, color: UiTokens.border),
              Text(
                'CCM (linear Rec.2020, row-major)',
                style: Theme.of(context).textTheme.labelSmall,
              ),
              const SizedBox(height: 4),
              SelectableText(
                _matrixText(solution.matrix),
                style: const TextStyle(
                  fontFamily: 'monospace',
                  fontSize: 11,
                  height: 1.4,
                  color: UiTokens.muted,
                ),
              ),
            ],
          ),
        ),
        if (!solution.accepted) ...[
          const SizedBox(height: 8),
          const Text(
            'Measurement rejected: no usable chart fit. Retake with the chart '
            'filling the frame, evenly lit, without glare.',
            style: TextStyle(color: UiTokens.danger, fontSize: 12),
          ),
        ] else if (!good) ...[
          const SizedBox(height: 8),
          Text(
            solution.deltaEAfter > 4.0
                ? 'High error. Re-shoot with softer, even light and a larger chart in frame.'
                : 'Acceptable, but check the corner alignment and glare for a tighter fit.',
            style: const TextStyle(color: UiTokens.accent, fontSize: 12),
          ),
        ],
        if (_saved) ...[
          const SizedBox(height: 10),
          const Row(
            children: [
              Icon(Icons.check_circle, color: UiTokens.success, size: 16),
              SizedBox(width: 6),
              Expanded(
                child: Text(
                  'Saved to the device database and applied to this lens.',
                  style: TextStyle(color: UiTokens.success, fontSize: 12),
                ),
              ),
            ],
          ),
        ],
      ],
    );
  }

  Widget _actions(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
      child: Row(
        children: [
          if (_stage == _Stage.result && !_saved) ...[
            Expanded(
              child: OutlinedButton(
                onPressed: _retake,
                child: const Text('Retake'),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: FilledButton(
                onPressed: _solution?.accepted == true ? _save : null,
                child: const Text('Save calibration'),
              ),
            ),
          ] else if (_stage == _Stage.result && _saved) ...[
            Expanded(
              child: OutlinedButton(
                onPressed: _retake,
                child: const Text('Another lens'),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: FilledButton(
                onPressed: () => Navigator.of(context).pop(),
                child: const Text('Done'),
              ),
            ),
          ] else if (_stage == _Stage.review ||
              _stage == _Stage.solving) ...[
            Expanded(
              child: OutlinedButton(
                onPressed: _stage == _Stage.solving ? null : _retake,
                child: const Text('Retake'),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: FilledButton(
                onPressed: _stage == _Stage.solving ? null : _solve,
                child: const Text('Solve'),
              ),
            ),
          ] else ...[
            Expanded(
              child: OutlinedButton(
                onPressed: _stage == _Stage.capturing
                    ? null
                    : () => Navigator.of(context).pop(),
                child: const Text('Cancel'),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: FilledButton.icon(
                onPressed: _stage == _Stage.capturing ? null : _capture,
                icon: const Icon(Icons.camera_alt_outlined, size: 17),
                label: Text(
                  _stage == _Stage.error ? 'Retry capture' : 'Capture chart',
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }

  void _startDrag(Offset local, Size size) {
    var best = 0.12 * size.shortestSide + 12;
    var index = -1;
    for (var i = 0; i < _quad.length; i++) {
      final point = Offset(
        _quad[i].dx * size.width,
        _quad[i].dy * size.height,
      );
      final distance = (point - local).distance;
      if (distance < best) {
        best = distance;
        index = i;
      }
    }
    _dragCorner = index;
  }

  void _updateDrag(Offset local, Size size) {
    final index = _dragCorner;
    if (index < 0 || size.width <= 0 || size.height <= 0) return;
    setState(() {
      _quad[index] = Offset(
        (local.dx / size.width).clamp(0.0, 1.0),
        (local.dy / size.height).clamp(0.0, 1.0),
      );
    });
  }

  Widget _badge(String text) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: 0.6),
        borderRadius: BorderRadius.circular(4),
        border: Border.all(color: UiTokens.border),
      ),
      child: Text(
        text,
        style: const TextStyle(
          fontSize: 9,
          letterSpacing: 1.1,
          color: UiTokens.muted,
        ),
      ),
    );
  }

  Widget _metric(String label, String value, Color colour) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: UiTokens.surface,
        borderRadius: BorderRadius.circular(UiTokens.radius),
        border: Border.all(color: UiTokens.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: const TextStyle(fontSize: 9, letterSpacing: 1)),
          const SizedBox(height: 4),
          Text(
            value,
            style: TextStyle(
              color: colour,
              fontSize: 20,
              fontWeight: FontWeight.w700,
            ),
          ),
        ],
      ),
    );
  }

  Widget _row(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        children: [
          Expanded(
            child: Text(label, style: const TextStyle(fontSize: 12)),
          ),
          Text(
            value,
            style: const TextStyle(fontSize: 12, color: UiTokens.muted),
          ),
        ],
      ),
    );
  }

  String _matrixText(List<double> matrix) {
    final rows = <String>[];
    for (var row = 0; row < 3; row++) {
      final values = matrix.skip(row * 3).take(3).map(
        (value) => value.toStringAsFixed(4).padLeft(8),
      );
      rows.add(values.join(' '));
    }
    return rows.join('\n');
  }
}

class _Bullet extends StatelessWidget {
  const _Bullet(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('· ', style: TextStyle(color: UiTokens.muted)),
          Expanded(
            child: Text(
              text,
              style: const TextStyle(fontSize: 12, color: UiTokens.muted),
            ),
          ),
        ],
      ),
    );
  }
}

class _ChartOverlayPainter extends CustomPainter {
  _ChartOverlayPainter({required this.quad, required this.showHandles});

  final List<Offset> quad;
  final bool showHandles;

  Offset _point(double u, double v, Size size) {
    final top = Offset.lerp(quad[0], quad[1], u)!;
    final bottom = Offset.lerp(quad[3], quad[2], u)!;
    final point = Offset.lerp(top, bottom, v)!;
    return Offset(point.dx * size.width, point.dy * size.height);
  }

  @override
  void paint(Canvas canvas, Size size) {
    if (size.isEmpty) return;
    final outline = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.4
      ..color = Colors.white.withValues(alpha: 0.9);
    final grid = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 0.7
      ..color = Colors.white.withValues(alpha: 0.42);

    for (var row = 0; row <= 4; row++) {
      canvas.drawLine(
        _point(0, row / 4, size),
        _point(1, row / 4, size),
        row == 0 || row == 4 ? outline : grid,
      );
    }
    for (var column = 0; column <= 6; column++) {
      canvas.drawLine(
        _point(column / 6, 0, size),
        _point(column / 6, 1, size),
        column == 0 || column == 6 ? outline : grid,
      );
    }

    if (!showHandles) return;
    final handleFill = Paint()..color = UiTokens.accent;
    final handleRing = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2
      ..color = Colors.black.withValues(alpha: 0.75);
    for (final corner in quad) {
      final point = Offset(corner.dx * size.width, corner.dy * size.height);
      canvas.drawCircle(point, 9, handleRing);
      canvas.drawCircle(point, 6, handleFill);
    }
  }

  @override
  bool shouldRepaint(_ChartOverlayPainter oldDelegate) =>
      oldDelegate.showHandles != showHandles ||
      !_sameQuad(oldDelegate.quad, quad);

  bool _sameQuad(List<Offset> a, List<Offset> b) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (a[i] != b[i]) return false;
    }
    return true;
  }
}
