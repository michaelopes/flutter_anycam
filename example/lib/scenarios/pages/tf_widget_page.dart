import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_anycam/flutter_anycam.dart';

import '../lab_config.dart';
import '../tf/vehicle_processor.dart';
import '../widgets/camera_selector_tile.dart';
import '../widgets/scenario_scaffold.dart';

class TfWidgetPage extends StatefulWidget {
  const TfWidgetPage({
    super.key,
    required this.cameras,
    required this.checklist,
  });

  final List<FlutterAnycamCameraSelector> cameras;
  final List<String> checklist;

  @override
  State<TfWidgetPage> createState() => _TfWidgetPageState();
}

class _TfWidgetPageState extends State<TfWidgetPage> {
  FlutterAnycamTfSession? _session;
  Uint8List? _cropJpeg;
  int _inferences = 0;
  int _detections = 0;
  int _lastMs = 0;
  String _status = 'idle';

  late final FlutterAnycamCameraSelector? _camera;

  @override
  void initState() {
    super.initState();
    _camera = firstOfFacing(widget.cameras, FlutterAnycamLensFacing.back) ??
        (widget.cameras.isNotEmpty ? widget.cameras.first : null);
  }

  @override
  void dispose() {
    _session?.dispose();
    super.dispose();
  }

  Future<void> _onFrame(FlutterAnycamTfFrame frame) async {
    final sw = Stopwatch()..start();
    try {
      _session ??= await FlutterAnycamTfModel.loadModel(
        assetPath: kVehicleModelAsset,
        inputSize: const FlutterAnycamSize(224, 224),
        outputProcessor: processVehicleDetections,
        trackerOptions: FlutterAnycamTfTrackerOptions(threshold: .1),
      );
      setState(() => _status = 'running');
      final res = await _session!.runInference(
        inputFrame: frame,
        normalize: FlutterAnycamTfNormalize.simple,
      );
      await frame.close();
      if (res.isNotEmpty) {
        final crop = await res.first.getScaledCroppedFrame();
        final img = await crop?.getJpeg();
        if (mounted) {
          setState(() {
            _cropJpeg = img;
            _detections = res.length;
          });
        }
        res.first.close();
      }
      if (mounted) {
        setState(() {
          _inferences++;
          _lastMs = sw.elapsedMilliseconds;
        });
      }
    } catch (e) {
      if (mounted) setState(() => _status = 'error: $e');
      await frame.close();
    }
  }

  @override
  Widget build(BuildContext context) {
    return ScenarioScaffold(
      title: 'TF widget',
      checklist: widget.checklist,
      statusLines: [
        'status=$_status',
        'inferences=$_inferences detections=$_detections',
        'lastMs=$_lastMs',
      ],
      body: _camera == null
          ? const Center(child: Text('No cameras'))
          : Stack(
              fit: StackFit.expand,
              children: [
                FlutterAnycamTfWidget(
                  camera: _camera,
                  fps: 8,
                  preferredSize: const FlutterAnycamSize(640, 480),
                  resizeFrame: const FlutterAnycamSize(224, 224),
                  onFrame: _onFrame,
                ),
                if (_cropJpeg != null)
                  Positioned(
                    top: 12,
                    right: 12,
                    child: SizedBox(
                      height: 120,
                      child: Image.memory(_cropJpeg!, gaplessPlayback: true),
                    ),
                  ),
              ],
            ),
    );
  }
}
