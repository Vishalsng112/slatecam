import 'package:flutter/material.dart';

import 'ui_tokens.dart';

class DeckCard extends StatelessWidget {
  const DeckCard({
    super.key,
    required this.title,
    required this.child,
    this.trailing,
    this.tint,
  });

  final String title;
  final Widget child;
  final Widget? trailing;

  /// Section accent. Tints the background, frame, left stripe and title so
  /// cards read as distinct groups without leaving the dark console palette.
  final Color? tint;

  @override
  Widget build(BuildContext context) {
    final accent = tint;
    final background = accent == null
        ? UiTokens.surfaceRaised
        : Color.alphaBlend(
            accent.withValues(alpha: 0.055),
            UiTokens.surfaceRaised,
          );
    final frame = accent == null
        ? UiTokens.border
        : Color.alphaBlend(accent.withValues(alpha: 0.30), UiTokens.border);
    final titleStyle = Theme.of(context).textTheme.labelSmall?.copyWith(
      color: accent ?? UiTokens.muted,
      letterSpacing: 0.8,
    );
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(bottom: 8),
      decoration: BoxDecoration(
        color: background,
        borderRadius: BorderRadius.circular(UiTokens.radius),
        border: Border.all(color: frame),
      ),
      padding: const EdgeInsets.fromLTRB(8, 8, 8, 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(title.toUpperCase(), style: titleStyle),
              ),
              ?trailing,
            ],
          ),
          const SizedBox(height: 6),
          child,
        ],
      ),
    );
  }
}

class LabeledSlider extends StatelessWidget {
  const LabeledSlider({
    super.key,
    required this.label,
    required this.valueLabel,
    required this.value,
    required this.min,
    required this.max,
    this.divisions,
    this.onChanged,
    this.onChangeEnd,
    this.onReset,
    this.enabled = true,
  });

  final String label;
  final String valueLabel;
  final double value;
  final double min;
  final double max;
  final int? divisions;
  final ValueChanged<double>? onChanged;
  final ValueChanged<double>? onChangeEnd;
  /// Shows a small reset-to-default action when provided.
  final VoidCallback? onReset;
  final bool enabled;

  @override
  Widget build(BuildContext context) {
    final clamped = value.clamp(min, max).toDouble();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(label, style: Theme.of(context).textTheme.bodyMedium),
            ),
            if (onReset != null)
              IconButton(
                tooltip: 'Reset to default',
                visualDensity: VisualDensity.compact,
                padding: EdgeInsets.zero,
                constraints: const BoxConstraints.tightFor(
                  width: 22,
                  height: 22,
                ),
                onPressed: onReset,
                icon: const Icon(Icons.restart_alt, size: 14),
              ),
            Text(
              valueLabel,
              style: Theme.of(context).textTheme.bodySmall,
              textAlign: TextAlign.right,
            ),
          ],
        ),
        Slider(
          min: min,
          max: max == min ? min + 1 : max,
          value: max == min ? min : clamped,
          divisions: divisions,
          onChanged: enabled ? onChanged : null,
          onChangeEnd: enabled ? onChangeEnd : null,
        ),
      ],
    );
  }
}

class ReadoutChip extends StatelessWidget {
  const ReadoutChip({
    super.key,
    required this.label,
    required this.value,
    required this.onTap,
    this.icon,
    this.accent = false,
  });

  final String label;
  final String value;
  final VoidCallback onTap;
  final IconData? icon;
  final bool accent;

  @override
  Widget build(BuildContext context) {
    final color = accent ? UiTokens.accent : UiTokens.text;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        borderRadius: BorderRadius.circular(UiTokens.controlRadius),
        onTap: onTap,
        child: Container(
          constraints: const BoxConstraints(minWidth: 92),
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
          decoration: BoxDecoration(
            color: UiTokens.surfaceRaised.withValues(alpha: 0.9),
            borderRadius: BorderRadius.circular(UiTokens.controlRadius),
            border: Border.all(color: UiTokens.border),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (icon != null) ...[
                Icon(icon, size: 14, color: UiTokens.muted),
                const SizedBox(width: 6),
              ],
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    label,
                    style: const TextStyle(
                      color: UiTokens.muted,
                      fontSize: 9,
                      fontWeight: FontWeight.w700,
                      letterSpacing: 0.5,
                    ),
                  ),
                  Text(
                    value,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: color,
                      fontSize: 12,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class StatusPill extends StatelessWidget {
  const StatusPill({
    super.key,
    required this.child,
    this.color = UiTokens.muted,
    this.icon,
  });

  final Widget child;
  final Color color;
  final IconData? icon;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 5),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: color.withValues(alpha: 0.35)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (icon != null) ...[
            Icon(icon, size: 12, color: color),
            const SizedBox(width: 4),
          ],
          child,
        ],
      ),
    );
  }
}

String humanizeWireValue(String value) =>
    value.replaceAll('_', ' ').replaceAll('-', ' ');

String formatDuration(int seconds) {
  final minutes = (seconds ~/ 60).toString().padLeft(2, '0');
  final remainder = (seconds % 60).toString().padLeft(2, '0');
  return '$minutes:$remainder';
}

String formatShutterMs(int nanoseconds) =>
    '${(nanoseconds / 1000000).toStringAsFixed(2)} ms';

String formatShutterFraction(int nanoseconds) {
  if (nanoseconds <= 0) return '—';
  final denominator = (1e9 / nanoseconds).round();
  return '1/$denominator';
}

String formatShutterSummary(int nanoseconds) =>
    '${formatShutterFraction(nanoseconds)} · ${formatShutterMs(nanoseconds)}';

String formatDiopters(double value) => '${value.toStringAsFixed(2)} D';
