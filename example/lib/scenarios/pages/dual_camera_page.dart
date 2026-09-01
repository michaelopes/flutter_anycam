import 'package:flutter/material.dart';
import 'package:flutter_anycam/flutter_anycam.dart';

import '../widgets/camera_selector_tile.dart';
import '../widgets/scenario_scaffold.dart';

class DualCameraPage extends StatefulWidget {
  const DualCameraPage({
    super.key,
    required this.cameras,
    required this.checklist,
  });

  final List<FlutterAnycamCameraSelector> cameras;
  final List<String> checklist;

  @override
  State<DualCameraPage> createState() => _DualCameraPageState();
}

class _DualCameraPageState extends State<DualCameraPage> {
  int _backFrames = 0;
  int _frontFrames = 0;

  @override
  Widget build(BuildContext context) {
    final back = firstOfFacing(widget.cameras, FlutterAnycamLensFacing.back);
    final front = firstOfFacing(widget.cameras, FlutterAnycamLensFacing.front);

    return ScenarioScaffold(
      title: 'Dual camera',
      checklist: widget.checklist,
      statusLines: [
        'backFrames=$_backFrames id=${back?.id ?? "-"}',
        'frontFrames=$_frontFrames id=${front?.id ?? "-"}',
        'Expect CameraX concurrent OR Camera2 fallback without crash',
      ],
      body: (back == null || front == null)
          ? const Center(child: Text('Need both back and front cameras'))
          : Column(
              children: [
                Expanded(
                  child: FlutterAnycamWidget(
                    camera: back,
                    fps: 5,
                    preferredSize: const FlutterAnycamSize(640, 480),
                    onFrame: (_) => setState(() => _backFrames++),
                  ),
                ),
                const Divider(height: 1),
                Expanded(
                  child: FlutterAnycamWidget(
                    camera: front,
                    fps: 5,
                    preferredSize: const FlutterAnycamSize(640, 480),
                    onFrame: (_) => setState(() => _frontFrames++),
                  ),
                ),
              ],
            ),
    );
  }
}
