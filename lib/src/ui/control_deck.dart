import 'package:flutter/material.dart';

import '../pigeon/slatecam.g.dart';
import 'ui_tokens.dart';
import 'widgets.dart';

enum DeckTab { exposure, focus, colour, monitor, capture, device }

const _deckTabs = [
  (DeckTab.exposure, Icons.tune, 'Exposure'),
  (DeckTab.focus, Icons.center_focus_strong, 'Focus'),
  (DeckTab.colour, Icons.palette_outlined, 'Colour'),
  (DeckTab.monitor, Icons.visibility_outlined, 'Monitor'),
  (DeckTab.capture, Icons.videocam_outlined, 'Capture'),
  (DeckTab.device, Icons.memory, 'Device'),
];

const _angleOptions = [45.0, 90.0, 180.0, 270.0, 360.0];

class ControlDeck extends StatefulWidget {
  const ControlDeck({
    super.key,
    required this.state,
    required this.host,
    required this.tab,
    required this.onTabChanged,
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
  final DeckTab tab;
  final ValueChanged<DeckTab> onTabChanged;
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
    if (oldWidget.tab != widget.tab) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (_scrollController.hasClients) _scrollController.jumpTo(0);
      });
    }
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
          _header(context),
          _tabBar(context),
          const Divider(height: 1, color: UiTokens.border),
          Expanded(
            child: SingleChildScrollView(
              controller: _scrollController,
              padding: const EdgeInsets.fromLTRB(12, 12, 12, 20),
              child: AnimatedSwitcher(
                duration: const Duration(milliseconds: 160),
                child: KeyedSubtree(
                  key: ValueKey(widget.tab),
                  child: _tabContent(context),
                ),
              ),
            ),
          ),
          _footer(context),
        ],
      ),
    );
  }

  Widget _header(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(14, 10, 8, 6),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'CONTROL DECK',
                  style: Theme.of(context).textTheme.labelSmall,
                ),
                const SizedBox(height: 2),
                Row(
                  children: [
                    StatusPill(
                      color: UiTokens.accent,
                      child: Text(
                        state.exposureMode,
                        style: const TextStyle(
                          color: UiTokens.accent,
                          fontSize: 10,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                    ),
                    const SizedBox(width: 6),
                    Text(
                      '${state.previewWidth}×${state.previewHeight} · ${state.frameRate} fps',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ],
                ),
              ],
            ),
          ),
          IconButton(
            tooltip: 'Close controls',
            onPressed: widget.onClose,
            icon: const Icon(Icons.chevron_right),
          ),
        ],
      ),
    );
  }

  Widget _tabBar(BuildContext context) {
    return SizedBox(
      height: 58,
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 8),
        child: Row(
          children: [
            for (final (tab, icon, label) in _deckTabs)
              DeckTabButton(
                icon: icon,
                label: label,
                selected: widget.tab == tab,
                onTap: () => widget.onTabChanged(tab),
              ),
          ],
        ),
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

  Widget _tabContent(BuildContext context) {
    switch (widget.tab) {
      case DeckTab.exposure:
        return _exposure(context);
      case DeckTab.focus:
        return _focus(context);
      case DeckTab.colour:
        return _colour(context);
      case DeckTab.monitor:
        return _monitor(context);
      case DeckTab.capture:
        return _capture(context);
      case DeckTab.device:
        return _device(context);
    }
  }

  Widget _exposure(BuildContext context) {
    final manualIso = state.exposureMode == 'M';
    final manualShutter = state.exposureMode != 'P';
    final isoValueLabel = state.exposureMode == 'S'
        ? 'auto · ${state.iso}'
        : '${state.iso}';
    final shutterValueLabel = state.exposureMode == 'P'
        ? 'auto · ${formatShutterSummary(state.shutterNs)}'
        : formatShutterSummary(state.shutterNs);

    return Column(
      children: [
        DeckCard(
          title: 'Exposure mode',
          child: SegmentedButton<String>(
            showSelectedIcon: false,
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
        ),
        DeckCard(
          title: 'Sensor',
          child: Column(
            children: [
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
              Row(
                children: [
                  const Expanded(child: Text('Shutter angle')),
                  Expanded(
                    child: DropdownButtonHideUnderline(
                      child: DropdownButton<double>(
                        isExpanded: true,
                        alignment: Alignment.centerRight,
                        value:
                            _angleOptions.contains(
                              state.shutterAngle.roundToDouble(),
                            )
                            ? state.shutterAngle.roundToDouble()
                            : null,
                        hint: Text('${state.shutterAngle.toStringAsFixed(0)}°'),
                        items: [
                          for (final angle in _angleOptions)
                            DropdownMenuItem(
                              value: angle,
                              child: Text(
                                '${angle.toInt()}°',
                                textAlign: TextAlign.right,
                              ),
                            ),
                        ],
                        onChanged: manualShutter
                            ? (value) {
                                if (value == null) return;
                                widget.host.setShutterAngle(value);
                              }
                            : null,
                      ),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
        DeckCard(
          title: 'Automatic',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              OutlinedButton.icon(
                onPressed: () => widget.host.setAutoExposure(),
                icon: Icon(
                  state.autoExposure
                      ? Icons.check_circle_outline
                      : Icons.exposure,
                ),
                label: Text(
                  state.autoExposure ? 'AE active' : 'Return to auto exposure',
                ),
              ),
              const SizedBox(height: 8),
              Text(
                'P uses the HAL AE/AF loop. S fixes the shutter and runs the '
                'histogram auto-ISO loop. M gives both sliders to you.',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _focus(BuildContext context) {
    final focusEnabled = state.focusMax > 0;
    return Column(
      children: [
        DeckCard(
          title: 'Autofocus',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              OutlinedButton.icon(
                onPressed: state.autoFocusSupported
                    ? () => widget.host.setAutoFocus()
                    : null,
                icon: Icon(
                  state.autoFocus
                      ? Icons.center_focus_strong
                      : Icons.lock_outline,
                ),
                label: Text(
                  !state.autoFocusSupported
                      ? 'AF not supported on this lens'
                      : state.autoFocus
                      ? 'AF active'
                      : 'Return to AF',
                ),
              ),
              const SizedBox(height: 8),
              Text(
                !state.autoFocusSupported
                    ? 'This camera has no autofocus; focus stays manual.'
                    : state.autoFocus
                    ? 'The HAL is focusing. Moving the manual slider locks the lens.'
                    : 'The lens is under manual control. AF hands over at the focused distance.',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
          ),
        ),
        DeckCard(
          title: 'Lens',
          child: Column(
            children: [
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
                label: 'Focus shift speed',
                valueLabel: '${_shift.toStringAsFixed(1)} D/s',
                value: _shift,
                min: 0.1,
                max: 20,
                onChanged: (value) => setState(() => _shiftDraft = value),
                onChangeEnd: (value) => widget.host.setFocusShiftSpeed(value),
              ),
              Text(
                'Slow values produce a deliberate rack; fast values snap focus.',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _colour(BuildContext context) {
    final calibration = state.deviceCalibrationActive
        ? (state.deviceCalibrationSource ?? 'measured matrix')
        : 'identity · no measured data';
    return Column(
      children: [
        DeckCard(
          title: 'Look',
          child: Column(
            children: [
              _dropdown<String>(
                label: 'Profile',
                value: state.profileNames.contains(state.profile)
                    ? state.profile
                    : null,
                items: state.profileNames,
                labelOf: humanizeWireValue,
                onChanged: (value) {
                  if (value == null) return;
                  widget.host.setProfile(value);
                },
              ),
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton.icon(
                  onPressed: () => widget.host.reloadLuts(),
                  icon: const Icon(Icons.refresh, size: 16),
                  label: const Text('Reload LUTs'),
                ),
              ),
            ],
          ),
        ),
        DeckCard(
          title: 'White balance',
          child: _dropdown<String>(
            label: 'Mode',
            value: state.whiteBalanceModes.contains(state.whiteBalanceMode)
                ? state.whiteBalanceMode
                : null,
            items: state.whiteBalanceModes,
            labelOf: humanizeWireValue,
            onChanged: (value) {
              if (value == null) return;
              widget.host.setWhiteBalanceMode(value);
            },
          ),
        ),
        DeckCard(
          title: 'Input calibration',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(calibration, style: Theme.of(context).textTheme.bodyMedium),
              const SizedBox(height: 4),
              Text(
                'The matrix and trims run before the creative profile. '
                'Calibration is data in the device database.',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _monitor(BuildContext context) {
    return Column(
      children: [
        DeckCard(
          title: 'Preview overlays',
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
            ],
          ),
        ),
        if (state.peaking)
          DeckCard(
            title: 'Peaking',
            child: Column(
              children: [
                LabeledSlider(
                  label: 'Sensitivity',
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
                  label: 'Strength',
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
            ),
          ),
        Text(
          'Monitoring marks are preview-only. The encoder receives the clean '
          'profile and LUT without zebra, false colour, guides or peaking.',
          style: Theme.of(context).textTheme.bodySmall,
        ),
      ],
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

  Widget _capture(BuildContext context) {
    final lastClip = state.clipPath;
    final shootLenses = state.shootLenses;
    return Column(
      children: [
        DeckCard(
          title: 'Camera',
          child: _dropdown<String>(
            label: 'Active camera',
            value: state.cameraIds.contains(state.cameraId)
                ? state.cameraId
                : null,
            items: state.cameraIds,
            labelOf: _cameraLabel,
            onChanged: (value) {
              if (value == null) return;
              widget.host.setCameraId(value);
            },
          ),
        ),
        DeckCard(
          title: 'Output',
          child: Column(
            children: [
              _dropdown<String>(
                label: 'Shoot mode',
                value: state.shootMode,
                items: const ['single', 'movie'],
                labelOf: (mode) => mode == 'movie'
                    ? 'Movie / Vlog (common mode)'
                    : 'Single camera',
                onChanged: (mode) {
                  if (mode == null) return;
                  widget.host.setShootMode(mode);
                },
              ),
              _dropdown<String>(
                label: 'Recording mode',
                value:
                    state.recordingModes.any(
                      (mode) => mode.id == state.recordingMode,
                    )
                    ? state.recordingMode
                    : null,
                items: state.recordingModes.map((mode) => mode.id).toList(),
                labelOf: (id) {
                  final mode = state.recordingModes.firstWhere(
                    (candidate) => candidate.id == id,
                  );
                  return mode.enabled
                      ? mode.label
                      : '${mode.label} · ${mode.reason}';
                },
                onChanged: (id) {
                  if (id == null) return;
                  widget.host.selectRecordingMode(id);
                },
              ),
              _infoRow(
                'Preview',
                '${state.previewWidth}×${state.previewHeight}',
              ),
              _infoRow('Frame rate', '${state.frameRate} fps'),
              _infoRow(
                'Codec',
                state.tenBit ? 'HEVC · 10-bit HLG' : 'HEVC · 8-bit SDR',
              ),
              _infoRow(
                'HLG / 10-bit',
                state.hlgAvailable
                    ? (state.tenBit ? 'active' : 'verified')
                    : state.hlgReason,
              ),
              _infoRow('Gyro sidecar', '${state.gyroSampleCount} samples'),
            ],
          ),
        ),
        DeckCard(
          title: 'Shoot set',
          child: shootLenses.isEmpty
              ? const SizedBox.shrink()
              : Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Wrap(
                      spacing: 8,
                      runSpacing: 6,
                      children: [
                        for (final lens in shootLenses)
                          FilterChip(
                            label: Text(lens.label),
                            selected: lens.selected,
                            onSelected: state.isRecording
                                ? null
                                : (value) =>
                                      widget.host.setShootLens(lens.id, value),
                            selectedColor: UiTokens.accent.withValues(
                              alpha: 0.25,
                            ),
                            checkmarkColor: UiTokens.accent,
                          ),
                      ],
                    ),
                    const SizedBox(height: 6),
                    Text(
                      state.isRecording
                          ? 'Stop recording to change the shoot set.'
                          : 'The common recording mode is the intersection of the '
                                'selected lenses. Macro lenses are never part of the '
                                'shoot set.',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                    const SizedBox(height: 8),
                    Row(
                      children: [
                        Expanded(
                          child: DropdownButtonHideUnderline(
                            child: DropdownButton<String>(
                              isExpanded: true,
                              value:
                                  state.shootProfiles.any(
                                    (profile) =>
                                        profile.id == state.activeShootProfile,
                                  )
                                  ? state.activeShootProfile
                                  : null,
                              items: [
                                for (final profile in state.shootProfiles)
                                  DropdownMenuItem(
                                    value: profile.id,
                                    child: Text(
                                      '${profile.name} · ${profile.lensCount}',
                                      overflow: TextOverflow.ellipsis,
                                    ),
                                  ),
                              ],
                              onChanged: state.isRecording
                                  ? null
                                  : (id) {
                                      if (id != null) {
                                        widget.host.applyShootProfile(id);
                                      }
                                    },
                            ),
                          ),
                        ),
                        IconButton(
                          tooltip: 'Save as profile',
                          onPressed: state.isRecording
                              ? null
                              : _saveShootProfile,
                          icon: const Icon(Icons.save_outlined),
                        ),
                        IconButton(
                          tooltip: 'Rename profile',
                          onPressed: state.isRecording
                              ? null
                              : _renameShootProfile,
                          icon: const Icon(Icons.drive_file_rename_outline),
                        ),
                        IconButton(
                          tooltip: 'Delete profile',
                          onPressed:
                              state.shootProfiles.length > 1 &&
                                  !state.isRecording
                              ? _deleteShootProfile
                              : null,
                          icon: const Icon(Icons.delete_outline),
                        ),
                      ],
                    ),
                  ],
                ),
        ),
        DeckCard(
          title: 'Processing',
          child: Column(
            children: [
              _dropdown<String>(
                label: 'Sharpening',
                value: state.edgeModes.contains(state.edgeMode)
                    ? state.edgeMode
                    : null,
                items: state.edgeModes,
                onChanged: (value) {
                  if (value == null) return;
                  widget.host.setEdgeMode(value);
                },
              ),
              _dropdown<String>(
                label: 'Noise reduction',
                value: state.noiseReductionModes.contains(state.noiseReduction)
                    ? state.noiseReduction
                    : null,
                items: state.noiseReductionModes,
                onChanged: (value) {
                  if (value == null) return;
                  widget.host.setNoiseReduction(value);
                },
              ),
              _dropdown<String>(
                label: 'Anti-banding',
                value: state.antibandingModes.contains(state.antibanding)
                    ? state.antibanding
                    : null,
                items: state.antibandingModes,
                onChanged: (value) {
                  if (value == null) return;
                  widget.host.setAntibanding(value);
                },
              ),
            ],
          ),
        ),
        DeckCard(
          title: 'Last clip',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (lastClip == null)
                Text(
                  'No clip in this session.',
                  style: Theme.of(context).textTheme.bodySmall,
                )
              else
                SelectableText(
                  lastClip,
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
        ),
      ],
    );
  }

  Widget _device(BuildContext context) {
    return Column(
      children: [
        DeckCard(
          title: 'Device',
          child: Column(
            children: [
              _infoRow('Device key', state.deviceKey),
              _infoRow('Camera', state.cameraId),
              _infoRow('Database', 'v${state.deviceDatabaseVersion}'),
              _infoRow(
                'Calibration',
                state.deviceCalibrationActive
                    ? (state.deviceCalibrationSource ?? 'active')
                    : 'identity · no measured data',
              ),
              const SizedBox(height: 8),
              Align(
                alignment: Alignment.centerLeft,
                child: Text(
                  'APPLIED QUIRKS',
                  style: Theme.of(context).textTheme.labelSmall,
                ),
              ),
              const SizedBox(height: 6),
              if (state.deviceQuirks.isEmpty)
                Text('None', style: Theme.of(context).textTheme.bodySmall)
              else
                Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: [
                    for (final quirk in state.deviceQuirks)
                      StatusPill(
                        color: UiTokens.info,
                        child: Text(
                          quirk,
                          style: const TextStyle(
                            color: UiTokens.info,
                            fontSize: 9,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                      ),
                  ],
                ),
            ],
          ),
        ),
        DeckCard(
          title: 'Device reports',
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('Opt-in device reports'),
                subtitle: const Text(
                  'Stores a summary probe JSON on this device. It is never uploaded automatically.',
                ),
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
                          : const Icon(Icons.analytics_outlined, size: 18),
                      label: Text(
                        state.deviceReportRunning
                            ? 'Probing…'
                            : 'Run device report',
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
              if (state.deviceReportPath != null) ...[
                const SizedBox(height: 8),
                SelectableText(
                  state.deviceReportPath!,
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ],
              if (state.deviceReportError != null) ...[
                const SizedBox(height: 8),
                Text(
                  state.deviceReportError!,
                  style: const TextStyle(color: UiTokens.danger),
                ),
              ],
              const SizedBox(height: 8),
              Text(
                'Reports are local. Opt-in is off by default and the debug probe '
                'launch bypasses only the preference, not the privacy rules.',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
          ),
        ),
        OutlinedButton.icon(
          onPressed: widget.onCloseCamera,
          icon: const Icon(Icons.videocam_off_outlined, size: 18),
          label: const Text('Close camera'),
        ),
      ],
    );
  }

  String _cameraLabel(String id) {
    final index = state.cameraIds.indexOf(id);
    if (index >= 0 && index < state.cameraLabels.length) {
      return state.cameraLabels[index];
    }
    return 'camera $id';
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
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            flex: 2,
            child: Text(label, style: Theme.of(context).textTheme.bodySmall),
          ),
          const SizedBox(width: 8),
          Expanded(
            flex: 3,
            child: Text(
              value,
              textAlign: TextAlign.right,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: Theme.of(context).textTheme.bodyMedium
                  ?.copyWith(fontSize: 12),
            ),
          ),
        ],
      ),
    );
  }

  Widget _dropdown<T>({
    required String label,
    required T? value,
    required List<T> items,
    required ValueChanged<T?> onChanged,
    String Function(T)? labelOf,
  }) {
    return Row(
      children: [
        Expanded(
          flex: 2,
          child: Text(label, style: Theme.of(context).textTheme.bodyMedium),
        ),
        const SizedBox(width: 8),
        Expanded(
          flex: 3,
          child: DropdownButtonHideUnderline(
            child: DropdownButton<T>(
              isExpanded: true,
              alignment: Alignment.centerRight,
              value: value,
              items: [
                for (final item in items)
                  DropdownMenuItem(
                    value: item,
                    child: Text(
                      labelOf?.call(item) ?? '$item',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      textAlign: TextAlign.right,
                    ),
                  ),
              ],
              onChanged: onChanged,
            ),
          ),
        ),
      ],
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
