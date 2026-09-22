import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_anycam/flutter_anycam.dart';

import '../widgets/camera_selector_tile.dart';
import '../widgets/scenario_scaffold.dart';

class JpegPage extends StatefulWidget {
  const JpegPage({
    super.key,
    required this.cameras,
    required this.checklist,
  });

  final List<FlutterAnycamCameraSelector> cameras;
  final List<String> checklist;

  @override
  State<JpegPage> createState() => _JpegPageState();
}

class _JpegPageState extends State<JpegPage> {
  Uint8List? _jpeg;
  int _conversions = 0;
  int _lastMs = 0;
  bool _busy = false;
  late final FlutterAnycamCameraSelector? _camera;

  @override
  void initState() {
    super.initState();
    _camera = firstOfFacing(widget.cameras, FlutterAnycamLensFacing.back) ??
        (widget.cameras.isNotEmpty ? widget.cameras.first : null);
  }

  Future<void> _onFrame(FlutterAnycamFrame frame) async {
    if (_busy) return;
    _busy = true;
    final sw = Stopwatch()..start();
    try {
      final img = await FlutterAnycam.frameConversor.convertToJpeg(
        frame: frame,
        quality: 80,
      );
      if (!mounted) return;
      setState(() {
        _jpeg = img;
        _conversions++;
        _lastMs = sw.elapsedMilliseconds;
      });
    } catch (e) {
      if (mounted) {
        setState(() => _lastMs = -1);
      }
    } finally {
      _busy = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    return ScenarioScaffold(
      title: 'JPEG conversion',
      checklist: widget.checklist,
      statusLines: [
        'conversions=$_conversions',
        'lastMs=$_lastMs',
        'jpegBytes=${_jpeg?.length ?? 0}',
      ],
      body: _camera == null
          ? const Center(child: Text('No cameras'))
          : Stack(
              fit: StackFit.expand,
              children: [
                FlutterAnycamWidget(
                  camera: _camera,
                  fps: 3,
                  onFrame: _onFrame,
                ),
                if (_jpeg != null)
                  Positioned(
                    top: 12,
                    right: 12,
                    child: Container(
                      decoration: BoxDecoration(
                        border: Border.all(color: Colors.white, width: 2),
                      ),
                      height: 140,
                      child: Image.memory(_jpeg!, gaplessPlayback: true),
                    ),
                  ),
              ],
            ),
    );
  }
}
