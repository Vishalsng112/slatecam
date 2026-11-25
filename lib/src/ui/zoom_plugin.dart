import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../pigeon/slatecam.g.dart';
import 'ui_tokens.dart';

/// Lens + zoom control driven entirely by the probe report.
///
/// The rail is a single vertical strip: min/max zoom at the ends, lens switch
/// points marked on the track, and the current zoom printed inside the thumb.
/// Lens names are no longer repeated above the bar.
class ZoomPlugin extends StatelessWidget {
  const ZoomPlugin({super.key, required this.state, required this.host});

  final CameraState state;
  final SlateCamHostApi host;

  @override
  Widget build(BuildContext context) {
    final lenses = state.lenses.where((lens) => lens.facing == 'back').toList();
    final canZoom = state.zoomMax > state.zoomMin + 0.01;
    if (!canZoom) return const SizedBox.shrink();

    final logicalActive = lenses.any(
      (lens) => lens.logical && lens.id == state.cameraId,
    );
    final activePhysical = _activePhysical(lenses);
    final base = logicalActive ? 1.0 : (activePhysical?.zoomRatio ?? 1.0);
    final min = state.zoomMin * base;
    final max = state.zoomMax * base;
    final value = (state.zoomRatio * base).clamp(min, max).toDouble();
    final marks = _lensMarks(lenses, min, max);

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 10),
      decoration: BoxDecoration(
        color: UiTokens.surfaceGlass,
        borderRadius: BorderRadius.circular(22),
        border: Border.all(color: UiTokens.border),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            _formatZoom(max),
            textAlign: TextAlign.center,
            style: const TextStyle(
              color: UiTokens.muted,
              fontSize: 10,
              fontWeight: FontWeight.w700,
              fontFeatures: [FontFeature.tabularFigures()],
            ),
          ),
          const SizedBox(height: 5),
          RotatedBox(
            quarterTurns: 3,
            child: SizedBox(
              width: 210,
              height: 48,
              child: _ZoomTrack(
                min: min,
                max: max,
                value: value,
                valueLabel: _formatZoom(value),
                marks: marks,
                onChanged: (effective) {
                  final ratio = (effective / base).clamp(
                    state.zoomMin,
                    state.zoomMax,
                  );
                  host.setZoom(ratio.toDouble());
                },
              ),
            ),
          ),
          const SizedBox(height: 5),
          Text(
            _formatZoom(min),
            textAlign: TextAlign.center,
            style: const TextStyle(
              color: UiTokens.muted,
              fontSize: 10,
              fontWeight: FontWeight.w700,
              fontFeatures: [FontFeature.tabularFigures()],
            ),
          ),
        ],
      ),
    );
  }

  LensOption? _activePhysical(List<LensOption> lenses) {
    for (final lens in lenses) {
      if (!lens.logical && lens.id == state.cameraId) return lens;
    }
    return null;
  }

  /// Absolute ratios of every lens stop inside the current zoom range.
  List<double> _lensMarks(
    List<LensOption> lenses,
    double min,
    double max,
  ) {
    final seen = <int>{};
    final marks = <double>[];
    for (final lens in lenses) {
      final ratio = lens.logical ? lens.stopRatio : lens.zoomRatio;
      if (ratio < min - 0.01 || ratio > max + 0.01) continue;
      final key = (ratio * 100).round();
      if (seen.add(key)) marks.add(ratio.clamp(min, max).toDouble());
    }
    marks.sort();
    return marks;
  }

  String _formatZoom(double value) {
    if (value < 1.0) return '${value.toStringAsFixed(2)}×';
    if (value < 10.0) return '${value.toStringAsFixed(1)}×';
    return '${value.round()}×';
  }
}

/// A full-width zoom strip with a large thumb.
///
/// The whole 48 lp area is an opaque drag target: a tap or drag anywhere jumps
/// the thumb there, so there is no tiny track or separate hit box to miss. The
/// thumb follows the finger during a gesture and holds the requested value until
/// the camera ramp catches up.
class _ZoomTrack extends StatefulWidget {
  const _ZoomTrack({
    required this.min,
    required this.max,
    required this.value,
    required this.valueLabel,
    required this.marks,
    required this.onChanged,
  });

