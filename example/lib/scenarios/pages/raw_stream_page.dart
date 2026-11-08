import 'package:flutter/material.dart';
import 'package:flutter_anycam/flutter_anycam.dart';

import '../widgets/camera_selector_tile.dart';
import '../widgets/scenario_scaffold.dart';

class RawStreamPage extends StatefulWidget {
  const RawStreamPage({
    super.key,
    required this.cameras,
    required this.checklist,
  });

  final List<FlutterAnycamCameraSelector> cameras;
  final List<String> checklist;

  @override
  State<RawStreamPage> createState() => _RawStreamPageState();
}

class _RawStreamPageState extends State<RawStreamPage> {
  FlutterAnycamCameraRawStreamDisposer? _disposer;
  int _rawFrames = 0;
  int _previewFrames = 0;
  String _status = 'registering';

  late final FlutterAnycamCameraSelector? _camera;

  @override
  void initState() {
    super.initState();
    _camera = firstOfFacing(widget.cameras, FlutterAnycamLensFacing.back) ??
        (widget.cameras.isNotEmpty ? widget.cameras.first : null);
    _register();
  }

  Future<void> _register() async {
    final camera = _camera;
    if (camera == null) {
      setState(() => _status = 'no camera');
      return;
    }
    _disposer = await FlutterAnycamCameraRawStream.I.register(
      cameraId: camera.id,
      fps: 5,
      listener: (_) {
        if (mounted) setState(() => _rawFrames++);
      },
    );
    if (mounted) setState(() => _status = 'listening');
  }

  @override
  void dispose() {
    _disposer?.call();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ScenarioScaffold(
      title: 'Raw stream',
      checklist: widget.checklist,
      statusLines: [
        'status=$_status',
        'rawFrames=$_rawFrames',
        'previewFrames=$_previewFrames',
        'camera=${_camera?.id ?? "-"}',
      ],
      body: _camera == null
          ? const Center(child: Text('No cameras'))
          : FlutterAnycamWidget(
              camera: _camera,
              fps: 5,
              onFrame: (_) => setState(() => _previewFrames++),
            ),
    );
  }
}
