import 'package:flutter/material.dart';
import 'package:flutter_anycam/flutter_anycam.dart';

import '../widgets/camera_selector_tile.dart';
import '../widgets/scenario_scaffold.dart';

class SwitchCameraPage extends StatefulWidget {
  const SwitchCameraPage({
    super.key,
    required this.cameras,
    required this.checklist,
  });

  final List<FlutterAnycamCameraSelector> cameras;
  final List<String> checklist;

  @override
  State<SwitchCameraPage> createState() => _SwitchCameraPageState();
}

class _SwitchCameraPageState extends State<SwitchCameraPage> {
  late FlutterAnycamCameraSelector? _selected;
  int _frames = 0;
  int _switchCount = 0;

  @override
  void initState() {
    super.initState();
    _selected = firstOfFacing(widget.cameras, FlutterAnycamLensFacing.back) ??
        (widget.cameras.isNotEmpty ? widget.cameras.first : null);
  }

  void _toggle() {
    final back = firstOfFacing(widget.cameras, FlutterAnycamLensFacing.back);
    final front = firstOfFacing(widget.cameras, FlutterAnycamLensFacing.front);
    if (back == null || front == null) return;
    setState(() {
      _selected = _selected?.id == back.id ? front : back;
      _switchCount++;
      _frames = 0;
    });
  }

  @override
  Widget build(BuildContext context) {
    return ScenarioScaffold(
      title: 'Switch camera',
      checklist: widget.checklist,
      statusLines: [
        'switches=$_switchCount',
        'frames=$_frames',
        'camera=${_selected?.id ?? "-"} (${_selected?.lensFacing.name})',
      ],
      actions: [
        IconButton(
          key: const Key('switch_camera_btn'),
          onPressed: _toggle,
          icon: const Icon(Icons.cameraswitch),
          tooltip: 'Switch',
        ),
      ],
      body: _selected == null
          ? const Center(child: Text('No cameras'))
          : FlutterAnycamWidget(
              camera: _selected!,
              fps: 8,
              onFrame: (_) => setState(() => _frames++),
            ),
      bottomBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(8),
          child: FilledButton.icon(
            key: const Key('switch_camera_bottom'),
            onPressed: _toggle,
            icon: const Icon(Icons.cameraswitch),
            label: const Text('Switch front/back (didUpdateWidget)'),
          ),
        ),
      ),
    );
  }
}
