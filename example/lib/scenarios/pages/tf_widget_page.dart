import 'dart:math';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_anycam/flutter_anycam.dart';

import '../tf/vehicle_labels.dart';
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

  List<FlutterAnycamTfInferenceOutput> _processor(
    dynamic data,
    FlutterAnycamSize inputSize,
  ) {
    final output = {
      0: List.filled(1 * 300 * 6, 0.0).reshape([1, 300, 6]),
    };
    FlutterAnycamTfSession.cast(data, output);
    final lst = output[0]![0];
    int lastKey = -1;
    final vehicles = <FlutterAnycamTfInferenceOutput>[];

    for (var i = 0; i < lst.length; i++) {
      final item = lst[i];
      final key =
          Object.hash(item[0], item[1], item[2], item[3], item[4], item[5]);
      if (lastKey == key) continue;
      lastKey = key;
      final confidence = item[4];
      final classId = item[5].toInt();
      if (confidence >= .35 &&
          confidence <= .999 &&
          VehicleLabels.isVehicle(classId)) {
        final y1 = item[1] as double;
        final x1 = item[0] as double;
        final y2 = item[3] as double;
        final x2 = item[2] as double;
        final rX1 = max(0.0, x1 * inputSize.width);
        final rY1 = max(0.0, y1 * inputSize.height);
        final rX2 = min(inputSize.width.toDouble(), x2 * inputSize.width);
        final rY2 = min(inputSize.height.toDouble(), y2 * inputSize.height);
        try {
          vehicles.add(
            FlutterAnycamTfInferenceOutput(
              score: confidence,
              box: FlutterAnycamTfInferenceOutputBox(
                xMin: rX1.toInt(),
                yMin: rY1.toInt(),
                xMax: rX2.toInt(),
                yMax: rY2.toInt(),
                srcWidth: inputSize.width,
                srcHeight: inputSize.height,
                classId: classId,
                minScaledCropSize: const FlutterAnycamSize(64, 64),
              ),
            ),
          );
        } catch (_) {}
      }
    }
    return vehicles;
  }

  Future<void> _onFrame(FlutterAnycamTfFrame frame) async {
    final sw = Stopwatch()..start();
    try {
      _session ??= await FlutterAnycamTfModel.loadModel(
        assetPath: 'assets/vehicle_detection.tflite',
        inputSize: const FlutterAnycamSize(224, 224),
        outputProcessor: _processor,
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
