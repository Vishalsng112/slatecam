import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:video_player/video_player.dart';

import '../pigeon/slatecam.g.dart';
import 'ui_tokens.dart';

/// Clip review and media management: library, playback, delete and share.
class PlaybackScreen extends StatefulWidget {
  const PlaybackScreen({super.key, required this.host});

  final SlateCamHostApi host;

  @override
  State<PlaybackScreen> createState() => _PlaybackScreenState();
}

class _PlaybackScreenState extends State<PlaybackScreen> {
  List<MediaClip>? _clips;
  MediaStorageInfo? _storage;
  String? _error;
  bool _loading = true;

  final Map<String, Future<String?>> _thumbnails = {};

  VideoPlayerController? _player;
  MediaClip? _selected;
  String? _playerError;
  bool _muted = false;
  Timer? _ticker;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  @override
  void dispose() {
    _ticker?.cancel();
    _player?.dispose();
    super.dispose();
  }

  Future<void> _reload() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final clips = await widget.host.listMediaClips();
      final storage = await widget.host.mediaStorageInfo();
      if (!mounted) return;
      setState(() {
        _clips = clips;
        _storage = storage;
        _loading = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _error = '$error';
        _loading = false;
      });
    }
  }

  Future<void> _openClip(MediaClip clip) async {
    await _closePlayer();
    final controller = VideoPlayerController.contentUri(Uri.parse(clip.uri));
    setState(() {
      _selected = clip;
      _player = controller;
      _playerError = null;
      _muted = false;
    });
    try {
      await controller.initialize();
      if (!mounted || _player != controller) {
        await controller.dispose();
        return;
      }
      await controller.setVolume(1);
      await controller.play();
      _ticker = Timer.periodic(const Duration(milliseconds: 250), (_) {
        if (mounted) setState(() {});
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _playerError = 'Cannot play this clip: $error';
      });
    }
  }

  Future<void> _closePlayer() async {
    _ticker?.cancel();
    _ticker = null;
    final player = _player;
    if (mounted) {
      setState(() {
        _player = null;
        _selected = null;
        _playerError = null;
      });
    }
    if (player != null) {
      await player.pause();
      await player.dispose();
    }
  }

  Future<void> _togglePlay() async {
    final player = _player;
    if (player == null || !player.value.isInitialized) return;
    if (player.value.isPlaying) {
      await player.pause();
    } else {
      if (player.value.duration > Duration.zero &&
          player.value.position >= player.value.duration) {
        await player.seekTo(Duration.zero);
      }
      await player.play();
    }
    if (mounted) setState(() {});
  }

  Future<void> _toggleMute() async {
    final player = _player;
    if (player == null) return;
    _muted = !_muted;
    await player.setVolume(_muted ? 0 : 1);
    if (mounted) setState(() {});
  }

  Future<void> _deleteSelected() async {
    final clip = _selected;
    if (clip == null) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Delete clip?'),
        content: Text('“${clip.name}” will be removed from the device.'),
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
    if (confirmed != true) return;
    final deleted = await widget.host.deleteMediaClip(clip.id);
    if (!mounted) return;
    if (!deleted) {
      _snack('Could not delete the clip.', UiTokens.danger);
      return;
    }
    _thumbnails.remove(clip.id);
    await _closePlayer();
    _snack('Clip deleted.', UiTokens.success);
    await _reload();
  }

  Future<void> _shareSelected() async {
    final clip = _selected;
    if (clip == null) return;
    final shared = await widget.host.shareMediaClip(clip.id);
    if (!mounted) return;
    if (!shared) _snack('No app available to share this clip.', UiTokens.danger);
  }

  void _snack(String message, Color color) {
    final messenger = ScaffoldMessenger.maybeOf(context);
    messenger?.showSnackBar(
      SnackBar(
        content: Text(message, style: TextStyle(color: color)),
        backgroundColor: UiTokens.surfaceRaised,
        behavior: SnackBarBehavior.floating,
        duration: const Duration(seconds: 3),
      ),
    );
  }

  Future<String?> _thumbnail(MediaClip clip) =>
      _thumbnails.putIfAbsent(
        clip.id,
        () => widget.host.mediaClipThumbnail(clip.id),
      );

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: _selected == null,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _closePlayer();
      },
      child: Scaffold(
        backgroundColor: UiTokens.background,
        body: SafeArea(
          child: Column(
            children: [
              _header(context),
              Expanded(
                child: _selected != null
                    ? _playerView(context, _selected!)
                    : _libraryView(context),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _header(BuildContext context) {
    final storage = _storage;
    final clipCount = _clips?.length ?? 0;
    final free = storage != null && storage.freeBytes > 0
        ? '${_formatBytes(storage.freeBytes)} free'
        : null;
    return Padding(
      padding: const EdgeInsets.fromLTRB(8, 6, 8, 6),
      child: Row(
        children: [
          IconButton(
            tooltip: _selected == null ? 'Back' : 'Back to clips',
            onPressed: () {
              if (_selected != null) {
                _closePlayer();
              } else {
                Navigator.of(context).pop();
              }
            },
            icon: const Icon(Icons.arrow_back, size: 20),
          ),
          Text(
            'SLATECAM CLIPS',
            style: Theme.of(context).textTheme.labelLarge?.copyWith(
              letterSpacing: 2.0,
              fontSize: 12,
            ),
          ),
          const SizedBox(width: 12),
          if (_selected == null)
            Text(
              '$clipCount ${clipCount == 1 ? 'clip' : 'clips'}',
              style: const TextStyle(color: UiTokens.muted, fontSize: 11),
            ),
          const Spacer(),
          if (free != null)
            Text(
              free,
              style: const TextStyle(color: UiTokens.muted, fontSize: 11),
            ),
          if (_selected == null)
            IconButton(
              tooltip: 'Refresh',
              onPressed: _loading ? null : _reload,
              icon: const Icon(Icons.refresh, size: 19),
            ),
        ],
      ),
    );
  }

  Widget _libraryView(BuildContext context) {
    if (_loading) {
      return const Center(
        child: SizedBox(
          width: 26,
          height: 26,
          child: CircularProgressIndicator(strokeWidth: 2),
        ),
      );
    }
    if (_error != null) {
      return _messageView(
        icon: Icons.error_outline,
        title: 'Could not read clips',
        detail: _error!,
        action: TextButton.icon(
          onPressed: _reload,
          icon: const Icon(Icons.refresh, size: 16),
          label: const Text('Try again'),
        ),
      );
    }
    final clips = _clips ?? const <MediaClip>[];
    if (clips.isEmpty) {
      return _messageView(
        icon: Icons.video_library_outlined,
        title: 'No clips yet',
        detail: 'Recorded clips appear here. They live in Movies/SlateCam and '
            'are never uploaded.',
      );
    }
    return GridView.builder(
      padding: const EdgeInsets.fromLTRB(12, 4, 12, 16),
      gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
        maxCrossAxisExtent: 360,
        mainAxisExtent: 238,
        crossAxisSpacing: 10,
        mainAxisSpacing: 10,
      ),
      itemCount: clips.length,
      itemBuilder: (context, index) {
        final clip = clips[index];
        return _ClipTile(
          clip: clip,
          thumbnail: _thumbnail(clip),
          onTap: () => _openClip(clip),
        );
      },
    );
  }

  Widget _playerView(BuildContext context, MediaClip clip) {
    final player = _player;
    final value = player?.value;
    final ready = player != null && value != null && value.isInitialized;
    final durationMs = ready ? value.duration.inMilliseconds : 0;
    final positionMs = ready
        ? value.position.inMilliseconds.clamp(0, durationMs == 0 ? 1 : durationMs)
        : 0;
    return Column(
      children: [
        Expanded(
          child: Container(
            margin: const EdgeInsets.symmetric(horizontal: 12),
            color: Colors.black,
            child: Center(
              child: _playerError != null
                  ? Padding(
                      padding: const EdgeInsets.all(24),
                      child: Text(
                        _playerError!,
                        textAlign: TextAlign.center,
                        style: const TextStyle(color: UiTokens.muted),
                      ),
                    )
                  : !ready
                  ? const SizedBox(
                      width: 28,
                      height: 28,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : AspectRatio(
                      aspectRatio: value.aspectRatio,
                      child: Stack(
                        fit: StackFit.expand,
                        children: [
                          VideoPlayer(player),
                          if (!value.isPlaying)
                            Center(
                              child: IconButton(
                                iconSize: 54,
                                color: Colors.white,
                                onPressed: _togglePlay,
                                icon: const Icon(Icons.play_circle_fill),
                              ),
                            ),
                        ],
                      ),
                    ),
            ),
          ),
        ),
        Container(
          margin: const EdgeInsets.fromLTRB(12, 8, 12, 12),
          padding: const EdgeInsets.fromLTRB(10, 6, 10, 8),
          decoration: BoxDecoration(
            color: UiTokens.surfaceRaised,
            borderRadius: BorderRadius.circular(UiTokens.radius),
            border: Border.all(color: UiTokens.border),
          ),
          child: Column(
            children: [
              Row(
                children: [
                  Text(
                    _formatDuration(positionMs),
                    style: const TextStyle(
                      color: UiTokens.muted,
                      fontSize: 11,
                      fontFeatures: [FontFeature.tabularFigures()],
                    ),
                  ),
                  Expanded(
                    child: Slider(
                      value: positionMs.toDouble(),
                      max: durationMs <= 0 ? 1 : durationMs.toDouble(),
                      onChanged: ready
                          ? (next) => player.seekTo(
                              Duration(milliseconds: next.round()),
                            )
                          : null,
                    ),
                  ),
                  Text(
                    _formatDuration(durationMs),
                    style: const TextStyle(
                      color: UiTokens.muted,
                      fontSize: 11,
                      fontFeatures: [FontFeature.tabularFigures()],
                    ),
                  ),
                ],
              ),
              Row(
                children: [
                  IconButton(
                    tooltip: ready && value.isPlaying ? 'Pause' : 'Play',
                    onPressed: ready ? _togglePlay : null,
                    icon: Icon(
                      ready && value.isPlaying
                          ? Icons.pause
                          : Icons.play_arrow,
                    ),
                  ),
                  IconButton(
                    tooltip: _muted ? 'Unmute' : 'Mute',
                    onPressed: ready ? _toggleMute : null,
                    icon: Icon(
                      _muted ? Icons.volume_off : Icons.volume_up,
                    ),
                  ),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          clip.name,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            fontSize: 12,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                        Text(
                          _clipSubtitle(clip),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            color: UiTokens.muted,
                            fontSize: 10,
                          ),
                        ),
                      ],
                    ),
                  ),
                  IconButton(
                    tooltip: 'Share',
                    onPressed: _shareSelected,
                    icon: const Icon(Icons.ios_share, size: 19),
                  ),
                  IconButton(
                    tooltip: 'Delete',
                    onPressed: _deleteSelected,
                    icon: const Icon(
                      Icons.delete_outline,
                      size: 19,
                      color: UiTokens.danger,
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _messageView({
    required IconData icon,
    required String title,
    required String detail,
    Widget? action,
  }) {
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 460),
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon, size: 42, color: UiTokens.muted),
              const SizedBox(height: 12),
              Text(title, style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 6),
              Text(
                detail,
                textAlign: TextAlign.center,
                style: const TextStyle(color: UiTokens.muted),
              ),
              if (action != null) ...[const SizedBox(height: 12), action],
            ],
          ),
        ),
      ),
    );
  }

  String _clipSubtitle(MediaClip clip) {
    final resolution = clip.width > 0 && clip.height > 0
        ? '${clip.width}×${clip.height}'
        : 'video';
    return '$resolution · ${_formatBytes(clip.sizeBytes)} · '
        '${_formatDate(clip.dateAddedMs)}';
  }
}

class _ClipTile extends StatelessWidget {
  const _ClipTile({
    required this.clip,
    required this.thumbnail,
    required this.onTap,
  });

  final MediaClip clip;
  final Future<String?> thumbnail;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: UiTokens.surfaceRaised,
      borderRadius: BorderRadius.circular(UiTokens.radius),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(
              child: Stack(
                fit: StackFit.expand,
                children: [
                  ColoredBox(color: Colors.black, child: _thumb()),
                  Positioned(
                    right: 6,
                    bottom: 6,
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 6,
                        vertical: 2,
                      ),
                      decoration: BoxDecoration(
                        color: Colors.black.withValues(alpha: 0.72),
                        borderRadius: BorderRadius.circular(6),
                      ),
                      child: Text(
                        _formatDuration(clip.durationMs),
                        style: const TextStyle(
                          fontSize: 10,
                          fontWeight: FontWeight.w700,
                          fontFeatures: [FontFeature.tabularFigures()],
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(8, 6, 8, 8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    clip.name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 11,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    '${clip.width}×${clip.height} · '
                    '${_formatBytes(clip.sizeBytes)} · '
                    '${_formatDate(clip.dateAddedMs)}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      color: UiTokens.muted,
                      fontSize: 9,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _thumb() {
    return FutureBuilder<String?>(
      future: thumbnail,
      builder: (context, snapshot) {
        final path = snapshot.data;
        if (path == null) {
          return const Center(
            child: Icon(Icons.movie_outlined, color: UiTokens.muted, size: 30),
          );
        }
        return Image.file(
          File(path),
          fit: BoxFit.cover,
          errorBuilder: (context, error, stack) => const Center(
            child: Icon(Icons.movie_outlined, color: UiTokens.muted, size: 30),
          ),
        );
      },
    );
  }
}

String _formatDuration(int milliseconds) {
  if (milliseconds <= 0) return '0:00';
  final total = milliseconds ~/ 1000;
  final hours = total ~/ 3600;
  final minutes = (total % 3600) ~/ 60;
  final seconds = total % 60;
  final mm = hours > 0 ? minutes.toString().padLeft(2, '0') : '$minutes';
  final ss = seconds.toString().padLeft(2, '0');
  return hours > 0 ? '$hours:$mm:$ss' : '$mm:$ss';
}

String _formatBytes(int bytes) {
  if (bytes <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  var value = bytes.toDouble();
  var unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  final digits = value >= 100 ? 0 : value >= 10 ? 1 : 2;
  return '${value.toStringAsFixed(digits)} ${units[unit]}';
}

String _formatDate(int milliseconds) {
  if (milliseconds <= 0) return '';
  final date = DateTime.fromMillisecondsSinceEpoch(milliseconds);
  String two(int value) => value.toString().padLeft(2, '0');
  return '${date.year}-${two(date.month)}-${two(date.day)} '
      '${two(date.hour)}:${two(date.minute)}';
}
