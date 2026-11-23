import 'package:flutter/material.dart';

import 'src/ui/capture_screen.dart';
import 'src/ui/ui_tokens.dart';

void main() => runApp(const SlateCamApp());

class SlateCamApp extends StatelessWidget {
  const SlateCamApp({super.key});

  static const accent = UiTokens.accent;
  static const background = UiTokens.background;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'SlateCam',
      debugShowCheckedModeBanner: false,
      theme: UiTokens.theme(),
      home: const CaptureScreen(),
    );
  }
}
