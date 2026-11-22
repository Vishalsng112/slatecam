import 'package:flutter/material.dart';

void main() => runApp(const SlateCamApp());

/// Phase 0 shell. The camera surface arrives in Phase 2 and will be a native
/// texture rendered behind the controls, not a Dart camera plugin view.
class SlateCamApp extends StatelessWidget {
  const SlateCamApp({super.key});

  static const _accent = Color(0xFFE8B44A);
  static const _background = Color(0xFF0B0B0C);

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'SlateCam',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        brightness: Brightness.dark,
        scaffoldBackgroundColor: _background,
        colorScheme: ColorScheme.fromSeed(
          seedColor: _accent,
          brightness: Brightness.dark,
        ),
      ),
      home: const _FoundationScreen(),
    );
  }
}

class _FoundationScreen extends StatelessWidget {
  const _FoundationScreen();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Text(
                'SLATECAM',
                style: theme.textTheme.headlineMedium?.copyWith(
                  letterSpacing: 8,
                  fontWeight: FontWeight.w600,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                'Open-source cinema camera',
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
              const SizedBox(height: 40),
              Text(
                'Phase 0 — foundation',
                style: theme.textTheme.labelLarge,
              ),
              const SizedBox(height: 8),
              Text(
                'No camera pipeline yet.\n'
                'Capabilities will be gated by hardware probes,\n'
                'never by model name.',
                textAlign: TextAlign.center,
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                  height: 1.6,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
