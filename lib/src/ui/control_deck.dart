import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart';

import '../pigeon/slatecam.g.dart';
import 'ui_tokens.dart';
import 'widgets.dart';

const _angleOptions = [45.0, 90.0, 180.0, 270.0, 360.0];

const _gammaOptions = <String, String>{
  'standard': 'Standard',
  'movie': 'Movie',
  'still': 'Still',
  'cine1': 'Cine1',
  'cine2': 'Cine2',
  'cine3': 'Cine3',
  'cine4': 'Cine4',
  'itu709': 'ITU709',
  'itu709_800': 'ITU709(800%)',
  'scinetone': 'S-Cinetone',
};

const _blackGammaRangeOptions = <String, String>{
  'wide': 'Wide',
  'middle': 'Middle',
  'narrow': 'Narrow',
};

const _kneeModeOptions = <String, String>{'auto': 'Auto', 'manual': 'Manual'};

const _kneeSensitivityOptions = <String, String>{
  'high': 'High',
  'mid': 'Mid',
  'low': 'Low',
};

const _colorModeOptions = <String, String>{
  'movie': 'Movie',
  'still': 'Still',
  'cinema': 'Cinema',
  'pro': 'Pro',
  'itu709_matrix': 'ITU709 Matrix',
  'mono': 'B & W',
  'bt2020': 'BT.2020',
  'bt709': '709',
};

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
    required this.onOpenClips,
    required this.showGuides,
    required this.onGuidesChanged,
    required this.showHistogram,
    required this.onHistogramChanged,
    required this.showWaveform,
    required this.onWaveformChanged,
    required this.telemetry,
    required this.audio,
  });

  final CameraState state;
  final SlateCamHostApi host;
  final VoidCallback onClose;
  final VoidCallback onCloseCamera;
  final VoidCallback onOpenClips;
  final bool showGuides;
  final ValueChanged<bool> onGuidesChanged;
  final bool showHistogram;
  final ValueChanged<bool> onHistogramChanged;
  final bool showWaveform;
  final ValueChanged<bool> onWaveformChanged;
  final ValueListenable<CameraTelemetry?> telemetry;
  final ValueListenable<AudioTelemetry?> audio;

  @override
  State<ControlDeck> createState() => _ControlDeckState();
}

class _ControlDeckState extends State<ControlDeck> {
  final ScrollController _scrollController = ScrollController();
  double? _isoDraft;
  double? _shutterDraft;
  double? _meterSpeedDraft;
  double? _focusSensitivityDraft;
  double? _focusTransitionDraft;
  double? _wbKelvinDraft;
  double? _wbTintDraft;
  final Map<String, double> _profileDrafts = {};
  double? _peakingSensitivityDraft;
  double? _peakingStrengthDraft;
  double? _audioGainDraft;

  CameraState get state => widget.state;

