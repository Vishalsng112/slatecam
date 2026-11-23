import 'package:flutter/material.dart';

/// Shared visual tokens for the camera cockpit.
class UiTokens {
  const UiTokens._();

  static const background = Color(0xFF08090B);
  static const surface = Color(0xFF111317);
  static const surfaceRaised = Color(0xFF171A1F);
  static const surfaceGlass = Color(0xE6111317);
  static const border = Color(0xFF2A2E36);
  static const accent = Color(0xFFE8B44A);
  static const accentDark = Color(0xFF8A6A26);
  static const text = Color(0xFFF5F2EA);
  static const muted = Color(0xFF9AA3AF);
  static const danger = Color(0xFFE5484D);
  static const success = Color(0xFF4CC38A);
  static const info = Color(0xFF6EA8FE);

  static const radius = 14.0;
  static const controlRadius = 12.0;

  static ThemeData theme() {
    final scheme =
        ColorScheme.fromSeed(
          seedColor: accent,
          brightness: Brightness.dark,
        ).copyWith(
          surface: surface,
          error: danger,
          primary: accent,
          onPrimary: const Color(0xFF241800),
          onSurface: text,
          onSurfaceVariant: muted,
          outline: border,
        );

    return ThemeData(
      useMaterial3: true,
      brightness: Brightness.dark,
      scaffoldBackgroundColor: background,
      colorScheme: scheme,
      sliderTheme: SliderThemeData(
        activeTrackColor: accent,
        inactiveTrackColor: border,
        thumbColor: accent,
        overlayColor: accent.withValues(alpha: 0.14),
        trackHeight: 3,
        thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 8),
      ),
      switchTheme: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) {
            return const Color(0xFF241800);
          }
          return muted;
        }),
        trackColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) return accent;
          return surfaceRaised;
        }),
        trackOutlineColor: WidgetStatePropertyAll(border),
      ),
      textTheme: const TextTheme(
        headlineMedium: TextStyle(
          color: text,
          fontWeight: FontWeight.w700,
          letterSpacing: 6,
        ),
        titleMedium: TextStyle(color: text, fontWeight: FontWeight.w700),
        bodyMedium: TextStyle(color: text),
        bodySmall: TextStyle(color: muted),
        labelLarge: TextStyle(
          color: text,
          fontWeight: FontWeight.w700,
          letterSpacing: 0.6,
        ),
        labelSmall: TextStyle(
          color: muted,
          fontWeight: FontWeight.w700,
          letterSpacing: 1.1,
        ),
      ),
    );
  }
}
