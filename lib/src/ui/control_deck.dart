import 'package:flutter/material.dart';

import '../pigeon/slatecam.g.dart';
import 'ui_tokens.dart';
import 'widgets.dart';

const _angleOptions = [45.0, 90.0, 180.0, 270.0, 360.0];

/// One control surface for the whole camera.
///
/// Every primary action lives on this rail: no tabs, no nested menus for the
/// camera, shoot mode or recording mode. The record button and quick bar stay
/// fixed at the top so they are always one tap away, including from automation.
class ControlDeck extends StatefulWidget {
  const ControlDeck({
    super.key,
    required this.state,
    required this.host,
    required this.onClose,
    required this.onCloseCamera,
    required this.showGuides,
    required this.onGuidesChanged,
    required this.showHistogram,
    required this.onHistogramChanged,
    required this.showWaveform,
    required this.onWaveformChanged,
  });

  final CameraState state;
  final SlateCamHostApi host;
  final VoidCallback onClose;
  final VoidCallback onCloseCamera;
  final bool showGuides;
  final ValueChanged<bool> onGuidesChanged;
  final bool showHistogram;
  final ValueChanged<bool> onHistogramChanged;
  final bool showWaveform;
  final ValueChanged<bool> onWaveformChanged;

  @override
  State<ControlDeck> createState() => _ControlDeckState();
}

class _ControlDeckState extends State<ControlDeck> {
  final ScrollController _scrollController = ScrollController();
  double? _isoDraft;
  double? _shutterDraft;
  double? _peakingSensitivityDraft;
  double? _peakingStrengthDraft;

  CameraState get state => widget.state;