  @override
  void didUpdateWidget(covariant ControlDeck oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.state.iso != state.iso) _isoDraft = null;
    if (oldWidget.state.shutterNs != state.shutterNs) _shutterDraft = null;
    if (oldWidget.state.meteringSpeed != state.meteringSpeed) {
      _meterSpeedDraft = null;
    }
    if (oldWidget.state.focusShiftSensitivity != state.focusShiftSensitivity) {
      _focusSensitivityDraft = null;
    }
    if (oldWidget.state.focusTransitionSpeed != state.focusTransitionSpeed) {
      _focusTransitionDraft = null;
    }
    if (oldWidget.state.whiteBalanceKelvin != state.whiteBalanceKelvin) {
      _wbKelvinDraft = null;
    }
    if (oldWidget.state.whiteBalanceTint != state.whiteBalanceTint) {
      _wbTintDraft = null;
    }
    if (oldWidget.state.profileSettings != state.profileSettings) {
      _profileDrafts.clear();
    }
    if (oldWidget.state.peakingSensitivity != state.peakingSensitivity) {
      _peakingSensitivityDraft = null;
    }
    if (oldWidget.state.peakingStrength != state.peakingStrength) {
      _peakingStrengthDraft = null;
    }
    if (oldWidget.state.audioGainDb != state.audioGainDb) {
      _audioGainDraft = null;
    }
  }

  double get _iso => _isoDraft ?? state.iso.toDouble();
  double get _shutter => _shutterDraft ?? state.shutterNs.toDouble();
  double get _meterSpeed => _meterSpeedDraft ?? state.meteringSpeed;
  double get _focusSensitivity =>
      _focusSensitivityDraft ?? state.focusShiftSensitivity;
  double get _focusTransition =>
      _focusTransitionDraft ?? state.focusTransitionSpeed;
  double get _peakingSensitivity =>
      _peakingSensitivityDraft ?? state.peakingSensitivity;
  double get _peakingStrength => _peakingStrengthDraft ?? state.peakingStrength;
  double get _wbKelvin => _wbKelvinDraft ?? state.whiteBalanceKelvin.toDouble();
  double get _wbTint => _wbTintDraft ?? state.whiteBalanceTint.toDouble();
  double get _audioGain => _audioGainDraft ?? state.audioGainDb;

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
                    ValueListenableBuilder<CameraTelemetry?>(
                      valueListenable: widget.telemetry,
                      builder: (context, _, _) => _exposureCard(context),
                    ),
                    _audioCard(context),
                    _focusCard(context),
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
                      children: [...primary, ...secondary, ...tertiary],
                    );
                  } else if (constraints.maxWidth >= 720) {
                    final width = (constraints.maxWidth - 16) / 3;
                    columns = Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        SizedBox(
                          width: width,
                          child: Column(children: primary),
                        ),
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
                          child: Column(children: [...primary]),
                        ),
                        const SizedBox(width: 8),
                        SizedBox(
                          width: width,
                          child: Column(
                            children: [
                              ...secondary,
                              _monitorCard(context),
                              _clipCard(context),
                              _processingCard(context),
                            ],
                          ),
                        ),
                      ],
                    );
                  }
                  // Device is diagnostics, not a shooting control: always last.
                  return Column(children: [columns, _deviceCard(context)]);
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
              _shootCircle('set', 'L'),
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
                tooltip: 'Clips',
                visualDensity: VisualDensity.compact,
                onPressed: widget.onOpenClips,
                icon: const Icon(Icons.video_library_outlined, size: 20),
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
                  '${state.lensSetMode == 'set' ? 'LENS SET' : 'SINGLE LENS'} · '
                  '$_displayWidth×$_displayHeight · '
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
    final selected = state.lensSetMode == mode;
    return Semantics(
      button: true,
      selected: selected,
      label: mode == 'set' ? 'lens set mode' : 'single lens mode',
      child: Tooltip(
        message: mode == 'set' ? 'Lens set (multi-camera)' : 'Single lens',
        child: Material(
          color: Colors.transparent,
          shape: const CircleBorder(),
          child: InkWell(
            customBorder: const CircleBorder(),
            onTap: () => widget.host.setLensSetMode(mode),
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
    for (final lens in state.lensSetLenses) {
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
          _stepButton(
            Icons.chevron_left,
            'Previous mode',
            () => _cycleMode(-1),
          ),
          ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 150),
            child: Semantics(
              label:
                  'recording mode: ${mode != null ? _modeShort(mode) : state.recordingMode}',
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
        tint: UiTokens.cardRecording,
        child: Text(
          'No recording modes reported.',
          style: Theme.of(context).textTheme.bodySmall,
        ),
      );
    }

    final resolution = current.resolution;
    final wide = current.aspect == '16:9';
    final is4k = resolution == '4K';
    final is1080 = resolution == '1080';

    final rates =
        state.recordingModes
            .where(
              (mode) =>
                  mode.resolution == resolution &&
                  (mode.aspect == '16:9') == wide,
            )
            .map((mode) => mode.fps.toInt())
            .toSet()
            .toList()
          ..sort();

    // Only show depths that can actually be selected at this resolution,
    // aspect and rate. A 4K 4:3 mode is 8-bit until the probe verifies it.
    final depths = state.recordingModes
        .where(
          (mode) =>
              mode.enabled &&
              mode.resolution == resolution &&
              mode.aspect == current.aspect &&
              mode.fps == current.fps,
        )
        .map((mode) => mode.bitDepth)
        .toSet();

    return DeckCard(
      title: 'Recording mode',
      tint: UiTokens.cardRecording,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _modeRow('Resolution', [
            _modeChip(
              label: '4K',
              tooltip: '4K family, exact size follows the aspect',
              selected: is4k,
              onTap: () => _selectResolution('4K'),
            ),
            _modeChip(
              label: '1080',
              tooltip: '1080 family, exact size follows the aspect',
              selected: is1080,
              onTap: () => _selectResolution('1080'),
            ),
          ]),
          const SizedBox(height: 4),
          _modeRow('Aspect', [
            _modeChip(
              label: _portrait ? '9:16' : '16:9',
              tooltip: _portrait ? 'Vertical 9:16 video' : 'Widescreen 16:9',
              selected: wide,
              onTap: () => _selectAspect(true),
            ),
            _modeChip(
              label: '4:3',
              tooltip: 'Open-gate 4:3',
              selected: !wide,
              onTap: () => _selectAspect(false),
            ),
          ]),
          const SizedBox(height: 4),
          _modeRow('Frame rate', [
            for (final fps in rates)
              _modeChip(
                label: '$fps',
                tooltip: '$fps fps',
                selected: fps == current.fps,
                onTap: () => _selectFps(fps),
              ),
          ]),
          const SizedBox(height: 4),
          _modeRow('Bit depth', [
            for (final bits in [10, 8])
              if (depths.contains(bits))
                _modeChip(
                  label: '$bits-bit',
                  tooltip: bits == 10
                      ? '10-bit HLG where probe-verified'
                      : '8-bit SDR',
                  selected: current.bitDepth == bits,
                  onTap: () => _selectBit(bits),
                ),
          ]),
          const SizedBox(height: 4),
          _modeRow('Bitrate', [
            _modeChip(
              label: 'VBR',
              tooltip: 'System variable bitrate · '
                  '${_formatBitrate(current.bitrate)}',
              selected: state.bitrateMode == 'vbr',
              onTap: () => widget.host.setRecordingBitrate('vbr', 0),
            ),
            for (final bps in state.bitratePresets)
              _modeChip(
                label: '${bps ~/ 1000000}M',
                tooltip: 'Constant bitrate · ${_formatBitrate(bps)}',
                selected:
                    state.bitrateMode == 'cbr' && state.bitrateTarget == bps,
                onTap: () => widget.host.setRecordingBitrate('cbr', bps),
              ),
            _modeValue(_formatBitrate(state.bitrateTarget)),
          ]),
        ],
      ),
    );
  }

  Widget _modeValue(String text) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
      decoration: BoxDecoration(
        color: UiTokens.surfaceRaised,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: UiTokens.border),
      ),
      child: Text(
        text,
        style: const TextStyle(fontSize: 10, fontWeight: FontWeight.w700),
      ),
    );
  }

  String _formatBitrate(int bitsPerSecond) {
    if (bitsPerSecond >= 1000000) {
      final mbps = bitsPerSecond / 1000000;
      final text = mbps == mbps.roundToDouble()
          ? mbps.toStringAsFixed(0)
          : mbps.toStringAsFixed(1);
      return '$text Mbps';
    }
    return '${(bitsPerSecond / 1000).round()} kbps';
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
              style: Theme.of(context).textTheme.bodySmall
                  ?.copyWith(fontSize: 10),
            ),
          ),
        ),
        Expanded(child: Wrap(spacing: 4, runSpacing: 4, children: chips)),
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

  void _selectResolution(String resolution) {
    final current = _currentMode;
    _chooseMode(
      resolution,
      current?.aspect ?? '4:3',
      current?.fps.toInt() ?? 30,
      current?.bitDepth ?? 8,
    );
  }

  void _selectAspect(bool wide) {
    final current = _currentMode;
    _chooseMode(
      current?.resolution ?? '1080',
      wide ? '16:9' : '4:3',
      current?.fps.toInt() ?? 30,
      current?.bitDepth ?? 8,
    );
  }

  void _selectFps(int fps) {
    final current = _currentMode;
    if (current == null) return;
    _chooseMode(current.resolution, current.aspect, fps, current.bitDepth);
  }

  void _selectBit(int bits) {
    final current = _currentMode;
    if (current == null) return;
    _chooseMode(current.resolution, current.aspect, current.fps.toInt(), bits);
  }

  /// Chooses a mode by the core's recording-mode elements. It keeps the
  /// requested rate and depth where possible, then falls back within the same
  /// resolution/aspect, and finally asks for the first mode at that
  /// resolution/aspect so its disabled reason is shown.
  void _chooseMode(String resolution, String aspect, int fps, int bitDepth) {
    RecordingMode? exact(int rate, int depth) {
      for (final mode in state.recordingModes) {
        if (mode.enabled &&
            mode.resolution == resolution &&
            mode.aspect == aspect &&
            mode.fps == rate &&
            mode.bitDepth == depth) {
          return mode;
        }
      }
      return null;
    }

    RecordingMode? nearestRate(int depth) {
      RecordingMode? best;
      var bestDelta = 1 << 30;
      for (final mode in state.recordingModes) {
        if (!mode.enabled ||
            mode.resolution != resolution ||
            mode.aspect != aspect ||
            mode.bitDepth != depth) {
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

    RecordingMode? firstEnabled() {
      for (final mode in state.recordingModes) {
        if (mode.enabled &&
            mode.resolution == resolution &&
            mode.aspect == aspect) {
          return mode;
        }
      }
      return null;
    }

    RecordingMode? first() {
      for (final mode in state.recordingModes) {
        if (mode.resolution == resolution && mode.aspect == aspect) {
          return mode;
        }
      }
      return null;
    }

    final mode =
        exact(fps, bitDepth) ??
        exact(fps, bitDepth == 10 ? 8 : 10) ??
        nearestRate(bitDepth) ??
        nearestRate(bitDepth == 10 ? 8 : 10) ??
        firstEnabled() ??
        first();
    if (mode != null) widget.host.selectRecordingMode(mode.id);
  }

  bool get _portrait =>
      MediaQuery.orientationOf(context) == Orientation.portrait;

  bool get _verticalFrame => state.previewQuarterTurns.isOdd;

  int get _displayWidth =>
      _verticalFrame ? state.previewHeight : state.previewWidth;

  int get _displayHeight =>
      _verticalFrame ? state.previewWidth : state.previewHeight;

  String _sizeLabel(RecordingMode mode) {
    if (mode.aspect == '16:9') {
      return mode.resolution == '4K' ? '4K' : '${mode.resolution}p';
    }
    return mode.resolution == '4K' ? '4K4:3' : '4:3';
  }

  /// Lens badges use the 35 mm-equivalent focal length, e.g. `16mm`, because
  /// that is how the operator picks a lens on set. Facing is added only when
  /// two lenses share the same value.
  Widget _shootLensRow(List<LensSetLens> lenses, bool editable) {
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

  Widget _shootLensCircle(LensSetLens lens, bool editable, bool withFacing) {
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
                ? () => widget.host.setLensInSet(lens.id, !lens.selected)
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
                      color: lens.selected ? UiTokens.accent : UiTokens.border,
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

  String _focalLabel(LensSetLens lens) {
    final match = RegExp(r'(\d+)mm').firstMatch(lens.label);
    if (match != null) return '${match.group(1)}mm';
    final digits = RegExp(r'\d+').firstMatch(lens.label)?.group(0);
    return digits != null ? '${digits}mm' : lens.role;
  }

  Widget _shootSetCard(BuildContext context) {
    final lenses = state.lensSetLenses;
    final profiles = state.shootProfiles;
    final active = profiles.firstWhere(
      (profile) => profile.id == state.activeShootProfile,
      orElse: () => ShootProfile(id: '', name: 'Default', lensCount: 0),
    );
    final editable = !state.isRecording;
    return DeckCard(
      title: 'Lens set & profiles',
      tint: UiTokens.cardShoot,
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
          _modeRow('Mode', [
            _modeChip(
              label: 'Single lens',
              tooltip: 'Record with the active lens only',
              selected: state.lensSetMode == 'single',
              onTap: () => widget.host.setLensSetMode('single'),
            ),
            _modeChip(
              label: 'Lens set',
              tooltip: 'At least two lenses share one recording mode',
              selected: state.lensSetMode == 'set',
              onTap: () => widget.host.setLensSetMode('set'),
            ),
          ]),
          const SizedBox(height: 4),
          Text(
            state.isRecording
                ? 'Stop recording to edit the lens set or profiles.'
                : 'Lens set uses the common recording mode of at least two '
                      'selected lenses; the mode follows their shared '
                      'capabilities. Macro lenses are never included.',
            style: Theme.of(context).textTheme.bodySmall
                ?.copyWith(fontSize: 10),
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

  String _exposureModeLabel(String mode) => switch (mode) {
    'AE' => 'AE',
    'P' => 'P',
    'S' => 'S',
    'M' => 'M',
    _ => mode,
  };

  String _exposureModeTooltip(String mode) => switch (mode) {
    'AE' => 'HAL auto exposure',
    'P' => 'Program auto: HAL AE with software exposure bias',
    'S' => 'Shutter priority: fixed shutter, automatic ISO',
    'M' => 'Manual ISO and shutter',
    _ => mode,
  };

  Widget _exposureCard(BuildContext context) {
    final manualIso = state.exposureMode == 'M';
    final manualShutter =
        state.exposureMode == 'S' || state.exposureMode == 'M';
    final isoValueLabel = manualIso ? '${state.iso}' : 'auto · ${state.iso}';
    final shutterValueLabel = manualShutter
        ? formatShutterSummary(state.shutterNs)
        : 'auto · ${formatShutterSummary(state.shutterNs)}';
    final shutterRange = _shutterRange(state);
    final bandLabel = state.antibanding == 'auto'
        ? 'auto'
        : state.antibanding.replaceAll('hz', ' Hz');
    final shutterGridHint = !manualShutter || state.shutterStepNs == null
        ? null
        : 'Flicker-safe $bandLabel · '
              '${formatShutterSummary(state.shutterStepNs!)} steps · '
              '${state.shutterAngle.round()}°';

    return DeckCard(
      title: 'Exposure',
      tint: UiTokens.cardExposure,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _modeRow('Mode', [
            for (final mode in state.exposureModes)
              _modeChip(
                label: _exposureModeLabel(mode),
                tooltip: _exposureModeTooltip(mode),
                selected: state.exposureMode == mode,
                onTap: () => widget.host.setExposureMode(mode),
              ),
          ]),
          const SizedBox(height: 2),
          _modeRow('Metering', [
            for (final mode in state.meteringModes)
              _modeChip(
                label: _meteringLabel(mode),
                tooltip: _meteringTooltip(mode),
                selected: state.meteringMode == mode,
                onTap: () => widget.host.setMeteringMode(mode),
              ),
          ]),
          LabeledSlider(
            label: 'Meter speed',
            valueLabel: '${(_meterSpeed * 100).round()}%',
            value: _meterSpeed,
            min: 0,
            max: 1,
            enabled: state.exposureMode == 'P' || state.exposureMode == 'S',
            onChanged: (value) => setState(() => _meterSpeedDraft = value),
            onChangeEnd: widget.host.setMeteringSpeed,
          ),
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
            min: shutterRange.min,
            max: shutterRange.max,
            divisions: shutterRange.divisions,
            enabled: manualShutter,
            onChanged: (value) => setState(() => _shutterDraft = value),
            onChangeEnd: (value) => widget.host.setShutterNs(value.round()),
          ),
          if (shutterGridHint != null)
            Padding(
              padding: const EdgeInsets.only(bottom: 6),
              child: Text(
                shutterGridHint,
                style: Theme.of(context).textTheme.labelSmall
                    ?.copyWith(color: UiTokens.muted),
              ),
            ),
          Padding(
            padding: const EdgeInsets.only(bottom: 6),
            child: Text(
              'Shutter capped at 1/48 s (48 fps) to avoid ghosting.',
              style: Theme.of(context).textTheme.labelSmall
                  ?.copyWith(color: UiTokens.muted),
            ),
          ),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              for (final angle in _angleOptions)
                ChoiceChip(
                  visualDensity: VisualDensity.compact,
                  materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  label: Text(
                    '${angle.toInt()}°',
                    style: const TextStyle(fontSize: 10),
                  ),
                  selected:
                      state.shutterAngle.roundToDouble() == angle &&
                      manualShutter,
                  onSelected: manualShutter
                      ? (_) => widget.host.setShutterAngle(angle)
                      : null,
                ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _audioCard(BuildContext context) {
    final inputs = state.audioInputs;
    final selected = inputs.where((input) => input.key == state.audioInputKey);
    final input = selected.isEmpty ? null : selected.first;
    final channels = state.audioChannels == 1 ? 'mono' : 'stereo';
    final rate = state.audioSampleRate >= 1000
        ? '${(state.audioSampleRate / 1000).round()} kHz'
        : '${state.audioSampleRate} Hz';
    final locked = state.isRecording;
    return DeckCard(
      title: 'Audio',
      tint: UiTokens.cardAudio,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text('INPUT', style: Theme.of(context).textTheme.labelSmall),
              const Spacer(),
              IconButton(
                tooltip: 'Rescan audio inputs',
                visualDensity: VisualDensity.compact,
                onPressed: widget.host.refreshAudioInputs,
                icon: const Icon(Icons.refresh, size: 16),
              ),
            ],
          ),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              for (final option in inputs)
                ChoiceChip(
                  visualDensity: VisualDensity.compact,
                  materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  label: Text(
                    option.name,
                    style: const TextStyle(fontSize: 10),
                  ),
                  selected: option.key == state.audioInputKey,
                  onSelected: locked
                      ? null
                      : (_) => widget.host.setAudioInput(option.key),
                ),
            ],
          ),
          const SizedBox(height: 4),
          Text(
            '$rate · $channels · ${input?.type ?? 'platform default'}'
            '${state.audioSource == null ? '' : ' · ${state.audioSource}'}',
            style: Theme.of(context).textTheme.bodySmall,
          ),
          if (locked)
            Text(
              'Input is locked while recording; gain stays live.',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          if (!state.audioPermissionGranted) ...[
            const SizedBox(height: 4),
            Row(
              children: [
                const Expanded(
                  child: Text(
                    'Microphone permission is not granted.',
                    style: TextStyle(color: UiTokens.danger, fontSize: 11),
                  ),
                ),
                TextButton(
                  onPressed: widget.host.requestCameraPermissions,
                  child: const Text('Grant', style: TextStyle(fontSize: 11)),
                ),
              ],
            ),
          ] else if (state.audioError != null) ...[
            const SizedBox(height: 4),
            Text(
              state.audioError!,
              style: const TextStyle(color: UiTokens.danger, fontSize: 11),
            ),
          ] else
            Text(
              state.isRecording
                  ? 'Recording audio · meter is post-gain'
                  : state.audioAvailable
                  ? 'Monitoring · pre-roll levels'
                  : 'Audio input inactive',
              style: TextStyle(
                fontSize: 11,
                color: state.audioAvailable ? UiTokens.muted : UiTokens.danger,
              ),
            ),
          const SizedBox(height: 8),
          ValueListenableBuilder<AudioTelemetry?>(
            valueListenable: widget.audio,
            builder: (context, level, _) =>
                _AudioMeter(level: level, active: state.audioAvailable),
          ),
          LabeledSlider(
            label: 'Input gain',
            valueLabel:
                '${_audioGain >= 0 ? '+' : ''}${_audioGain.toStringAsFixed(1)} dB',
            value: _audioGain.clamp(-12.0, 24.0),
            min: -12,
            max: 24,
            divisions: 72,
            onChanged: (value) => setState(() => _audioGainDraft = value),
            onChangeEnd: (value) => widget.host.setAudioGain(value),
          ),
          Text(
            'Applied before the meter and the AAC encoder.',
            style: Theme.of(context).textTheme.labelSmall,
          ),
        ],
      ),
    );
  }

  Widget _colourCard(BuildContext context) {
    final calibration = state.deviceCalibrationActive
        ? (state.deviceCalibrationSource ?? 'measured matrix')
        : 'identity · no measured data';
    final settings = state.profileSettings;
    final profileIndex = state.pictureProfiles.indexWhere(
      (profile) => profile.id == state.profile,
    );
    final activeProfile = profileIndex >= 0
        ? state.pictureProfiles[profileIndex]
        : null;
    final editable = activeProfile != null && !activeProfile.builtIn;
    return DeckCard(
      title: 'Picture profile',
      tint: UiTokens.cardPicture,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('PROFILE', style: Theme.of(context).textTheme.labelSmall),
          const SizedBox(height: 4),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              for (final profile in state.pictureProfiles)
                ChoiceChip(
                  visualDensity: VisualDensity.compact,
                  materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  label: Text(
                    profile.name,
                    style: const TextStyle(fontSize: 10),
                  ),
                  selected: profile.id == state.profile,
                  onSelected: (_) => widget.host.setProfile(profile.id),
                ),
              ActionChip(
                avatar: const Icon(Icons.add, size: 14),
                visualDensity: VisualDensity.compact,
                materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                label: const Text('New', style: TextStyle(fontSize: 10)),
                onPressed: _createPictureProfile,
              ),
            ],
          ),
          if (state.profile == 'slatelook')
            Text(
              'S-Cinetone-inspired look: soft shoulder, gentle knee, warm skin.',
              style: Theme.of(context).textTheme.bodySmall
                  ?.copyWith(color: UiTokens.muted),
            )
          else if (state.profile == 'standard')
            Text(
              'Camera look: no creative processing.',
              style: Theme.of(context).textTheme.bodySmall
                  ?.copyWith(color: UiTokens.muted),
            )
          else if (editable)
            Text(
              'Custom look. Changes save automatically.',
              style: Theme.of(context).textTheme.bodySmall
                  ?.copyWith(color: UiTokens.muted),
            )
          else
            Text(
              'Preset look. Pick a user profile to edit every control.',
              style: Theme.of(context).textTheme.bodySmall
                  ?.copyWith(color: UiTokens.muted),
            ),
          if (editable) ...[
            const Divider(height: 10, color: UiTokens.border),
            Row(
              children: [
                Expanded(
                  child: Text(
                    'CUSTOM PROFILE',
                    style: Theme.of(context).textTheme.labelSmall,
                  ),
                ),
                IconButton(
                  tooltip: 'Rename profile',
                  visualDensity: VisualDensity.compact,
                  padding: EdgeInsets.zero,
                  constraints: const BoxConstraints.tightFor(
                    width: 26,
                    height: 26,
                  ),
                  onPressed: _renamePictureProfile,
                  icon: const Icon(Icons.edit_outlined, size: 15),
                ),
                IconButton(
                  tooltip: 'Delete profile',
                  visualDensity: VisualDensity.compact,
                  padding: EdgeInsets.zero,
                  constraints: const BoxConstraints.tightFor(
                    width: 26,
                    height: 26,
                  ),
                  onPressed: _deletePictureProfile,
                  icon: const Icon(Icons.delete_outline, size: 15),
                ),
              ],
            ),
            _profileSlider(
              context,
              key: 'blackLevel',
              label: 'Black level',
              value: settings.blackLevel,
              min: -15,
              max: 15,
              divisions: 30,
              onCommit: (value) => _patchProfile(blackLevel: value.round()),
              onReset: () => _resetProfileParameter('blackLevel'),
            ),
            _profileChoiceGroup(
              'Gamma',
              _gammaOptions,
              settings.gamma,
              (value) => _patchProfile(gamma: value),
              onReset: () => _resetProfileParameter('gamma'),
            ),
            _profileChoiceGroup(
              'Black gamma range',
              _blackGammaRangeOptions,
              settings.blackGammaRange,
              (value) => _patchProfile(blackGammaRange: value),
              onReset: () => _resetProfileParameter('blackGammaRange'),
            ),
            _profileSlider(
              context,
              key: 'blackGammaLevel',
              label: 'Black gamma level',
              value: settings.blackGammaLevel,
              min: -7,
              max: 7,
              divisions: 14,
              onCommit: (value) =>
                  _patchProfile(blackGammaLevel: value.round()),
              onReset: () => _resetProfileParameter('blackGammaLevel'),
            ),
            _profileChoiceGroup(
              'Knee mode',
              _kneeModeOptions,
              settings.kneeMode,
              (value) => _patchProfile(kneeMode: value),
              onReset: () => _resetProfileParameter('kneeMode'),
            ),
            if (settings.kneeMode == 'auto') ...[
              _profileSlider(
                context,
                key: 'kneeAutoMaxPoint',
                label: 'Knee max point',
                value: settings.kneeAutoMaxPoint,
                min: 90,
                max: 100,
                divisions: 10,
                suffix: '%',
                onCommit: (value) =>
                    _patchProfile(kneeAutoMaxPoint: value.round()),
                onReset: () => _resetProfileParameter('kneeAutoMaxPoint'),
              ),
              _profileChoiceGroup(
                'Knee sensitivity',
                _kneeSensitivityOptions,
                settings.kneeAutoSensitivity,
                (value) => _patchProfile(kneeAutoSensitivity: value),
                onReset: () => _resetProfileParameter('kneeAutoSensitivity'),
              ),
            ] else ...[
              _profileSlider(
                context,
                key: 'kneePoint',
                label: 'Knee point',
                value: settings.kneePoint,
                min: 75,
                max: 105,
                divisions: 30,
                suffix: '%',
                onCommit: (value) => _patchProfile(kneePoint: value.round()),
                onReset: () => _resetProfileParameter('kneePoint'),
              ),
              _profileSlider(
                context,
                key: 'kneeSlope',
                label: 'Knee slope',
                value: settings.kneeSlope,
                min: -5,
                max: 5,
                divisions: 10,
                onCommit: (value) => _patchProfile(kneeSlope: value.round()),
                onReset: () => _resetProfileParameter('kneeSlope'),
              ),
            ],
            _profileChoiceGroup(
              'Color mode',
              _colorModeOptions,
              settings.colorMode,
              (value) => _patchProfile(colorMode: value),
              onReset: () => _resetProfileParameter('colorMode'),
            ),
            _profileSlider(
              context,
              key: 'saturation',
              label: 'Saturation',
              value: settings.saturation,
              min: -32,
              max: 32,
              divisions: 64,
              onCommit: (value) => _patchProfile(saturation: value.round()),
              onReset: () => _resetProfileParameter('saturation'),
            ),
            _profileSlider(
              context,
              key: 'colorPhase',
              label: 'Color phase',
              value: settings.colorPhase,
              min: -7,
              max: 7,
              divisions: 14,
              onCommit: (value) => _patchProfile(colorPhase: value.round()),
              onReset: () => _resetProfileParameter('colorPhase'),
            ),
            Text('COLOR DEPTH', style: Theme.of(context).textTheme.labelSmall),
            _profileSlider(
              context,
              key: 'colorDepthR',
              label: 'R',
              value: settings.colorDepthR,
              min: -7,
              max: 7,
              divisions: 14,
              onCommit: (value) => _patchProfile(colorDepthR: value.round()),
              onReset: () => _resetProfileParameter('colorDepthR'),
            ),
            _profileSlider(
              context,
              key: 'colorDepthG',
              label: 'G',
              value: settings.colorDepthG,
              min: -7,
              max: 7,
              divisions: 14,
              onCommit: (value) => _patchProfile(colorDepthG: value.round()),
              onReset: () => _resetProfileParameter('colorDepthG'),
            ),
            _profileSlider(
              context,
              key: 'colorDepthB',
              label: 'B',
              value: settings.colorDepthB,
              min: -7,
              max: 7,
              divisions: 14,
              onCommit: (value) => _patchProfile(colorDepthB: value.round()),
              onReset: () => _resetProfileParameter('colorDepthB'),
            ),
            _profileSlider(
              context,
              key: 'colorDepthC',
              label: 'C',
              value: settings.colorDepthC,
              min: -7,
              max: 7,
              divisions: 14,
              onCommit: (value) => _patchProfile(colorDepthC: value.round()),
              onReset: () => _resetProfileParameter('colorDepthC'),
            ),
            _profileSlider(
              context,
              key: 'colorDepthM',
              label: 'M',
              value: settings.colorDepthM,
              min: -7,
              max: 7,
              divisions: 14,
              onCommit: (value) => _patchProfile(colorDepthM: value.round()),
              onReset: () => _resetProfileParameter('colorDepthM'),
            ),
            _profileSlider(
              context,
              key: 'colorDepthY',
              label: 'Y',
              value: settings.colorDepthY,
              min: -7,
              max: 7,
              divisions: 14,
              onCommit: (value) => _patchProfile(colorDepthY: value.round()),
              onReset: () => _resetProfileParameter('colorDepthY'),
            ),
            TextButton.icon(
              style: TextButton.styleFrom(
                visualDensity: VisualDensity.compact,
                padding: const EdgeInsets.symmetric(horizontal: 4),
              ),
              onPressed: _resetAllProfileParameters,
              icon: const Icon(Icons.restart_alt, size: 15),
              label: const Text(
                'Reset all parameters',
                style: TextStyle(fontSize: 11),
              ),
            ),
          ],
          const Divider(height: 10, color: UiTokens.border),
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
              if (state.manualWhiteBalanceSupported)
                ChoiceChip(
                  visualDensity: VisualDensity.compact,
                  materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  label: Text('Manual', style: const TextStyle(fontSize: 10)),
                  selected: state.whiteBalanceMode == 'manual',
                  onSelected: (_) => widget.host.setManualWhiteBalance(
                    state.whiteBalanceKelvin,
                    state.whiteBalanceTint,
                  ),
                ),
            ],
          ),
          if (state.manualWhiteBalanceSupported) ...[
            const SizedBox(height: 2),
            LabeledSlider(
              label: 'Temperature',
              valueLabel: '${_wbKelvin.round()} K',
              value: _wbKelvin.clamp(2000.0, 10000.0),
              min: 2000,
              max: 10000,
              divisions: 80,
              onChanged: (value) => setState(() => _wbKelvinDraft = value),
              onChangeEnd: (value) => widget.host.setManualWhiteBalance(
                value.round(),
                _wbTint.round(),
              ),
            ),
            LabeledSlider(
              label: 'Tint',
              valueLabel: _wbTint.round() > 0
                  ? '+${_wbTint.round()}'
                  : '${_wbTint.round()}',
              value: _wbTint.clamp(-100.0, 100.0),
              min: -100,
              max: 100,
              divisions: 40,
              onChanged: (value) => setState(() => _wbTintDraft = value),
              onChangeEnd: (value) => widget.host.setManualWhiteBalance(
                _wbKelvin.round(),
                value.round(),
              ),
            ),
          ],
          const Divider(height: 10, color: UiTokens.border),
          Text(
            'Input calibration',
            style: Theme.of(context).textTheme.bodyMedium,
          ),
          Text(calibration, style: Theme.of(context).textTheme.bodySmall),
        ],
      ),
    );
  }

  void _resetProfileParameter(String key) {
    _profileDrafts.remove(key);
    setState(() {});
    widget.host.resetProfileParameter(key);
  }

  void _resetAllProfileParameters() {
    _profileDrafts.clear();
    setState(() {});
    widget.host.resetProfile();
  }

  void _patchProfile({
    int? blackLevel,
    String? gamma,
    String? blackGammaRange,
    int? blackGammaLevel,
    String? kneeMode,
    int? kneeAutoMaxPoint,
    String? kneeAutoSensitivity,
    int? kneePoint,
    int? kneeSlope,
    String? colorMode,
    int? saturation,
    int? colorPhase,
    int? colorDepthR,
    int? colorDepthG,
    int? colorDepthB,
    int? colorDepthC,
    int? colorDepthM,
    int? colorDepthY,
  }) {
    final s = state.profileSettings;
    _profileDrafts.clear();
    widget.host.setProfileSettings(
      ProfileSettings(
        blackLevel: blackLevel ?? s.blackLevel,
        gamma: gamma ?? s.gamma,
        blackGammaRange: blackGammaRange ?? s.blackGammaRange,
        blackGammaLevel: blackGammaLevel ?? s.blackGammaLevel,
        kneeMode: kneeMode ?? s.kneeMode,
        kneeAutoMaxPoint: kneeAutoMaxPoint ?? s.kneeAutoMaxPoint,
        kneeAutoSensitivity: kneeAutoSensitivity ?? s.kneeAutoSensitivity,
        kneePoint: kneePoint ?? s.kneePoint,
        kneeSlope: kneeSlope ?? s.kneeSlope,
        colorMode: colorMode ?? s.colorMode,
        saturation: saturation ?? s.saturation,
        colorPhase: colorPhase ?? s.colorPhase,
        colorDepthR: colorDepthR ?? s.colorDepthR,
        colorDepthG: colorDepthG ?? s.colorDepthG,
        colorDepthB: colorDepthB ?? s.colorDepthB,
        colorDepthC: colorDepthC ?? s.colorDepthC,
        colorDepthM: colorDepthM ?? s.colorDepthM,
        colorDepthY: colorDepthY ?? s.colorDepthY,
      ),
    );
  }

  Widget _profileSlider(
    BuildContext context, {
    required String key,
    required String label,
    required int value,
    required double min,
    required double max,
    int? divisions,
    String suffix = '',
    required void Function(double value) onCommit,
    VoidCallback? onReset,
  }) {
    final draft = _profileDrafts[key];
    return LabeledSlider(
      label: label,
      valueLabel: '${draft?.round() ?? value}$suffix',
      value: draft ?? value.toDouble(),
      min: min,
      max: max,
      divisions: divisions,
      onReset: onReset,
      onChanged: (next) => setState(() => _profileDrafts[key] = next),
      onChangeEnd: (next) {
        _profileDrafts.remove(key);
        setState(() => onCommit(next));
      },
    );
  }

  Widget _profileChoiceGroup(
    String label,
    Map<String, String> options,
    String selected,
    ValueChanged<String> onSelected, {
    VoidCallback? onReset,
  }) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  label.toUpperCase(),
                  style: Theme.of(context).textTheme.labelSmall,
                ),
              ),
              if (onReset != null)
                IconButton(
                  tooltip: 'Reset $label to default',
                  visualDensity: VisualDensity.compact,
                  padding: EdgeInsets.zero,
                  constraints: const BoxConstraints.tightFor(
                    width: 22,
                    height: 22,
                  ),
                  onPressed: onReset,
                  icon: const Icon(Icons.restart_alt, size: 14),
                ),
            ],
          ),
          const SizedBox(height: 4),
          Wrap(
            spacing: 4,
            runSpacing: 4,
            children: [
              for (final entry in options.entries)
                ChoiceChip(
                  visualDensity: VisualDensity.compact,
                  materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  label: Text(
                    entry.value,
                    style: const TextStyle(fontSize: 10),
                  ),
                  selected: entry.key == selected,
                  onSelected: (_) => onSelected(entry.key),
                ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _monitorCard(BuildContext context) {
    return DeckCard(
      title: 'Monitoring',
      tint: UiTokens.cardMonitor,
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

  Widget _focusCard(BuildContext context) {
    final modes = state.focusModes;
    final focusMax = state.focusMax <= 0 ? 1.0 : state.focusMax;
    final focusValue = state.focusDiopters.clamp(0.0, focusMax);
    return DeckCard(
      title: 'Focus & stabilisation',
      tint: UiTokens.cardFocus,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (modes.isEmpty)
            Text(
              'This camera did not report focus modes.',
              style: Theme.of(context).textTheme.bodySmall,
            )
          else
            _focusModeGroup(modes),
          if (state.focusMode == 'off') ...[
            const SizedBox(height: 4),
            Row(
              children: [
                Text('MF', style: Theme.of(context).textTheme.labelSmall),
                Expanded(
                  child: Slider(
                    value: focusValue,
                    max: focusMax,
                    onChanged: (value) => widget.host.setFocusDiopters(value),
                  ),
                ),
                SizedBox(
                  width: 46,
                  child: Text(
                    formatDiopters(state.focusDiopters),
                    textAlign: TextAlign.end,
                    style: const TextStyle(fontSize: 10),
                  ),
                ),
              ],
            ),
          ],
          if (state.focusModes.contains('software')) ...[
            const SizedBox(height: 2),
            Text(
              'SHOOTING SCENARIO',
              style: Theme.of(context).textTheme.labelSmall,
            ),
            const SizedBox(height: 4),
            Wrap(
              spacing: 4,
              runSpacing: 4,
              children: [
                for (final scenario in state.focusScenarios)
                  _modeChip(
                    label: _scenarioLabel(scenario),
                    tooltip: _scenarioTooltip(scenario),
                    selected:
                        state.focusMode == 'software' &&
                        state.focusScenario == scenario,
                    onTap: () => widget.host.setFocusScenario(scenario),
                  ),
              ],
            ),
            LabeledSlider(
              label: 'Shift sensitivity',
              valueLabel: '${(_focusSensitivity * 100).round()}%',
              value: _focusSensitivity,
              min: 0,
              max: 1,
              onChanged: (value) =>
                  setState(() => _focusSensitivityDraft = value),
              onChangeEnd: widget.host.setFocusShiftSensitivity,
            ),
            LabeledSlider(
              label: 'Transition speed',
              valueLabel: '${(_focusTransition * 100).round()}%',
              value: _focusTransition,
              min: 0,
              max: 1,
              onChanged: (value) =>
                  setState(() => _focusTransitionDraft = value),
              onChangeEnd: widget.host.setFocusTransitionSpeed,
            ),
            if (state.focusMode != 'software')
              Text(
                'Choose a scenario or SAF to run the software focus engine.',
                style: Theme.of(context).textTheme.bodySmall
                    ?.copyWith(color: UiTokens.muted),
              ),
          ],
          if (state.oisSupported || state.eisSupported) ...[
            const Divider(height: 14, color: UiTokens.border),
            Text(
              'STABILISATION',
              style: Theme.of(context).textTheme.labelSmall,
            ),
            const SizedBox(height: 4),
            if (state.oisSupported)
              _stabilizationRow('OIS', state.oisEnabled, widget.host.setOis),
            if (state.eisSupported)
              _stabilizationRow('EIS', state.eisEnabled, widget.host.setEis),
          ],
        ],
      ),
    );
  }

  Widget _focusModeGroup(List<String> modes) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('FOCUS MODE', style: Theme.of(context).textTheme.labelSmall),
        const SizedBox(height: 4),
        Wrap(
          spacing: 4,
          runSpacing: 4,
          children: [
            for (final mode in modes)
              _modeChip(
                label: _focusModeLabel(mode),
                tooltip: _focusModeTooltip(mode),
                selected: state.focusMode == mode,
                onTap: () => widget.host.setFocusMode(mode),
              ),
          ],
        ),
      ],
    );
  }

  String _meteringLabel(String mode) => switch (mode) {
    'multi' => 'Multi',
    'center' => 'Center',
    'spot' => 'Spot',
    'average' => 'Average',
    'highlight' => 'Highlight',
    _ => humanizeWireValue(mode),
  };

  String _meteringTooltip(String mode) => switch (mode) {
    'multi' => 'Pattern metering: centre-weighted with outlier rejection',
    'center' => 'Centre-weighted average',
    'spot' => 'Small central spot',
    'average' => 'Entire screen average',
    'highlight' => 'Protect the brightest 10% from clipping',
    _ => humanizeWireValue(mode),
  };

  String _scenarioLabel(String scenario) => switch (scenario) {
    'interview' => 'Interview',
    'vlogging' => 'Vlogging',
    'wedding' => 'Wedding',
    'cinematic' => 'Cinematic',
    _ => humanizeWireValue(scenario),
  };

  String _scenarioTooltip(String scenario) => switch (scenario) {
    'interview' => 'Sticky focus, slow transitions, long hold',
    'vlogging' => 'Quick to follow a moving subject',
    'wedding' => 'Balanced sensitivity and transition speed',
    'cinematic' => 'Very sticky and deliberately slow racks',
    _ => humanizeWireValue(scenario),
  };

  String _focusModeLabel(String mode) => switch (mode) {
    'off' => 'MF',
    'auto' => 'AF',
    'continuous_video' => 'CAF',
    'continuous_picture' => 'CAF-P',
    'macro' => 'MACRO',
    'edof' => 'EDOF',
    'software' => 'SAF',
    _ => humanizeWireValue(mode).toUpperCase(),
  };

  String _focusModeTooltip(String mode) => switch (mode) {
    'off' => 'Manual focus',
    'auto' => 'Single autofocus',
    'continuous_video' => 'Continuous autofocus for video',
    'continuous_picture' => 'Continuous autofocus for stills',
    'macro' => 'Macro autofocus',
    'edof' => 'Extended depth of field',
    'software' =>
      'Software autofocus with shift sensitivity and transition speed',
    _ => humanizeWireValue(mode),
  };

  Widget _stabilizationRow(
    String label,
    bool enabled,
    ValueChanged<bool> onChanged,
  ) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 2),
      child: Row(
        children: [
          SizedBox(
            width: 34,
            child: Text(label, style: Theme.of(context).textTheme.labelSmall),
          ),
          _modeChip(
            label: 'on',
            tooltip: '$label on',
            selected: enabled,
            onTap: () => onChanged(true),
          ),
          const SizedBox(width: 4),
          _modeChip(
            label: 'off',
            tooltip: '$label off',
            selected: !enabled,
            onTap: () => onChanged(false),
          ),
        ],
      ),
    );
  }

  Widget _processingCard(BuildContext context) {
    return DeckCard(
      title: 'Processing',
      tint: UiTokens.cardProcessing,
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
          if (state.shadingSupported)
            _chipGroup(
              'Vignette correction',
              const ['on', 'off'],
              state.shadingCorrection ? 'on' : 'off',
              (value) => widget.host.setShadingCorrection(value == 'on'),
            )
          else
            Text(
              'VIGNETTE CORRECTION · not offered by this sensor',
              style: Theme.of(context).textTheme.labelSmall
                  ?.copyWith(color: UiTokens.muted),
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
        Text(
          label.toUpperCase(),
          style: Theme.of(context).textTheme.labelSmall,
        ),
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
      tint: UiTokens.cardDevice,
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
                  onPressed:
                      state.deviceReportOptIn && !state.deviceReportRunning
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
      tint: UiTokens.cardClip,
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

  PictureProfileInfo? get _activePictureProfile {
    for (final profile in state.pictureProfiles) {
      if (profile.id == state.profile) return profile;
    }
    return null;
  }

  Future<void> _createPictureProfile() async {
    final customCount = state.pictureProfiles
        .where((profile) => !profile.builtIn)
        .length;
    final name = await _askProfileName(
      'New picture profile',
      'Look ${customCount + 1}',
    );
    if (name != null) await widget.host.createPictureProfile(name);
  }

  Future<void> _renamePictureProfile() async {
    final active = _activePictureProfile;
    if (active == null || active.builtIn) return;
    final name = await _askProfileName('Rename picture profile', active.name);
    if (name != null) await widget.host.renamePictureProfile(active.id, name);
  }

  Future<void> _deletePictureProfile() async {
    final active = _activePictureProfile;
    if (active == null || active.builtIn) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Delete picture profile?'),
        content: Text('“${active.name}” will be removed.'),
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
    if (confirmed == true) await widget.host.deletePictureProfile(active.id);
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
        .where((mode) => mode.enabled && mode.resolution != '720')
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
      '${_sizeLabel(mode)} ${mode.fps}p '
      '${mode.bitDepth == 10 ? '10b' : '8b'} · '
      '${_formatBitrate(mode.bitrate)}';

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
              style: Theme.of(context).textTheme.bodySmall
                  ?.copyWith(color: UiTokens.muted),
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

  double _shutterCeiling(CameraState state) => state.shutterMaxNs.toDouble();

  /// Aligns the slider to the flicker-safe exposure grid Kotlin applies, so the
  /// handle lands on real values instead of a continuous range that would snap
  /// on release.
  ({double min, double max, int? divisions}) _shutterRange(CameraState state) {
    final ceilingNs = _shutterCeiling(state).round();
    // In P the HAL owns exposure; the disabled slider keeps its continuous
    // range and simply shows the readback.
    final autoMode = state.exposureMode == 'AE' || state.exposureMode == 'P';
    final step = autoMode ? null : state.shutterStepNs;
    if (step == null || step <= 0) {
      return (
        min: state.shutterMinNs.toDouble(),
        max: ceilingNs.toDouble(),
        divisions: null,
      );
    }
    final minNs = ((state.shutterMinNs + step - 1) ~/ step) * step;
    final maxNs = (ceilingNs ~/ step) * step;
    if (maxNs <= minNs) {
      return (
        min: state.shutterMinNs.toDouble(),
        max: ceilingNs.toDouble(),
        divisions: null,
      );
    }
    final count = (maxNs - minNs) ~/ step;
    return (
      min: minNs.toDouble(),
      max: maxNs.toDouble(),
      divisions: count < 1 ? null : count,
    );
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
                  maxLength: 24,
                  textInputAction: TextInputAction.done,
                  decoration: InputDecoration(
                    labelText: widget.title,
                    isDense: true,
                    counterText: '',
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

/// Stereo peak/RMS meter with a short peak hold and latching clip indicator.
/// Rebuilt only by the audio [ValueListenable], never by full camera state.
class _AudioMeter extends StatefulWidget {
  const _AudioMeter({required this.level, required this.active});

  final AudioTelemetry? level;
  final bool active;

  @override
  State<_AudioMeter> createState() => _AudioMeterState();
}

class _AudioMeterState extends State<_AudioMeter> {
  static const _floor = -120.0;

  double _holdL = _floor;
  double _holdR = _floor;
  DateTime? _clipUntil;
  DateTime? _silentSince;

  @override
  void didUpdateWidget(covariant _AudioMeter oldWidget) {
    super.didUpdateWidget(oldWidget);
    final level = widget.level;
    if (level == null || !widget.active) {
      _holdL = _floor;
      _holdR = _floor;
      _clipUntil = null;
      _silentSince = null;
      return;
    }
    _holdL = math.max(level.peakDbL, _holdL - 0.6);
    _holdR = math.max(level.peakDbR, _holdR - 0.6);
    if (level.clipped) {
      _clipUntil = DateTime.now().add(const Duration(seconds: 2));
    }
    final silent = level.peakDbL <= -60 && level.peakDbR <= -60;
    if (silent) {
      _silentSince ??= DateTime.now();
    } else {
      _silentSince = null;
    }
  }

  bool get _clipped {
    final until = _clipUntil;
    return until != null && DateTime.now().isBefore(until);
  }

  @override
  Widget build(BuildContext context) {
    final level = widget.level;
    final active = widget.active && level != null;
    final peakL = active ? level.peakDbL : _floor;
    final peakR = active ? level.peakDbR : _floor;
    final rmsL = active ? level.rmsDbL : _floor;
    final rmsR = active ? level.rmsDbR : _floor;
    final clipped = active && _clipped;
    final silent =
        active &&
        _silentSince != null &&
        DateTime.now().difference(_silentSince!) > const Duration(seconds: 2);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
              decoration: BoxDecoration(
                color: clipped ? UiTokens.danger : UiTokens.surfaceRaised,
                borderRadius: BorderRadius.circular(3),
                border: Border.all(
                  color: clipped ? UiTokens.danger : UiTokens.border,
                ),
              ),
              child: Text(
                'CLIP',
                style: TextStyle(
                  fontSize: 8,
                  letterSpacing: 0.8,
                  color: clipped ? Colors.black : UiTokens.muted,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
            const Spacer(),
            Text(
              silent
                  ? 'SILENT INPUT'
                  : active
                  ? 'dBFS'
                  : 'NO SIGNAL',
              style: Theme.of(context).textTheme.labelSmall,
            ),
          ],
        ),
        const SizedBox(height: 4),
        _MeterBar(
          label: 'L',
          peakDb: peakL,
          rmsDb: rmsL,
          holdDb: active ? _holdL : _floor,
        ),
        const SizedBox(height: 3),
        _MeterBar(
          label: 'R',
          peakDb: peakR,
          rmsDb: rmsR,
          holdDb: active ? _holdR : _floor,
        ),
        const SizedBox(height: 2),
        const Padding(
          padding: EdgeInsets.only(left: 16),
          child: Row(
            children: [
              Text('-60', style: TextStyle(fontSize: 8, color: UiTokens.muted)),
              Spacer(),
              Text('-40', style: TextStyle(fontSize: 8, color: UiTokens.muted)),
              Spacer(),
              Text('-20', style: TextStyle(fontSize: 8, color: UiTokens.muted)),
              Spacer(),
              Text('-12', style: TextStyle(fontSize: 8, color: UiTokens.muted)),
              Spacer(),
              Text('-6', style: TextStyle(fontSize: 8, color: UiTokens.muted)),
              Spacer(),
              Text('0', style: TextStyle(fontSize: 8, color: UiTokens.muted)),
            ],
          ),
        ),
        if (silent)
          const Padding(
            padding: EdgeInsets.only(top: 4),
            child: Text(
              'No signal on this input. Choose another microphone or check the connection.',
              style: TextStyle(color: UiTokens.accent, fontSize: 10),
            ),
          ),
      ],
    );
  }
}

class _MeterBar extends StatelessWidget {
  const _MeterBar({
    required this.label,
    required this.peakDb,
    required this.rmsDb,
    required this.holdDb,
  });

  final String label;
  final double peakDb;
  final double rmsDb;
  final double holdDb;

  static double _fraction(double db) =>
      ((db.clamp(-60.0, 0.0) + 60.0) / 60.0).clamp(0.0, 1.0);

  @override
  Widget build(BuildContext context) {
    final peak = _fraction(peakDb);
    final rms = _fraction(rmsDb);
    final hold = _fraction(holdDb);
    return Row(
      children: [
        SizedBox(
          width: 14,
          child: Text(
            label,
            style: const TextStyle(
              fontSize: 9,
              color: UiTokens.muted,
              fontWeight: FontWeight.w700,
            ),
          ),
        ),
        Expanded(
          child: SizedBox(
            height: 11,
            child: ClipRRect(
              borderRadius: BorderRadius.circular(2),
              child: LayoutBuilder(
                builder: (context, constraints) {
                  final width = constraints.maxWidth;
                  return Stack(
                    fit: StackFit.expand,
                    children: [
                      Container(
                        decoration: const BoxDecoration(
                          gradient: LinearGradient(
                            colors: [
                              Color(0xFF4CC38A),
                              Color(0xFF4CC38A),
                              Color(0xFFE8B44A),
                              Color(0xFFE5484D),
                            ],
                            stops: [0.0, 0.62, 0.84, 1.0],
                          ),
                        ),
                      ),
                      Positioned(
                        left: width * peak,
                        right: 0,
                        top: 0,
                        bottom: 0,
                        child: const ColoredBox(color: UiTokens.surface),
                      ),
                      if (peak > 0)
                        Positioned(
                          left: (width * rms - 1).clamp(0.0, width - 2),
                          top: 0,
                          bottom: 0,
                          child: const SizedBox(
                            width: 2,
                            child: ColoredBox(color: Colors.white70),
                          ),
                        ),
                      if (hold > 0)
                        Positioned(
                          left: (width * hold - 1).clamp(0.0, width - 2),
                          top: 0,
                          bottom: 0,
                          child: const SizedBox(
                            width: 2,
                            child: ColoredBox(color: Colors.white),
                          ),
                        ),
                    ],
                  );
                },
              ),
            ),
          ),
        ),
        const SizedBox(width: 4),
        SizedBox(
          width: 30,
          child: Text(
            peakDb <= -60 ? '—' : peakDb.toStringAsFixed(0),
            textAlign: TextAlign.right,
            style: const TextStyle(
              fontSize: 9,
              color: UiTokens.muted,
              fontFeatures: [FontFeature.tabularFigures()],
            ),
          ),
        ),
      ],
    );
  }
}
