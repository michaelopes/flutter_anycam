import 'package:flutter/material.dart';
import 'package:flutter_anycam/flutter_anycam.dart';

import '../widgets/camera_selector_tile.dart';
import '../widgets/scenario_scaffold.dart';

class TfHeadlessPage extends StatefulWidget {
  const TfHeadlessPage({
    super.key,
    required this.cameras,
    required this.checklist,
  });

  final List<FlutterAnycamCameraSelector> cameras;
  final List<String> checklist;

  @override
  State<TfHeadlessPage> createState() => _TfHeadlessPageState();
}

class _TfHeadlessPageState extends State<TfHeadlessPage> {
  FlutterAnycamTfCameraStreamDisposer? _disposer;
  int _frames = 0;
  String _status = 'idle';
  String _size = '-';

  late final FlutterAnycamCameraSelector? _camera;

  @override
  void initState() {
    super.initState();
    _camera = firstOfFacing(widget.cameras, FlutterAnycamLensFacing.back) ??
        (widget.cameras.isNotEmpty ? widget.cameras.first : null);
    _start();
  }

  Future<void> _start() async {
    final camera = _camera;
    if (camera == null) {
      setState(() => _status = 'no camera');
      return;
    }
    setState(() => _status = 'registering');
    _disposer = await FlutterAnycamTfCameraStream.I.register(
      camera: camera,
      fps: 5,
      preferredSize: const FlutterAnycamSize(640, 480),
      onConnected: ({required cameraId, required width, required height}) {
        if (mounted) {
          setState(() {
            _status = 'connected';
            _size = '${width}x$height';
          });
        }
      },
      onFailed: ({required cameraId, required message}) {
        if (mounted) setState(() => _status = 'failed: $message');
      },
      onFrame: (frame) async {
        if (mounted) setState(() => _frames++);
        await frame.close();
      },
    );
  }

  @override
  void dispose() {
    _disposer?.call();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ScenarioScaffold(
      title: 'TF headless stream',
      checklist: widget.checklist,
      statusLines: [
        'status=$_status',
        'size=$_size',
        'frames=$_frames',
        'camera=${_camera?.id ?? "-"}',
        'No preview texture — headless Camera2 only',
      ],
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.memory, size: 64),
            const SizedBox(height: 12),
            Text(_status, style: Theme.of(context).textTheme.titleMedium),
            Text('frames: $_frames'),
          ],
        ),
      ),
    );
  }
}
