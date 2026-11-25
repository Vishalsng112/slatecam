import 'dart:math' show max;

import 'package:flutter/material.dart';

import 'ui_tokens.dart';

class HistogramPainter extends CustomPainter {
  HistogramPainter(this.bins);

  final List<int> bins;

  @override
  void paint(Canvas canvas, Size size) {
    if (bins.isEmpty) return;
    final peak = bins.reduce(max);
    if (peak == 0) return;
    final barWidth = size.width / bins.length;
    final paint = Paint()..color = UiTokens.accent;
    for (var i = 0; i < bins.length; i++) {
      final height = (bins[i] / peak) * size.height;
      canvas.drawRect(
        Rect.fromLTWH(
          i * barWidth,
          size.height - height,
          barWidth * 0.8,
          height,
        ),
        paint,
      );
    }
  }

  @override
  bool shouldRepaint(covariant HistogramPainter oldDelegate) =>
      oldDelegate.bins != bins;
}

class WaveformPainter extends CustomPainter {
  WaveformPainter(this.data, this.columns, this.bins);

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
        paint.color = UiTokens.accent.withValues(
          alpha: (count / peak).clamp(0.0, 1.0),
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
  bool shouldRepaint(covariant WaveformPainter oldDelegate) => true;
}

class GuidesPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final line = Paint()
      ..color = Colors.white.withValues(alpha: 0.35)
      ..strokeWidth = 1;

    for (var i = 1; i < 3; i++) {
      final dx = size.width * i / 3;
      final dy = size.height * i / 3;
      canvas.drawLine(Offset(dx, 0), Offset(dx, size.height), line);
      canvas.drawLine(Offset(0, dy), Offset(size.width, dy), line);
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}
