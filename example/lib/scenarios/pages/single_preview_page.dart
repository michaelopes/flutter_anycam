import 'package:flutter/material.dart';
import 'package:flutter_anycam/flutter_anycam.dart';

import '../widgets/camera_selector_tile.dart';
import '../widgets/scenario_scaffold.dart';

class SinglePreviewPage extends StatefulWidget {
  const SinglePreviewPage({
    super.key,
    required this.cameras,
    required this.facing,
    required this.title,
    required this.checklist,
  });

  final List<FlutterAnycamCameraSelector> cameras;
  final FlutterAnycamLensFacing facing;
  final String title;
  final List<String> checklist;

  @override
  State<SinglePreviewPage> createState() => _SinglePreviewPageState();
}

class _SinglePreviewPageState extends State<SinglePreviewPage> {
  final _mesure = FlutterAnycamMesure(seconds: 1);
  int _fps = 0;
  String _lastEvent = 'waiting';

  @override
  void initState() {
    super.initState();
    _mesure.startCounting(callback: (c) {
      if (mounted) setState(() => _fps = c);
    });
  }

  @override
  void dispose() {
    _mesure.stopCounting();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final camera = firstOfFacing(widget.cameras, widget.facing);
    return ScenarioScaffold(
      title: widget.title,
      checklist: widget.checklist,
      statusLines: [
        'fps=$_fps',
        'event=$_lastEvent',
        'camera=${camera?.id ?? "missing"}',
      ],
      body: camera == null
          ? Center(child: Text('No ${widget.facing.name} camera found'))
          : FlutterAnycamWidget(
              camera: camera,
              enableDebug: true,
              fps: 10,
              onFrame: (_) {
                _mesure.count();
                if (_lastEvent != 'frame') {
                  setState(() => _lastEvent = 'frame');
                }
              },
            ),
    );
  }
}
