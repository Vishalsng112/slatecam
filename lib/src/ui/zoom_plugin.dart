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
    final effective = ((active?.zoomRatio ?? 1.0) * state.zoomRatio)
        .clamp(0.1, 999.0)
        .toDouble();

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
      decoration: BoxDecoration(
        color: UiTokens.surfaceGlass,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: UiTokens.border),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          for (final lens in lenses) ...[
            _LensChip(
              lens: lens,
              selected: lens.id == state.cameraId,
              onTap: () => host.selectLens(lens.id),
            ),
            const SizedBox(width: 4),
          ],
          if (canZoom) ...[
            Container(
              width: 1,
              height: 20,
              margin: const EdgeInsets.symmetric(horizontal: 6),
              color: UiTokens.border,
            ),
            SizedBox(
              width: 132,
              child: SliderTheme(
                data: SliderTheme.of(context).copyWith(
                  trackHeight: 2,
                  thumbShape: const RoundSliderThumbShape(
                    enabledThumbRadius: 6,
                  ),
                  overlayShape: const RoundSliderOverlayShape(
                    overlayRadius: 10,
                  ),
                ),
                child: Slider(
                  min: state.zoomMin,
                  max: state.zoomMax,
                  value: state.zoomRatio
                      .clamp(state.zoomMin, state.zoomMax)
                      .toDouble(),
                  onChanged: host.setZoom,
                ),
              ),
            ),
            SizedBox(
              width: 46,
              child: Text(
                '${_formatZoom(effective)}×',
                textAlign: TextAlign.right,
                style: const TextStyle(
                  color: UiTokens.accent,
                  fontSize: 11,
                  fontWeight: FontWeight.w800,
                  fontFeatures: [FontFeature.tabularFigures()],
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }

  LensOption? _activeLens(List<LensOption> lenses) {
    for (final lens in lenses) {
      if (lens.id == state.cameraId) return lens;
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
    required this.onTap,
  });

  final LensOption lens;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final hint = lens.exposed
        ? lens.role
        : '${lens.role} — probe-verified hidden lens';
    return Semantics(
      button: true,
      selected: selected,
      label: 'Lens ${lens.label}, $hint',
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(999),
          onTap: onTap,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
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
