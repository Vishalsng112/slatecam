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
  double? _focusDraft;
  double? _shiftDraft;
  double? _peakingSensitivityDraft;
  double? _peakingStrengthDraft;

  CameraState get state => widget.state;

  @override
  void didUpdateWidget(covariant ControlDeck oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.state.iso != state.iso) _isoDraft = null;
    if (oldWidget.state.shutterNs != state.shutterNs) _shutterDraft = null;
    if (oldWidget.state.focusDiopters != state.focusDiopters) {
      _focusDraft = null;
    }
    if (oldWidget.state.focusShiftSpeed != state.focusShiftSpeed) {
      _shiftDraft = null;
    }
    if (oldWidget.state.peakingSensitivity != state.peakingSensitivity) {
      _peakingSensitivityDraft = null;
    }
    if (oldWidget.state.peakingStrength != state.peakingStrength) {
      _peakingStrengthDraft = null;
    }
  }

  double get _iso => _isoDraft ?? state.iso.toDouble();
  double get _shutter => _shutterDraft ?? state.shutterNs.toDouble();
  double get _focusValue => _focusDraft ?? state.focusDiopters;
  double get _shift => _shiftDraft ?? state.focusShiftSpeed;
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
                  final left = <Widget>[
                    _lensCard(context),
                    _modeCard(context),
                    _shootSetCard(context),
                    _exposureCard(context),
                    _processingCard(context),
                  ];
                  final right = <Widget>[
                    _focusCard(context),
                    _colourCard(context),
                    _monitorCard(context),
                    _deviceCard(context),
                    _clipCard(context),
                  ];
                  if (constraints.maxWidth < 520) {
                    return Column(children: [...left, ...right]);
                  }
                  final columnWidth = (constraints.maxWidth - 12) / 2;
                  return Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      SizedBox(
                        width: columnWidth,
                        child: Column(children: left),
                      ),
                      const SizedBox(width: 12),
                      SizedBox(
                        width: columnWidth,
                        child: Column(children: right),
                      ),
                    ],
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
      padding: const EdgeInsets.fromLTRB(12, 10, 6, 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'CONSOLE',
                      style: Theme.of(context).textTheme.labelSmall,
                    ),
                    const SizedBox(height: 2),
                    Text(
                      '${state.shootMode == 'movie' ? 'Movie' : 'Single'} · '
                      '${state.previewWidth}×${state.previewHeight} · '
                      '${state.frameRate} fps · '
                      '${state.tenBit ? '10-bit HLG' : '8-bit SDR'}',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ],
                ),
              ),
              IconButton(
                tooltip: 'Hide controls',
                onPressed: widget.onClose,
                icon: const Icon(Icons.chevron_right),
              ),
            ],
          ),
          const SizedBox(height: 6),
          Semantics(
            button: true,
            label: state.isRecording ? 'stop recording' : 'start recording',
            child: SizedBox(
              height: 50,
              child: FilledButton.icon(
                style: FilledButton.styleFrom(
                  backgroundColor: state.isRecording
                      ? UiTokens.danger
                      : UiTokens.accent,
                  foregroundColor: UiTokens.background,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
                onPressed: state.isRecording
                    ? () => widget.host.stopRecording()
                    : () => widget.host.startRecording(),
                icon: Icon(
                  state.isRecording
                      ? Icons.stop_circle_outlined
                      : Icons.fiber_manual_record,
                  size: 20,
                ),
                label: Text(
                  state.isRecording ? 'STOP RECORDING' : 'RECORD',
                  style: const TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w900,
                    letterSpacing: 0.6,
                  ),
                ),
              ),
            ),
          ),
          const SizedBox(height: 8),
          _quickBar(context),
        ],
      ),
    );
  }

  Widget _quickBar(BuildContext context) {
    final modes = _enabledModes;
    final modeIndex = modes.indexWhere((mode) => mode.id == state.recordingMode);
    final mode = modeIndex >= 0 ? modes[modeIndex] : null;
    final cameraIds = state.cameraIds;
    final cameraIndex = cameraIds.indexOf(state.cameraId);

    return Column(
      children: [
        _stepper(
          label: 'Camera',
          value: cameraIndex >= 0
              ? _cameraLabel(state.cameraId)
              : 'camera ${state.cameraId}',
          semanticsPrefix: 'camera',
          onPrevious: () => _cycleCamera(-1),
          onNext: () => _cycleCamera(1),
        ),
        const SizedBox(height: 6),
        _stepper(
          label: 'Mode',
          value: mode != null ? _modeShort(mode) : state.recordingMode,
          semanticsPrefix: 'recording mode',
          onPrevious: () => _cycleMode(-1),
          onNext: () => _cycleMode(1),
        ),
        const SizedBox(height: 6),
        Row(
          children: [
            Expanded(
              child: SegmentedButton<String>(
                showSelectedIcon: false,
                style: const ButtonStyle(
                  visualDensity: VisualDensity.compact,
                ),
                segments: const [
                  ButtonSegment(value: 'single', label: Text('Single')),
                  ButtonSegment(value: 'movie', label: Text('Movie / Vlog')),
                ],
                selected: {state.shootMode},
                onSelectionChanged: (selection) {
                  if (selection.isEmpty) return;
                  widget.host.setShootMode(selection.first);
                },
              ),
            ),
          ],
        ),
      ],
    );
  }

  Widget _stepper({
    required String label,
    required String value,
    required String semanticsPrefix,
    required VoidCallback onPrevious,
    required VoidCallback onNext,
  }) {
    return Container(
      height: 40,
      decoration: BoxDecoration(
        color: UiTokens.surfaceRaised,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: UiTokens.border),
      ),
      child: Row(
        children: [
          IconButton(
            tooltip: 'Previous $label',
            visualDensity: VisualDensity.compact,
            onPressed: onPrevious,
            icon: const Icon(Icons.chevron_left, size: 20),
          ),
          Expanded(
            child: Semantics(
              label: '$semanticsPrefix: $value',
              child: Text(
                '$label · $value',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700),
              ),
            ),
          ),
          IconButton(
            tooltip: 'Next $label',
            visualDensity: VisualDensity.compact,
            onPressed: onNext,
            icon: const Icon(Icons.chevron_right, size: 20),
          ),
        ],
      ),
    );
  }

  Widget _lensCard(BuildContext context) {
    return DeckCard(
      title: 'Lens',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              for (final id in state.cameraIds)
                ChoiceChip(
                  label: Text(_cameraLabel(id)),
                  selected: id == state.cameraId,
                  onSelected: (_) => widget.host.setCameraId(id),
                  selectedColor: UiTokens.accent.withValues(alpha: 0.25),
                ),
            ],
          ),
          const SizedBox(height: 6),
          Text(
            state.isRecording && state.shootMode != 'movie'
                ? 'Stop recording or switch to Movie to change lenses.'
                : 'Tap a lens to switch. Movie mode requires a common mode '
                      'across the shoot set.',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(fontSize: 10),
          ),
        ],
      ),
    );
  }

  Widget _modeCard(BuildContext context) {
    final modes = state.recordingModes;
    return DeckCard(
      title: 'Recording mode',
      child: Wrap(
        spacing: 6,
        runSpacing: 6,
        children: [
          for (final mode in modes)
            Tooltip(
              message: mode.enabled ? mode.label : '${mode.label} · ${mode.reason}',
              child: ChoiceChip(
                label: Text(
                  _modeLabel(mode),
                  style: const TextStyle(fontSize: 11),
                ),
                selected: mode.id == state.recordingMode,
                onSelected: mode.enabled
                    ? (_) => widget.host.selectRecordingMode(mode.id)
                    : null,
                selectedColor: UiTokens.accent.withValues(alpha: 0.25),
              ),
            ),
        ],
      ),
    );
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
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: [
                for (final lens in lenses)
                  FilterChip(
                    label: Text(lens.label),
                    selected: lens.selected,
                    onSelected: editable
                        ? (value) =>
                              widget.host.setShootLens(lens.id, value)
                        : null,
                    selectedColor: UiTokens.accent.withValues(alpha: 0.25),
                    checkmarkColor: UiTokens.accent,
                  ),
              ],
            ),
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
                  label: Text('${profile.name} · ${profile.lensCount}'),
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
              Text(
                'Active profile: ${active.name}',
                style: Theme.of(context).textTheme.bodySmall,
              ),
              const Spacer(),
              IconButton(
                tooltip: 'Save current set as profile',
                onPressed: editable ? _saveShootProfile : null,
                icon: const Icon(Icons.save_outlined, size: 18),
              ),
              IconButton(
                tooltip: 'Rename active profile',
                onPressed: editable && active.id.isNotEmpty
                    ? _renameShootProfile
                    : null,
                icon: const Icon(
                  Icons.drive_file_rename_outline,
                  size: 18,
                ),
              ),
              IconButton(
                tooltip: 'Delete active profile',
                onPressed: editable && profiles.length > 1 && active.id.isNotEmpty
                    ? _deleteShootProfile
                    : null,
                icon: const Icon(Icons.delete_outline, size: 18),
              ),
            ],
          ),
        ],
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
          SegmentedButton<String>(
            showSelectedIcon: false,
            style: const ButtonStyle(visualDensity: VisualDensity.compact),
            segments: [
              for (final mode in state.exposureModes)
                ButtonSegment(value: mode, label: Text(mode)),
            ],
            selected: {state.exposureMode},
            onSelectionChanged: (selection) {
              if (selection.isEmpty) return;
              widget.host.setExposureMode(selection.first);
            },
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
                  label: Text('${angle.toInt()}°'),
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
          OutlinedButton.icon(
            onPressed: () => widget.host.setAutoExposure(),
            icon: Icon(
              state.autoExposure ? Icons.check_circle_outline : Icons.exposure,
              size: 18,
            ),
            label: Text(
              state.autoExposure ? 'AE active' : 'Return to auto exposure',
            ),
          ),
        ],
      ),
    );
  }

  Widget _focusCard(BuildContext context) {
    final focusEnabled = state.focusMax > 0;
    return DeckCard(
      title: 'Focus',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          OutlinedButton.icon(
            onPressed: state.autoFocusSupported
                ? () => widget.host.setAutoFocus()
                : null,
            icon: Icon(
              state.autoFocus ? Icons.center_focus_strong : Icons.lock_outline,
              size: 18,
            ),
            label: Text(
              !state.autoFocusSupported
                  ? 'AF not supported'
                  : state.autoFocus
                  ? 'AF active'
                  : 'Return to AF',
            ),
          ),
          const SizedBox(height: 4),
          LabeledSlider(
            label: 'Focus',
            valueLabel: focusEnabled
                ? formatDiopters(_focusValue)
                : 'fixed focus',
            value: focusEnabled ? _focusValue : 0,
            min: 0,
            max: focusEnabled ? state.focusMax : 1,
            enabled: focusEnabled,
            onChanged: (value) => setState(() => _focusDraft = value),
            onChangeEnd: focusEnabled
                ? (value) => widget.host.setFocusDiopters(value)
                : null,
          ),
          LabeledSlider(
            label: 'Rack speed',
            valueLabel: '${_shift.toStringAsFixed(1)} D/s',
            value: _shift,
            min: 0.1,
            max: 20,
            onChanged: (value) => setState(() => _shiftDraft = value),
            onChangeEnd: (value) => widget.host.setFocusShiftSpeed(value),
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
                  label: Text(
                    humanizeWireValue(profile),
                    style: const TextStyle(fontSize: 11),
                  ),
                  selected: profile == state.profile,
                  onSelected: (_) => widget.host.setProfile(profile),
                ),
            ],
          ),
          TextButton.icon(
            onPressed: () => widget.host.reloadLuts(),
            icon: const Icon(Icons.refresh, size: 16),
            label: const Text('Reload LUTs'),
          ),
          const Divider(height: 12, color: UiTokens.border),
          Text('WHITE BALANCE', style: Theme.of(context).textTheme.labelSmall),
          const SizedBox(height: 4),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              for (final mode in state.whiteBalanceModes)
                ChoiceChip(
                  label: Text(
                    humanizeWireValue(mode),
                    style: const TextStyle(fontSize: 11),
                  ),
                  selected: mode == state.whiteBalanceMode,
                  onSelected: (_) => widget.host.setWhiteBalanceMode(mode),
                ),
            ],
          ),
          const Divider(height: 12, color: UiTokens.border),
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
          _switchRow(
            'Framing guides',
            'Thirds, centre mark and frame border',
            widget.showGuides,
            widget.onGuidesChanged,
          ),
          _switchRow(
            'Zebra',
            'Highlight warning at 70%',
            state.zebra,
            (value) => widget.host.setZebra(value),
          ),
          _switchRow(
            'False colour',
            'Exposure bands for shadows and highlights',
            state.falseColor,
            (value) => widget.host.setFalseColor(value),
          ),
          _switchRow(
            'Focus peaking',
            'Edge highlight on the preview only',
            state.peaking,
            (value) => widget.host.setPeaking(
              value,
              _peakingSensitivity,
              _peakingStrength,
            ),
          ),
          _switchRow(
            'Histogram',
            '64-bin luma histogram card',
            widget.showHistogram,
            widget.onHistogramChanged,
          ),
          _switchRow(
            'Waveform',
            '64 × 16 luma waveform card',
            widget.showWaveform,
            widget.onWaveformChanged,
          ),
          if (state.peaking) ...[
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
          spacing: 6,
          runSpacing: 6,
          children: [
            for (final value in values)
              ChoiceChip(
                label: Text(
                  humanizeWireValue(value),
                  style: const TextStyle(fontSize: 11),
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
    final enabled = state.recordingModes.where((mode) => mode.enabled).toList();
    return enabled.isEmpty ? state.recordingModes : enabled;
  }

  void _cycleCamera(int delta) {
    final ids = state.cameraIds;
    if (ids.length < 2) return;
    final index = ids.indexOf(state.cameraId);
    final next = ids[(index < 0 ? 0 : index + delta) % ids.length];
    widget.host.setCameraId(next);
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

  String _modeLabel(RecordingMode mode) {
    final size = _sizeLabel(mode);
    final bit = mode.tenBit ? '10-bit' : '8-bit';
    return '$size ${mode.fps}p · $bit';
  }

  String _modeShort(RecordingMode mode) =>
      '${_sizeLabel(mode)} ${mode.fps}p ${mode.tenBit ? '10b' : '8b'}';

  String _sizeLabel(RecordingMode mode) {
    final width = mode.width.toInt();
    final height = mode.height.toInt();
    return switch ((width, height)) {
      (3840, 2160) => '4K',
      (1920, 1080) => '1080p',
      (1440, 1080) => '4:3',
      (1280, 720) => '720p',
      _ => '$width×$height',
    };
  }

  Widget _switchRow(
    String title,
    String subtitle,
    bool value,
    ValueChanged<bool> onChanged,
  ) {
    return SwitchListTile(
      contentPadding: EdgeInsets.zero,
      dense: true,
      title: Text(title, style: Theme.of(context).textTheme.bodyMedium),
      subtitle: Text(
        subtitle,
        style: Theme.of(context).textTheme.bodySmall?.copyWith(fontSize: 10),
      ),
      value: value,
      onChanged: onChanged,
    );
  }

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
