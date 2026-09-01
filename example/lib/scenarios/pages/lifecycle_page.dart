import 'package:flutter/material.dart';
import 'package:flutter_anycam/flutter_anycam.dart';

import '../widgets/camera_selector_tile.dart';
import '../widgets/scenario_scaffold.dart';

class LifecyclePage extends StatefulWidget {
  const LifecyclePage({
    super.key,
    required this.cameras,
    required this.checklist,
  });

  final List<FlutterAnycamCameraSelector> cameras;
  final List<String> checklist;

  @override
  State<LifecyclePage> createState() => _LifecyclePageState();
}

class _LifecyclePageState extends State<LifecyclePage> {
  bool _mountedPreview = true;
  int _remounts = 0;
  int _frames = 0;

  late final FlutterAnycamCameraSelector? _camera;

  @override
  void initState() {
    super.initState();
    _camera = firstOfFacing(widget.cameras, FlutterAnycamLensFacing.back) ??
        (widget.cameras.isNotEmpty ? widget.cameras.first : null);
  }

  void _toggle() {
    setState(() {
      _mountedPreview = !_mountedPreview;
      if (_mountedPreview) {
        _remounts++;
        _frames = 0;
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return ScenarioScaffold(
      title: 'Lifecycle',
      checklist: widget.checklist,
      statusLines: [
        'previewMounted=$_mountedPreview',
        'remounts=$_remounts frames=$_frames',
        'Also: background the app >=200ms and resume (Android recreate)',
      ],
      body: _camera == null
          ? const Center(child: Text('No cameras'))
          : _mountedPreview
              ? FlutterAnycamWidget(
                  camera: _camera,
                  fps: 5,
                  onFrame: (_) => setState(() => _frames++),
                )
              : const ColoredBox(
                  color: Colors.black,
                  child: Center(
                    child: Text(
                      'Preview disposed\nTap Remount',
                      textAlign: TextAlign.center,
                      style: TextStyle(color: Colors.white),
                    ),
                  ),
                ),
      bottomBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(8),
          child: FilledButton(
            key: const Key('lifecycle_toggle'),
            onPressed: _toggle,
            child: Text(_mountedPreview ? 'Unmount preview' : 'Remount preview'),
          ),
        ),
      ),
    );
  }
}
