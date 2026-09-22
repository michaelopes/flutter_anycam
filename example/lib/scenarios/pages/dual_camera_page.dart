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
  int _primaryFrames = 0;
  int _secondaryFrames = 0;

  @override
  Widget build(BuildContext context) {
    final pair = pickDualCameras(widget.cameras);
    final primary = pair?.primary;
    final secondary = pair?.secondary;
    final mode = _pairModeLabel(primary, secondary);

    return ScenarioScaffold(
      title: 'Dual camera',
      checklist: widget.checklist,
      statusLines: [
        'mode=$mode',
        'camA frames=$_primaryFrames id=${primary?.id ?? "-"} '
            '(${primary?.lensFacing.name ?? "-"})',
        'camB frames=$_secondaryFrames id=${secondary?.id ?? "-"} '
            '(${secondary?.lensFacing.name ?? "-"})',
        'Expect CameraX concurrent OR Camera2 fallback without crash',
      ],
      body: (primary == null || secondary == null)
          ? const Center(
              child: Padding(
                padding: EdgeInsets.all(24),
                child: Text(
                  'Need 2 device cameras with different ids '
                  '(back+front, or two backs with ids like 100/101).',
                  textAlign: TextAlign.center,
                ),
              ),
            )
          : Column(
              children: [
                Expanded(
                  child: FlutterAnycamWidget(
                    camera: primary,
                    fps: 5,
                    preferredSize: const FlutterAnycamSize(640, 480),
                    onFrame: (_) => setState(() => _primaryFrames++),
                  ),
                ),
                const Divider(height: 1),
                Expanded(
                  child: FlutterAnycamWidget(
                    camera: secondary,
                    fps: 5,
                    preferredSize: const FlutterAnycamSize(640, 480),
                    onFrame: (_) => setState(() => _secondaryFrames++),
                  ),
                ),
              ],
            ),
    );
  }

  String _pairModeLabel(
    FlutterAnycamCameraSelector? a,
    FlutterAnycamCameraSelector? b,
  ) {
    if (a == null || b == null) return 'none';
    if (a.lensFacing == FlutterAnycamLensFacing.back &&
        b.lensFacing == FlutterAnycamLensFacing.front) {
      return 'classic_back_front';
    }
    if (hasHighCameraId(a) || hasHighCameraId(b)) {
      return 'multi_id_ge_100';
    }
    return 'two_device_ids';
  }
}
