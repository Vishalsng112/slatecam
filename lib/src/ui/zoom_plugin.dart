import 'package:flutter/material.dart';

import '../pigeon/slatecam.g.dart';
import 'ui_tokens.dart';

/// Lens + zoom control driven entirely by the probe report.
///
/// The chip row lists the back lenses the prober verified (including
/// vendor-hidden lenses it could open). The slider applies a continuous zoom
/// ratio to the active camera, and the readout is normalised to the default
/// lens so `0.62x` means the same thing on every phone.
class ZoomPlugin extends StatelessWidget {
  const ZoomPlugin({super.key, required this.state, required this.host});

  final CameraState state;
  final SlateCamHostApi host;

  @override
  Widget build(BuildContext context) {
    final lenses = state.lenses.where((lens) => lens.facing == 'back').toList();
    final canZoom = state.zoomMax > state.zoomMin + 0.01;
    if (lenses.isEmpty && !canZoom) return const SizedBox.shrink();

    final active = _activeLens(lenses);
    final logicalActive = lenses.any(
      (lens) => lens.logical && lens.id == state.cameraId,
    );
    final effective = (logicalActive
            ? state.zoomRatio
            : (active?.zoomRatio ?? 1.0) * state.zoomRatio)
        .clamp(0.1, 999.0)
        .toDouble();

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 8),
      decoration: BoxDecoration(
        color: UiTokens.surfaceGlass,
        borderRadius: BorderRadius.circular(22),
        border: Border.all(color: UiTokens.border),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          for (final lens in lenses) ...[
            _LensChip(
              lens: lens,
              selected: _isSelected(lens),
              activePhysical: _isActivePhysical(lens),
              onTap: () => lens.logical
                  ? host.selectLensStop(lens.id, lens.stopRatio)
                  : host.selectLens(lens.id),
            ),
            const SizedBox(height: 6),
          ],
          if (canZoom) ...[
            if (lenses.isNotEmpty)
              Container(width: 28, height: 1, color: UiTokens.border),
            const SizedBox(height: 6),
            const Icon(Icons.zoom_in, size: 16, color: UiTokens.muted),
            const SizedBox(height: 4),
            RotatedBox(
              quarterTurns: 3,
              child: SizedBox(
                width: 210,
                height: 48,
                child: _ZoomTrack(
                  min: state.zoomMin,
                  max: state.zoomMax,
                  value: state.zoomRatio
                      .clamp(state.zoomMin, state.zoomMax)
                      .toDouble(),
                  valueLabel: '${_formatZoom(effective)}×',
                  onChanged: host.setZoom,
                ),
              ),
            ),
            const SizedBox(height: 4),
            const Icon(Icons.zoom_out, size: 16, color: UiTokens.muted),
            const SizedBox(height: 6),
            Text(
              '${_formatZoom(effective)}×',
              textAlign: TextAlign.center,
              style: const TextStyle(
                color: UiTokens.accent,
                fontSize: 13,
                fontWeight: FontWeight.w800,
                fontFeatures: [FontFeature.tabularFigures()],
              ),
            ),
          ],
        ],
      ),
    );
  }

  bool _isSelected(LensOption lens) {
    if (lens.logical) {
      return lens.id == state.cameraId &&
          (state.zoomRatio - lens.stopRatio).abs() < 0.06;
    }
    return lens.id == state.cameraId;
  }

  bool _isActivePhysical(LensOption lens) {
    final physical = state.activePhysicalCameraId;
    return physical != null &&
        lens.physicalCameraId.isNotEmpty &&
        lens.physicalCameraId == physical;
  }

  LensOption? _activeLens(List<LensOption> lenses) {
    for (final lens in lenses) {
      if (!lens.logical && lens.id == state.cameraId) return lens;
    }
    return null;
  }

  String _formatZoom(double value) {
    if (value < 1.0) return value.toStringAsFixed(2);
    if (value < 10.0) return value.toStringAsFixed(1);
    return value.round().toString();
  }
}

class _LensChip extends StatelessWidget {
  const _LensChip({
    required this.lens,
    required this.selected,
    required this.activePhysical,
    required this.onTap,
  });

  final LensOption lens;
  final bool selected;
  final bool activePhysical;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final hint = lens.exposed
        ? lens.role
        : '${lens.role} — probe-verified hidden lens';
    return Semantics(
      button: true,
      selected: selected,
      label: 'Lens ${lens.label}, $hint'
          '${activePhysical ? ', active sensor' : ''}',
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(999),
          onTap: onTap,
          child: Container(
            constraints: const BoxConstraints(minHeight: 36),
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            decoration: BoxDecoration(
              color: selected ? UiTokens.accent : UiTokens.surfaceRaised,
              borderRadius: BorderRadius.circular(999),
              border: Border.all(
                color: selected ? UiTokens.accent : UiTokens.border,
              ),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (!lens.exposed) ...[
                  Icon(
                    Icons.visibility_off_outlined,
                    size: 11,
                    color: selected ? Colors.black : UiTokens.muted,
                  ),
                  const SizedBox(width: 4),
                ],
                if (activePhysical) ...[
                  Container(
                    width: 5,
                    height: 5,
                    decoration: BoxDecoration(
                      color: selected ? Colors.black : UiTokens.success,
                      shape: BoxShape.circle,
                    ),
                  ),
                  const SizedBox(width: 4),
                ],
                Text(
                  lens.label,
                  style: TextStyle(
                    color: selected ? Colors.black : UiTokens.muted,
                    fontSize: 11,
                    fontWeight: FontWeight.w800,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
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
    required this.onChanged,
  });

  final double min;
  final double max;
  final double value;
  final String valueLabel;
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

  void _seek(Offset local, double width) {
    if (width <= 0 || widget.max <= widget.min) return;
    final fraction = (local.dx / width).clamp(0.0, 1.0);
    final value = widget.min + fraction * (widget.max - widget.min);
    setState(() => _dragValue = value);
    widget.onChanged(value);
  }

  void _step(double direction) {
    final step = (widget.max - widget.min) / 20;
    final value = (widget.value + direction * step)
        .clamp(widget.min, widget.max);
    setState(() => _dragValue = value);
    widget.onChanged(value);
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth;
        final fraction = widget.max > widget.min
            ? ((_shown - widget.min) / (widget.max - widget.min)).clamp(
                0.0,
                1.0,
              )
            : 0.0;
        const thumbSize = 22.0;
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
                  Positioned(
                    left: fraction * trackWidth,
                    top: (48 - thumbSize) / 2,
                    width: thumbSize,
                    height: thumbSize,
                    child: Container(
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: UiTokens.accent,
                        border: Border.all(
                          color: UiTokens.background,
                          width: 2,
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