  @override
  void didUpdateWidget(covariant ControlDeck oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.state.iso != state.iso) _isoDraft = null;
    if (oldWidget.state.shutterNs != state.shutterNs) _shutterDraft = null;
    if (oldWidget.state.peakingSensitivity != state.peakingSensitivity) {
      _peakingSensitivityDraft = null;
    }
    if (oldWidget.state.peakingStrength != state.peakingStrength) {
      _peakingStrengthDraft = null;
    }
  }

  double get _iso => _isoDraft ?? state.iso.toDouble();
  double get _shutter => _shutterDraft ?? state.shutterNs.toDouble();
  double get _peakingSensitivity =>
      _peakingSensitivityDraft ?? state.peakingSensitivity;
  double get _peakingStrength => _peakingStrengthDraft ?? state.peakingStrength;

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: const BoxDecoration(
        color: UiTokens.surface,
        border: Border(left: BorderSide(color: UiTokens.border)),
      ),
      child: Column(
        children: [
          _fixedHeader(context),
          const Divider(height: 1, color: UiTokens.border),
          Expanded(
            child: SingleChildScrollView(
              controller: _scrollController,
              padding: const EdgeInsets.fromLTRB(12, 12, 12, 20),
              child: LayoutBuilder(
                builder: (context, constraints) {
                  final primary = <Widget>[
                    _modeCard(context),
                    _exposureCard(context),
                  ];
                  final secondary = <Widget>[
                    _shootSetCard(context),
                    _colourCard(context),
                  ];
                  final tertiary = <Widget>[
                    _monitorCard(context),
                    _processingCard(context),
                    _clipCard(context),
                  ];
                  Widget columns;
                  if (constraints.maxWidth < 520) {
                    columns = Column(
                      children: [
                        ...primary,
                        ...secondary,
                        ...tertiary,
                      ],
                    );
                  } else if (constraints.maxWidth >= 720) {
                    final width = (constraints.maxWidth - 16) / 3;
                    columns = Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        SizedBox(width: width, child: Column(children: primary)),
                        const SizedBox(width: 8),
                        SizedBox(
                          width: width,
                          child: Column(children: secondary),
                        ),
                        const SizedBox(width: 8),
                        SizedBox(
                          width: width,
                          child: Column(children: tertiary),
                        ),
                      ],
                    );
                  } else {
                    final width = (constraints.maxWidth - 8) / 2;
                    columns = Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        SizedBox(
                          width: width,
                          child: Column(children: [...primary, _processingCard(context)]),
                        ),
                        const SizedBox(width: 8),
                        SizedBox(
                          width: width,
                          child: Column(
                            children: [
                              ...secondary,
                              _monitorCard(context),
                              _clipCard(context),
                            ],
                          ),
                        ),
                      ],
                    );
                  }
                  // Device is diagnostics, not a shooting control: always last.
                  return Column(
                    children: [columns, _deviceCard(context)],
                  );
                },
              ),
            ),
          ),
          _footer(context),
        ],
      ),
    );
  }

  Widget _fixedHeader(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(10, 8, 4, 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              _recordCircle(),
              const SizedBox(width: 6),
              _shootCircle('single', 'S'),
              const SizedBox(width: 4),
              _shootCircle('movie', 'M'),
              const SizedBox(width: 8),
              Expanded(
                child: SingleChildScrollView(
                  scrollDirection: Axis.horizontal,
                  child: Row(
                    children: [
                      for (final id in state.cameraIds)
                        Padding(
                          padding: const EdgeInsets.only(right: 2),
                          child: _lensCircle(id),
                        ),
                    ],
                  ),
                ),
              ),
              IconButton(
                tooltip: 'Hide controls',
                visualDensity: VisualDensity.compact,
                onPressed: widget.onClose,
                icon: const Icon(Icons.chevron_right, size: 20),
              ),
            ],
          ),
          const SizedBox(height: 4),
          Row(
            children: [
              Expanded(
                child: Text(
                  '${state.shootMode == 'movie' ? 'MOVIE' : 'SINGLE'} · '
                  '${state.previewWidth}×${state.previewHeight} · '
                  '${state.frameRate}p · '
                  '${state.tenBit ? '10b HLG' : '8b SDR'}',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontSize: 10,
                    letterSpacing: 0.3,
                    color: UiTokens.muted,
                  ),
                ),
              ),
              _modeStepper(),
            ],
          ),
        ],
      ),
    );
  }

  Widget _recordCircle() {
    final recording = state.isRecording;
    return Semantics(
      button: true,
      label: recording ? 'stop recording' : 'start recording',
      child: Tooltip(
        message: recording ? 'Stop recording' : 'Record',
        child: Material(
          color: Colors.transparent,
          shape: const CircleBorder(),
          child: InkWell(
            customBorder: const CircleBorder(),
            onTap: recording
                ? () => widget.host.stopRecording()
                : () => widget.host.startRecording(),
            child: Container(
              width: 44,
              height: 44,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: recording ? UiTokens.danger : UiTokens.surfaceRaised,
                border: Border.all(
                  color: recording ? UiTokens.danger : UiTokens.accent,
                  width: 2,
                ),
              ),
              child: Center(
                child: recording
                    ? Container(
                        width: 13,
                        height: 13,
                        decoration: BoxDecoration(
                          color: Colors.white,
                          borderRadius: BorderRadius.circular(2),
                        ),
                      )
                    : Container(
                        width: 16,
                        height: 16,
                        decoration: const BoxDecoration(
                          color: UiTokens.danger,
                          shape: BoxShape.circle,
                        ),
                      ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _shootCircle(String mode, String letter) {
    final selected = state.shootMode == mode;
    return Semantics(
      button: true,
      selected: selected,
      label: mode == 'movie' ? 'shoot mode movie' : 'shoot mode single',
      child: Tooltip(
        message: mode == 'movie' ? 'Movie / Vlog' : 'Single camera',
        child: Material(
          color: Colors.transparent,
          shape: const CircleBorder(),
          child: InkWell(
            customBorder: const CircleBorder(),
            onTap: () => widget.host.setShootMode(mode),
            child: SizedBox(
              width: 44,
              height: 44,
              child: Center(
                child: Container(
                  width: 34,
                  height: 34,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: selected ? UiTokens.accent : UiTokens.surfaceRaised,
                    border: Border.all(
                      color: selected ? UiTokens.accent : UiTokens.border,
                    ),
                  ),
                  child: Text(
                    letter,
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w900,
                      color: selected ? UiTokens.background : UiTokens.text,
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _lensCircle(String id) {
    final selected = id == state.cameraId;
    return Semantics(
      button: true,
      selected: selected,
      label: 'camera $id',
      child: Tooltip(
        message: _cameraLabel(id),
        child: Material(
          color: Colors.transparent,
          shape: const CircleBorder(),
          child: InkWell(
            customBorder: const CircleBorder(),
            onTap: () => widget.host.setCameraId(id),
            child: SizedBox(
              width: 44,
              height: 44,
              child: Center(
                child: Container(
                  width: 34,
                  height: 34,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: selected ? UiTokens.accent : UiTokens.surfaceRaised,
                    border: Border.all(
                      color: selected ? UiTokens.accent : UiTokens.border,
                    ),
                  ),
                  child: Text(
                    _lensBadge(id),
                    style: TextStyle(
                      fontSize: 10,
                      fontWeight: FontWeight.w900,
                      color: selected ? UiTokens.background : UiTokens.text,
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  String _lensBadge(String id) {
    var facing = '';
    for (final lens in state.shootLenses) {
      if (lens.id == id) {
        facing = lens.facing;
        break;
      }
    }
    if (facing.isEmpty) {
      final label = _cameraLabel(id).toLowerCase();
      if (label.contains('front')) {
        facing = 'front';
      } else if (label.contains('back')) {
        facing = 'back';
      }
    }
    final letter = switch (facing) {
      'front' => 'F',
      'back' => 'B',
      _ => 'C',
    };
    return '$letter$id';
  }

  Widget _modeStepper() {
    final modes = _enabledModes;
    final index = modes.indexWhere((mode) => mode.id == state.recordingMode);
    final mode = index >= 0 ? modes[index] : null;
    return Container(
      height: 28,
      decoration: BoxDecoration(
        color: UiTokens.surfaceRaised,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: UiTokens.border),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          _stepButton(Icons.chevron_left, 'Previous mode', () => _cycleMode(-1)),
          ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 150),
            child: Semantics(
              label: 'recording mode: ${mode != null ? _modeShort(mode) : state.recordingMode}',
              child: Text(
                mode != null ? _modeShort(mode) : state.recordingMode,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontSize: 10,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
          ),
          _stepButton(Icons.chevron_right, 'Next mode', () => _cycleMode(1)),
        ],
      ),
    );
  }

  Widget _stepButton(IconData icon, String tooltip, VoidCallback onTap) {
    return IconButton(
      tooltip: tooltip,
      onPressed: onTap,
      visualDensity: VisualDensity.compact,
      padding: EdgeInsets.zero,
      constraints: const BoxConstraints.tightFor(width: 26, height: 26),
      icon: Icon(icon, size: 16),
    );
  }

  Widget _modeCard(BuildContext context) {
    final current = _currentMode;
    if (current == null) {
      return DeckCard(
        title: 'Recording mode',
        child: Text(
          'No recording modes reported.',
          style: Theme.of(context).textTheme.bodySmall,
        ),
      );
    }

    final height = current.height.toInt();
    final is4k = height == 2160;
    final is1080 = height == 1080;
    final wide = _isWideMode(current);
    final familyHeight = is4k ? 2160 : 1080;

    final rates = state.recordingModes
        .where((mode) => mode.height == familyHeight && mode.height != 720)
        .map((mode) => mode.fps.toInt())
        .toSet()
        .toList()
      ..sort();

    final depths = state.recordingModes
        .where(
          (mode) =>
              mode.width == current.width &&
              mode.height == current.height &&
              mode.fps == current.fps,
        )
        .map((mode) => mode.tenBit)
        .toSet();

    return DeckCard(
      title: 'Recording mode',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _modeRow(
            'Resolution',
            [
              _modeChip(
                label: '4K',
                tooltip: '3840×2160',
                selected: is4k,
                onTap: () => _selectResolution('4K'),
              ),
              _modeChip(
                label: '1080',
                tooltip: '1920×1080 or 1440×1080',
                selected: is1080,
                onTap: () => _selectResolution('1080'),
              ),
            ],
          ),
          const SizedBox(height: 4),
          _modeRow(
            'Aspect',
            [
              _aspectCircle(
                '16:9',
                selected: wide,
                onTap: () => _selectAspect(true),
              ),
              _aspectCircle(
                '4:3',
                selected: !wide,
                onTap: () => _selectAspect(false),
              ),
            ],
          ),
          const SizedBox(height: 4),
          _modeRow(
            'Frame rate',
            [
              for (final fps in rates)
                _modeChip(
                  label: '$fps',
                  tooltip: '$fps fps',
                  selected: fps == current.fps,
                  onTap: () => _selectFps(fps),
                ),
            ],
          ),
          const SizedBox(height: 4),
          _modeRow(
            'Bit depth',
            [
              for (final tenBit in [true, false])
                if (depths.contains(tenBit))
                  _modeChip(
                    label: tenBit ? '10-bit' : '8-bit',
                    tooltip: tenBit
                        ? '10-bit HLG where probe-verified'
                        : '8-bit SDR',
                    selected: current.tenBit == tenBit,
                    onTap: () => _selectBit(tenBit),
                  ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _aspectCircle(String label, {
    required bool selected,
    required VoidCallback onTap,
  }) {
    return Semantics(
      button: true,
      selected: selected,
      label: 'aspect ratio $label',
      child: Material(
        color: Colors.transparent,
        shape: const CircleBorder(),
        child: InkWell(
          customBorder: const CircleBorder(),
          onTap: onTap,
          child: SizedBox(
            width: 44,
            height: 44,
            child: Center(
              child: Container(
                width: 36,
                height: 36,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: selected ? UiTokens.accent : UiTokens.surfaceRaised,
                  border: Border.all(
                    color: selected ? UiTokens.accent : UiTokens.border,
                  ),
                ),
                child: Text(
                  label,
                  style: TextStyle(
                    fontSize: 9,
                    fontWeight: FontWeight.w900,
                    color: selected ? UiTokens.background : UiTokens.text,
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _profileIcon(IconData icon, String tooltip, VoidCallback? onPressed) {
    return IconButton(
      tooltip: tooltip,
      visualDensity: VisualDensity.compact,
      padding: EdgeInsets.zero,
      constraints: const BoxConstraints.tightFor(width: 30, height: 30),
      onPressed: onPressed,
      icon: Icon(icon, size: 16),
    );
  }

  Widget _modeRow(String label, List<Widget> chips) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          width: 62,
          child: Padding(
            padding: const EdgeInsets.only(top: 7),
            child: Text(
              label,
              style: Theme.of(
                context,
              ).textTheme.bodySmall?.copyWith(fontSize: 10),
            ),
          ),
        ),
        Expanded(
          child: Wrap(spacing: 4, runSpacing: 4, children: chips),
        ),
      ],
    );
  }

  Widget _modeChip({
    required String label,
    required String tooltip,
    required bool selected,
    required VoidCallback onTap,
  }) {
    return Tooltip(
      message: tooltip,
      child: ChoiceChip(
        visualDensity: VisualDensity.compact,
        materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
        labelPadding: EdgeInsets.zero,
        label: Text(label, style: const TextStyle(fontSize: 10)),
        selected: selected,
        onSelected: (_) => onTap(),
        selectedColor: UiTokens.accent.withValues(alpha: 0.25),
      ),
    );
  }

  RecordingMode? get _currentMode {
    for (final mode in state.recordingModes) {
      if (mode.id == state.recordingMode) return mode;
    }
    return null;
  }

  bool _isWideMode(RecordingMode mode) =>
      mode.width * 9 == mode.height * 16;

  (int, int) get _editableSize {
    final current = _currentMode;
    if (current == null) return (1920, 1080);
    if (current.height == 2160) return (3840, 2160);
    if (current.width == 1440) return (1440, 1080);
    return (1920, 1080);
  }

  void _selectResolution(String label) {
    final current = _currentMode;
    final fps = current?.fps.toInt() ?? 30;
    final tenBit = current?.tenBit ?? true;
    if (label == '4K') {
      _chooseMode(3840, 2160, fps, tenBit);
      return;
    }
    final wide = current == null ? true : _isWideMode(current);
    _chooseMode(wide ? 1920 : 1440, 1080, fps, tenBit);
  }

  void _selectAspect(bool wide) {
    final current = _currentMode;
    final fps = current?.fps.toInt() ?? 30;
    final tenBit = current?.tenBit ?? true;
    final is4k = current?.height == 2160;
    if (wide) {
      _chooseMode(is4k ? 3840 : 1920, is4k ? 2160 : 1080, fps, tenBit);
    } else {
      // 4:3 exists at 1440x1080; selecting it from 4K drops to 1080.
      _chooseMode(1440, 1080, fps, tenBit);
    }
  }

  void _selectFps(int fps) {
    final current = _currentMode;
    if (current == null) return;
    final (width, height) = _editableSize;
    _chooseMode(width, height, fps, current.tenBit);
  }

  void _selectBit(bool tenBit) {
    final current = _currentMode;
    if (current == null) return;
    final (width, height) = _editableSize;
    _chooseMode(width, height, current.fps.toInt(), tenBit);
  }

  void _chooseMode(int width, int height, int fps, bool tenBit) {
    RecordingMode? enabled(
      int desiredFps,
      bool desiredBit,
    ) {
      for (final mode in state.recordingModes) {
        if (mode.enabled &&
            mode.width == width &&
            mode.height == height &&
            mode.fps == desiredFps &&
            mode.tenBit == desiredBit) {
          return mode;
        }
      }
      return null;
    }

    final exact = enabled(fps, tenBit) ??
        enabled(fps, !tenBit) ??
        _nearestRate(width, height, fps, tenBit) ??
        _nearestRate(width, height, fps, !tenBit) ??
        _firstEnabledAt(width, height);

    if (exact != null) {
      widget.host.selectRecordingMode(exact.id);
      return;
    }

    // No enabled combination at this size: surface the bridge's reason.
    for (final mode in state.recordingModes) {
      if (mode.width == width && mode.height == height) {
        widget.host.selectRecordingMode(mode.id);
        return;
      }
    }
  }

  RecordingMode? _nearestRate(
    int width,
    int height,
    int fps,
    bool tenBit,
  ) {
    RecordingMode? best;
    var bestDelta = 1 << 30;
    for (final mode in state.recordingModes) {
      if (!mode.enabled ||
          mode.width != width ||
          mode.height != height ||
          mode.tenBit != tenBit) {
        continue;
      }
      final delta = (mode.fps.toInt() - fps).abs();
      if (delta < bestDelta ||
          (delta == bestDelta && (best == null || mode.fps > best.fps))) {
        best = mode;
        bestDelta = delta;
      }
    }
    return best;
  }

  RecordingMode? _firstEnabledAt(int width, int height) {
    for (final mode in state.recordingModes) {
      if (mode.enabled && mode.width == width && mode.height == height) {
        return mode;
      }
    }
    return null;
  }

  String _sizeLabelFor(int width, int height) {
    return switch ((width, height)) {
      (3840, 2160) => '4K',
      (1920, 1080) => '1080p',
      (1440, 1080) => '4:3',
      (1280, 720) => '720p',
      _ => '$width×$height',
    };
  }

  /// Lens badges use the 35 mm-equivalent focal length, e.g. `16mm`, because
  /// that is how the operator picks a lens on set. Facing is added only when
  /// two lenses share the same value.
  Widget _shootLensRow(List<ShootLens> lenses, bool editable) {
    final focalCounts = <String, int>{};
    for (final lens in lenses) {
      final focal = _focalLabel(lens);
      focalCounts[focal] = (focalCounts[focal] ?? 0) + 1;
    }
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        for (final lens in lenses)
          _shootLensCircle(lens, editable, focalCounts[_focalLabel(lens)]! > 1),
      ],
    );
  }

  Widget _shootLensCircle(ShootLens lens, bool editable, bool withFacing) {
    final focal = _focalLabel(lens);
    final facing = switch (lens.facing) {
      'front' => 'F',
      'back' => 'B',
      _ => 'C',
    };
    final label = withFacing ? '$facing$focal' : focal;
    return Semantics(
      button: true,
      selected: lens.selected,
      label: 'shoot lens ${lens.label}',
      child: Tooltip(
        message: lens.label,
        child: Material(
          color: Colors.transparent,
          shape: const CircleBorder(),
          child: InkWell(
            customBorder: const CircleBorder(),
            onTap: editable
                ? () => widget.host.setShootLens(lens.id, !lens.selected)
                : null,
            child: SizedBox(
              width: 50,
              height: 50,
              child: Center(
                child: Container(
                  width: 42,
                  height: 42,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: lens.selected
                        ? UiTokens.accent
                        : UiTokens.surfaceRaised,
                    border: Border.all(
                      color: lens.selected
                          ? UiTokens.accent
                          : UiTokens.border,
                    ),
                  ),
                  child: Text(
                    label,
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: 10,
                      height: 1.05,
                      fontWeight: FontWeight.w800,
                      color: lens.selected
                          ? UiTokens.background
                          : UiTokens.text,
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  String _focalLabel(ShootLens lens) {
    final match = RegExp(r'(\d+)mm').firstMatch(lens.label);
    if (match != null) return '${match.group(1)}mm';
    final digits = RegExp(r'\d+').firstMatch(lens.label)?.group(0);
    return digits != null ? '${digits}mm' : lens.role;
  }

  Widget _shootSetCard(BuildContext context) {
    final lenses = state.shootLenses;
    final profiles = state.shootProfiles;
    final active = profiles.firstWhere(
      (profile) => profile.id == state.activeShootProfile,
      orElse: () => ShootProfile(id: '', name: 'Default', lensCount: 0),
    );
    final editable = !state.isRecording;
    return DeckCard(
      title: 'Shoot set & profiles',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (lenses.isEmpty)
            Text(
              'Run a device report to discover lenses.',
              style: Theme.of(context).textTheme.bodySmall,
            )
          else
            _shootLensRow(lenses, editable),
          const SizedBox(height: 6),
          Text(
            state.isRecording
                ? 'Stop recording to edit the shoot set or profiles.'
                : 'Movie mode uses the common mode of the selected lenses. '
                      'Macro lenses are never included.',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(fontSize: 10),
          ),
          const SizedBox(height: 8),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              for (final profile in profiles)
                ChoiceChip(
                  visualDensity: VisualDensity.compact,
                  materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  label: Text(
                    '${profile.name} · ${profile.lensCount}',
                    style: const TextStyle(fontSize: 10),
                  ),
                  selected: profile.id == state.activeShootProfile,
                  onSelected: editable
                      ? (_) => widget.host.applyShootProfile(profile.id)
                      : null,
                  selectedColor: UiTokens.accent.withValues(alpha: 0.25),
                ),
            ],
          ),
          const SizedBox(height: 4),
          Row(
            children: [
              Expanded(
                child: Text(
                  'Active: ${active.name}',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ),
              _profileIcon(
                Icons.save_outlined,
                'Save current set as profile',
                editable ? _saveShootProfile : null,
              ),
              _profileIcon(
                Icons.drive_file_rename_outline,
                'Rename active profile',
                editable && active.id.isNotEmpty ? _renameShootProfile : null,
              ),
              _profileIcon(
                Icons.delete_outline,
                'Delete active profile',
                editable && profiles.length > 1 && active.id.isNotEmpty
                    ? _deleteShootProfile
                    : null,
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _exposureCircle(String mode) {
    final selected = state.exposureMode == mode;
    return Semantics(
      button: true,
      selected: selected,
      label: 'exposure mode $mode',
      child: Material(
        color: Colors.transparent,
        shape: const CircleBorder(),
        child: InkWell(
          customBorder: const CircleBorder(),
          onTap: () => widget.host.setExposureMode(mode),
          child: SizedBox(
            width: 42,
            height: 42,
            child: Center(
              child: Container(
                width: 34,
                height: 34,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: selected ? UiTokens.accent : UiTokens.surfaceRaised,
                  border: Border.all(
                    color: selected ? UiTokens.accent : UiTokens.border,
                  ),
                ),
                child: Text(
                  mode,
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w900,
                    color: selected ? UiTokens.background : UiTokens.text,
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _exposureCard(BuildContext context) {
    final manualIso = state.exposureMode == 'M';
    final manualShutter = state.exposureMode != 'P';
    final isoValueLabel = state.exposureMode == 'S'
        ? 'auto · ${state.iso}'
        : '${state.iso}';
    final shutterValueLabel = state.exposureMode == 'P'
        ? 'auto · ${formatShutterSummary(state.shutterNs)}'
        : formatShutterSummary(state.shutterNs);

    return DeckCard(
      title: 'Exposure',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              for (final mode in state.exposureModes) ...[
                _exposureCircle(mode),
                const SizedBox(width: 6),
              ],
            ],
          ),
          const SizedBox(height: 4),
          LabeledSlider(
            label: 'ISO',
            valueLabel: isoValueLabel,
            value: _iso,
            min: state.isoMin.toDouble(),
            max: state.isoMax.toDouble(),
            enabled: manualIso,
            onChanged: (value) => setState(() => _isoDraft = value),
            onChangeEnd: (value) => widget.host.setIso(value.round()),
          ),
          LabeledSlider(
            label: 'Shutter',
            valueLabel: shutterValueLabel,
            value: _shutter,
            min: state.shutterMinNs.toDouble(),
            max: _shutterCeiling(state),
            enabled: manualShutter,
            onChanged: (value) => setState(() => _shutterDraft = value),
            onChangeEnd: (value) => widget.host.setShutterNs(value.round()),
          ),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              for (final angle in _angleOptions)
                ChoiceChip(
                  visualDensity: VisualDensity.compact,
                  materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  label: Text('${angle.toInt()}°', style: const TextStyle(fontSize: 10)),
                  selected:
                      state.shutterAngle.roundToDouble() == angle &&
                      manualShutter,
                  onSelected: manualShutter
                      ? (_) => widget.host.setShutterAngle(angle)
                      : null,
                ),
            ],
          ),
          const SizedBox(height: 6),
          TextButton.icon(
            style: TextButton.styleFrom(
              visualDensity: VisualDensity.compact,
              padding: const EdgeInsets.symmetric(horizontal: 4),
            ),
            onPressed: () => widget.host.setAutoExposure(),
            icon: Icon(
              state.autoExposure ? Icons.check_circle_outline : Icons.exposure,
              size: 15,
            ),
            label: Text(
              state.autoExposure ? 'AE active' : 'Return to AE',
              style: const TextStyle(fontSize: 11),
            ),
          ),
        ],
      ),
    );
  }

  Widget _colourCard(BuildContext context) {
    final calibration = state.deviceCalibrationActive
        ? (state.deviceCalibrationSource ?? 'measured matrix')
        : 'identity · no measured data';
    return DeckCard(
      title: 'Colour',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('PROFILE', style: Theme.of(context).textTheme.labelSmall),
          const SizedBox(height: 4),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              for (final profile in state.profileNames)
                ChoiceChip(
                  visualDensity: VisualDensity.compact,
                  materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  label: Text(
                    humanizeWireValue(profile),
                    style: const TextStyle(fontSize: 10),
                  ),
                  selected: profile == state.profile,
                  onSelected: (_) => widget.host.setProfile(profile),
                ),
            ],
          ),
          TextButton.icon(
            style: TextButton.styleFrom(
              visualDensity: VisualDensity.compact,
              padding: const EdgeInsets.symmetric(horizontal: 4),
            ),
            onPressed: () => widget.host.reloadLuts(),
            icon: const Icon(Icons.refresh, size: 14),
            label: const Text('Reload LUTs', style: TextStyle(fontSize: 10)),
          ),
          const Divider(height: 8, color: UiTokens.border),
          Text('WHITE BALANCE', style: Theme.of(context).textTheme.labelSmall),
          const SizedBox(height: 4),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              for (final mode in state.whiteBalanceModes)
                ChoiceChip(
                  visualDensity: VisualDensity.compact,
                  materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  label: Text(
                    humanizeWireValue(mode),
                    style: const TextStyle(fontSize: 10),
                  ),
                  selected: mode == state.whiteBalanceMode,
                  onSelected: (_) => widget.host.setWhiteBalanceMode(mode),
                ),
            ],
          ),
          const Divider(height: 8, color: UiTokens.border),
          Text(
            'Input calibration',
            style: Theme.of(context).textTheme.bodyMedium,
          ),
          Text(calibration, style: Theme.of(context).textTheme.bodySmall),
        ],
      ),
    );
  }

  Widget _monitorCard(BuildContext context) {
    return DeckCard(
      title: 'Monitoring',
      child: Column(
        children: [
          Wrap(
            spacing: 4,
            runSpacing: 4,
            children: [
              _toggleChip(
                'Guides',
                Icons.grid_3x3,
                widget.showGuides,
                widget.onGuidesChanged,
              ),
              _toggleChip(
                'Zebra',
                Icons.warning_amber_rounded,
                state.zebra,
                widget.host.setZebra,
              ),
              _toggleChip(
                'False',
                Icons.palette_outlined,
                state.falseColor,
                widget.host.setFalseColor,
              ),
              _toggleChip(
                'Peaking',
                Icons.center_focus_strong,
                state.peaking,
                (value) => widget.host.setPeaking(
                  value,
                  _peakingSensitivity,
                  _peakingStrength,
                ),
              ),
              _toggleChip(
                'Hist',
                Icons.bar_chart,
                widget.showHistogram,
                widget.onHistogramChanged,
              ),
              _toggleChip(
                'Wave',
                Icons.show_chart,
                widget.showWaveform,
                widget.onWaveformChanged,
              ),
            ],
          ),
          if (state.peaking) ...[
            const SizedBox(height: 4),
            LabeledSlider(
              label: 'Peaking sensitivity',
              valueLabel: '${(_peakingSensitivity * 100).round()}%',
              value: _peakingSensitivity,
              min: 0,
              max: 1,
              onChanged: (value) =>
                  setState(() => _peakingSensitivityDraft = value),
              onChangeEnd: (value) =>
                  widget.host.setPeaking(true, value, _peakingStrength),
            ),
            LabeledSlider(
              label: 'Peaking strength',
              valueLabel: '${(_peakingStrength * 100).round()}%',
              value: _peakingStrength,
              min: 0.1,
              max: 1,
              onChanged: (value) =>
                  setState(() => _peakingStrengthDraft = value),
              onChangeEnd: (value) =>
                  widget.host.setPeaking(true, _peakingSensitivity, value),
            ),
          ],
        ],
      ),
    );
  }

  Widget _toggleChip(
    String label,
    IconData icon,
    bool selected,
    ValueChanged<bool> onChanged,
  ) {
    return FilterChip(
      visualDensity: VisualDensity.compact,
      materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
      avatar: Icon(
        icon,
        size: 13,
        color: selected ? UiTokens.accent : UiTokens.muted,
      ),
      label: Text(label, style: const TextStyle(fontSize: 10)),
      selected: selected,
      onSelected: onChanged,
      selectedColor: UiTokens.accent.withValues(alpha: 0.18),
      checkmarkColor: UiTokens.accent,
    );
  }

  Widget _processingCard(BuildContext context) {
    return DeckCard(
      title: 'Processing',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _chipGroup(
            'Sharpening',
            state.edgeModes,
            state.edgeMode,
            widget.host.setEdgeMode,
          ),
          const SizedBox(height: 8),
          _chipGroup(
            'Noise reduction',
            state.noiseReductionModes,
            state.noiseReduction,
            widget.host.setNoiseReduction,
          ),
          const SizedBox(height: 8),
          _chipGroup(
            'Anti-banding',
            state.antibandingModes,
            state.antibanding,
            widget.host.setAntibanding,
          ),
        ],
      ),
    );
  }

  Widget _chipGroup(
    String label,
    List<String> values,
    String selected,
    ValueChanged<String> onSelected,
  ) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label.toUpperCase(), style: Theme.of(context).textTheme.labelSmall),
        const SizedBox(height: 4),
        Wrap(
          spacing: 4,
          runSpacing: 4,
          children: [
            for (final value in values)
              ChoiceChip(
                visualDensity: VisualDensity.compact,
                materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                label: Text(
                  humanizeWireValue(value),
                  style: const TextStyle(fontSize: 10),
                ),
                selected: value == selected,
                onSelected: (_) => onSelected(value),
              ),
          ],
        ),
      ],
    );
  }

  Widget _deviceCard(BuildContext context) {
    return DeckCard(
      title: 'Device',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _infoRow('Device key', state.deviceKey),
          _infoRow('Database', 'v${state.deviceDatabaseVersion}'),
          _infoRow(
            'Calibration',
            state.deviceCalibrationActive
                ? (state.deviceCalibrationSource ?? 'active')
                : 'identity · no measured data',
          ),
          _infoRow(
            'Quirks',
            state.deviceQuirks.isEmpty ? 'none' : state.deviceQuirks.join(', '),
          ),
          const Divider(height: 16, color: UiTokens.border),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            dense: true,
            title: const Text('Opt-in device reports'),
            subtitle: const Text('Local only; never uploaded automatically.'),
            value: state.deviceReportOptIn,
            onChanged: (value) => widget.host.setDeviceReportOptIn(value),
          ),
          Row(
            children: [
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: state.deviceReportOptIn && !state.deviceReportRunning
                      ? () => widget.host.runDeviceReport()
                      : null,
                  icon: state.deviceReportRunning
                      ? const SizedBox(
                          width: 14,
                          height: 14,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.analytics_outlined, size: 16),
                  label: Text(
                    state.deviceReportRunning ? 'Probing…' : 'Run report',
                  ),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: OutlinedButton(
                  onPressed: () => widget.host.reloadDeviceDatabase(),
                  child: const Text('Reload DB'),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          SizedBox(
            width: double.infinity,
            child: OutlinedButton.icon(
              onPressed: widget.onCloseCamera,
              icon: const Icon(Icons.videocam_off_outlined, size: 16),
              label: const Text('Close camera'),
            ),
          ),
        ],
      ),
    );
  }

  Widget _clipCard(BuildContext context) {
    return DeckCard(
      title: 'Last clip',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (state.clipPath == null)
            Text(
              'No clip in this session.',
              style: Theme.of(context).textTheme.bodySmall,
            )
          else
            SelectableText(
              state.clipPath!,
              style: Theme.of(context).textTheme.bodySmall,
            ),
          if (state.recordingError != null) ...[
            const SizedBox(height: 8),
            Text(
              state.recordingError!,
              style: const TextStyle(color: UiTokens.danger),
            ),
          ],
        ],
      ),
    );
  }

  Widget _footer(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(14, 7, 14, 9),
      decoration: const BoxDecoration(
        border: Border(top: BorderSide(color: UiTokens.border)),
      ),
      child: Text(
        'Kotlin owns camera state · DB v${state.deviceDatabaseVersion}',
        style: Theme.of(context).textTheme.bodySmall?.copyWith(fontSize: 10),
      ),
    );
  }

  Future<String?> _askProfileName(String title, String initial) async {
    final name = await showDialog<String>(
      context: context,
      builder: (context) => _ProfileNameDialog(title: title, initial: initial),
    );
    if (name == null || name.isEmpty) return null;
    return name;
  }

  Future<void> _saveShootProfile() async {
    final name = await _askProfileName(
      'Save shoot profile',
      'Profile ${state.shootProfiles.length + 1}',
    );
    if (name != null) widget.host.saveShootProfile(name);
  }

  Future<void> _renameShootProfile() async {
    final current = state.shootProfiles.firstWhere(
      (profile) => profile.id == state.activeShootProfile,
      orElse: () => ShootProfile(id: '', name: '', lensCount: 0),
    );
    if (current.id.isEmpty) return;
    final name = await _askProfileName('Rename shoot profile', current.name);
    if (name != null) widget.host.renameShootProfile(current.id, name);
  }

  Future<void> _deleteShootProfile() async {
    final profiles = state.shootProfiles;
    if (profiles.length <= 1) return;
    final current = profiles.firstWhere(
      (profile) => profile.id == state.activeShootProfile,
      orElse: () => profiles.first,
    );
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Delete shoot profile?'),
        content: Text('“${current.name}” will be removed.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Delete'),
          ),
        ],
      ),
    );
    if (confirmed == true) widget.host.deleteShootProfile(current.id);
  }

  List<RecordingMode> get _enabledModes {
    final offered = state.recordingModes
        .where((mode) => mode.enabled && mode.height != 720)
        .toList();
    if (offered.isNotEmpty) return offered;
    final enabled = state.recordingModes.where((mode) => mode.enabled).toList();
    return enabled.isEmpty ? state.recordingModes : enabled;
  }

  void _cycleMode(int delta) {
    final modes = _enabledModes;
    if (modes.length < 2) return;
    final index = modes.indexWhere((mode) => mode.id == state.recordingMode);
    final next = modes[(index < 0 ? 0 : index + delta) % modes.length];
    widget.host.selectRecordingMode(next.id);
  }

  String _cameraLabel(String id) {
    final index = state.cameraIds.indexOf(id);
    if (index >= 0 && index < state.cameraLabels.length) {
      return state.cameraLabels[index];
    }
    return 'camera $id';
  }

  String _modeShort(RecordingMode mode) =>
      '${_sizeLabel(mode)}${mode.fps}p ${mode.tenBit ? '10b' : '8b'}';

  String _sizeLabel(RecordingMode mode) =>
      _sizeLabelFor(mode.width.toInt(), mode.height.toInt());

  Widget _infoRow(String label, String value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 92,
            child: Text(
              label,
              style: Theme.of(
                context,
              ).textTheme.bodySmall?.copyWith(color: UiTokens.muted),
            ),
          ),
          Expanded(
            child: Text(
              value,
              style: Theme.of(context).textTheme.bodySmall,
              textAlign: TextAlign.right,
            ),
          ),
        ],
      ),
    );
  }

  double _shutterCeiling(CameraState state) {
    const oneFrameAt24 = 1000000000 / 24;
    return state.shutterMaxNs < oneFrameAt24
        ? state.shutterMaxNs.toDouble()
        : oneFrameAt24.toDouble();
  }
}

/// Owns the text controller so it outlives the dialog's exit animation.
class _ProfileNameDialog extends StatefulWidget {
  const _ProfileNameDialog({required this.title, required this.initial});

  final String title;
  final String initial;

  @override
  State<_ProfileNameDialog> createState() => _ProfileNameDialogState();
}

class _ProfileNameDialogState extends State<_ProfileNameDialog> {
  late final TextEditingController _controller = TextEditingController(
    text: widget.initial,
  );

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _submit() => Navigator.of(context).pop(_controller.text.trim());

  @override
  Widget build(BuildContext context) {
    // Landscape IMEs can leave little room above the keyboard, so the prompt
    // stays a single compact row instead of a title + stacked buttons.
    return Dialog(
      alignment: Alignment.topCenter,
      insetPadding: const EdgeInsets.fromLTRB(24, 16, 24, 16),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 460),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 10, 8, 10),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Expanded(
                child: TextField(
                  controller: _controller,
                  autofocus: true,
                  textInputAction: TextInputAction.done,
                  decoration: InputDecoration(
                    labelText: widget.title,
                    isDense: true,
                  ),
                  onSubmitted: (_) => _submit(),
                ),
              ),
              const SizedBox(width: 4),
              TextButton(
                onPressed: () => Navigator.of(context).pop(),
                child: const Text('Cancel'),
              ),
              FilledButton(onPressed: _submit, child: const Text('Save')),
            ],
          ),
        ),
      ),
    );
  }
}
