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
  FlutterAnycamCameraSelector? _selected;
  FlutterAnycamCameraSelector? _other;
  int _frames = 0;
  int _switchCount = 0;

  @override
  void initState() {
    super.initState();
    final pair = pickDualCameras(widget.cameras);
    if (pair != null) {
      _selected = pair.primary;
      _other = pair.secondary;
    } else if (widget.cameras.isNotEmpty) {
      _selected = widget.cameras.first;
    }
  }

  void _toggle() {
    if (_selected == null || _other == null) return;
    setState(() {
      final tmp = _selected;
      _selected = _other;
      _other = tmp;
      _switchCount++;
      _frames = 0;
    });
  }

  @override
  Widget build(BuildContext context) {
    final canSwitch = _selected != null && _other != null;
    return ScenarioScaffold(
      title: 'Switch camera',
      checklist: widget.checklist,
      statusLines: [
        'switches=$_switchCount',
        'frames=$_frames',
        'camera=${_selected?.id ?? "-"} (${_selected?.lensFacing.name})',
        'other=${_other?.id ?? "-"} (${_other?.lensFacing.name ?? "-"})',
      ],
      actions: [
        IconButton(
          key: const Key('switch_camera_btn'),
          onPressed: canSwitch ? _toggle : null,
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
            onPressed: canSwitch ? _toggle : null,
            icon: const Icon(Icons.cameraswitch),
            label: Text(
              canSwitch
                  ? 'Switch id ${_selected!.id} ↔ ${_other!.id}'
                  : 'Need 2 cameras to switch',
            ),
          ),
        ),
      ),
    );
  }
}