  final double min;
  final double max;
  final double value;
  final String valueLabel;
  final List<double> marks;
  final ValueChanged<double> onChanged;

  @override
  State<_ZoomTrack> createState() => _ZoomTrackState();
}

class _ZoomTrackState extends State<_ZoomTrack> {
  double? _dragValue;

  double get _shown =>
      (_dragValue ?? widget.value).clamp(widget.min, widget.max);

  @override
  void didUpdateWidget(covariant _ZoomTrack oldWidget) {
    super.didUpdateWidget(oldWidget);
    final drag = _dragValue;
    if (drag != null && (widget.value - drag).abs() <= 0.01) {
      _dragValue = null;
    }
  }

  double _fraction(double value) {
    if (widget.max <= widget.min || widget.min <= 0) return 0;
    return (math.log(value / widget.min) /
            math.log(widget.max / widget.min))
        .clamp(0.0, 1.0)
        .toDouble();
  }

  double _valueAt(double fraction) {
    if (widget.max <= widget.min || widget.min <= 0) return widget.min;
    return widget.min *
        math.pow(widget.max / widget.min, fraction.clamp(0.0, 1.0));
  }

  void _seek(Offset local, double width) {
    if (width <= 0 || widget.max <= widget.min) return;
    final fraction = (local.dx / width).clamp(0.0, 1.0);
    final value = _valueAt(fraction);
    setState(() => _dragValue = value);
    widget.onChanged(value);
  }

  void _step(double direction) {
    // A constant ratio per step feels even across a 0.6-10x rail.
    final factor = math.pow(widget.max / widget.min, 1 / 20).toDouble();
    final value = (widget.value * (direction > 0 ? factor : 1 / factor))
        .clamp(widget.min, widget.max);
    setState(() => _dragValue = value);
    widget.onChanged(value);
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth;
        final fraction = _fraction(_shown);
        const thumbSize = 34.0;
        final trackWidth = (width - thumbSize).clamp(0.0, double.infinity);
        return Semantics(
          label: 'Zoom',
          value: widget.valueLabel,
          onIncrease: () => _step(1),
          onDecrease: () => _step(-1),
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTapDown: (details) => _seek(details.localPosition, width),
            onHorizontalDragStart: (details) =>
                _seek(details.localPosition, width),
            onHorizontalDragUpdate: (details) =>
                _seek(details.localPosition, width),
            child: SizedBox(
              height: 48,
              child: Stack(
                children: [
                  Positioned(
                    left: thumbSize / 2,
                    right: thumbSize / 2,
                    top: 21,
                    height: 6,
                    child: DecoratedBox(
                      decoration: BoxDecoration(
                        color: UiTokens.border,
                        borderRadius: BorderRadius.circular(3),
                      ),
                    ),
                  ),
                  Positioned(
                    left: thumbSize / 2,
                    top: 21,
                    height: 6,
                    width: fraction * trackWidth,
                    child: DecoratedBox(
                      decoration: BoxDecoration(
                        color: UiTokens.accent,
                        borderRadius: BorderRadius.circular(3),
                      ),
                    ),
                  ),
                  for (final mark in widget.marks)
                    Positioned(
                        left: thumbSize / 2 + _fraction(mark) * trackWidth - 1,
                        top: 14,
                        width: 2,
                        height: 20,
                        child: DecoratedBox(
                          decoration: BoxDecoration(
                            color: UiTokens.text.withValues(alpha: 0.55),
                            borderRadius: BorderRadius.circular(1),
                          ),
                        ),
                      ),
                  Positioned(
                    left: fraction * trackWidth,
                    top: (48 - thumbSize) / 2,
                    width: thumbSize,
                    height: thumbSize,
                    child: Container(
                      alignment: Alignment.center,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: UiTokens.accent,
                        border: Border.all(
                          color: UiTokens.background,
                          width: 2,
                        ),
                      ),
                      child: RotatedBox(
                        quarterTurns: 1,
                        child: FittedBox(
                          fit: BoxFit.scaleDown,
                          child: Text(
                            widget.valueLabel,
                            maxLines: 1,
                            style: const TextStyle(
                              color: Colors.black,
                              fontSize: 9,
                              fontWeight: FontWeight.w900,
                              fontFeatures: [FontFeature.tabularFigures()],
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}
