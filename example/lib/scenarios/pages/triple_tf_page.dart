import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_anycam/flutter_anycam.dart';

import '../lab_config.dart';
import '../tf/vehicle_processor.dart';
import '../widgets/analysis_frame_thumb.dart';
import '../widgets/camera_lane.dart';
import '../widgets/camera_selector_tile.dart';
import '../widgets/scenario_scaffold.dart';
import '../widgets/triple_lab_bar.dart';

class TripleTfPage extends StatefulWidget {
  const TripleTfPage({
    super.key,
    required this.cameras,
    required this.checklist,
  });

  final List<FlutterAnycamCameraSelector> cameras;
  final List<String> checklist;

  @override
  State<TripleTfPage> createState() => _TripleTfPageState();
}

class _TfLane {
  FlutterAnycamTfSession? session;
  Uint8List? cropJpeg;
  int inferences = 0;
  int detections = 0;
  int lastMs = 0;
  int fps = 0;
  final mesure = FlutterAnycamMesure(seconds: 1);
  bool busy = false;

  void start(VoidCallback onTick) {
    mesure.startCounting(callback: (count) {
      fps = count;
      onTick();
    });
  }

  void stop() => mesure.stopCounting();

  void reset() {
    inferences = 0;
    detections = 0;
    lastMs = 0;
    fps = 0;
    cropJpeg = null;
  }

  Future<void> disposeSession() async {
    await session?.dispose();
    session = null;
  }
}

class _TripleTfPageState extends State<TripleTfPage> {
  final _url = TextEditingController(text: kLabRtspUrl);
  final _user = TextEditingController();
  final _pass = TextEditingController();
  final _usbLane = _TfLane();
  final _rtspLane = _TfLane();
  final _backLane = _TfLane();

  FlutterAnycamCameraSelector? _usb;
  FlutterAnycamCameraSelector? _back;
  FlutterAnycamCameraSelector? _rtsp;
  bool _open = false;
  String _status = 'refresh cams, then Open 3 cameras';

  @override
  void initState() {
    super.initState();
    _applyCameras(widget.cameras);
    void tick() {
      if (mounted) setState(() {});
    }

    _usbLane.start(tick);
    _rtspLane.start(tick);
    _backLane.start(tick);
  }

  @override
  void dispose() {
    _usbLane.stop();
    _rtspLane.stop();
    _backLane.stop();
    _usbLane.disposeSession();
    _rtspLane.disposeSession();
    _backLane.disposeSession();
    _url.dispose();
    _user.dispose();
    _pass.dispose();
    super.dispose();
  }

  void _applyCameras(List<FlutterAnycamCameraSelector> cameras) {
    _usb = firstOfFacing(cameras, FlutterAnycamLensFacing.usb);
    _back = firstOfFacing(cameras, FlutterAnycamLensFacing.back);
  }

  Future<void> _refresh() async {
    setState(() => _status = 'refreshing availableCameras()');
    try {
      final cameras = await FlutterAnycam.availableCameras();
      if (!mounted) return;
      setState(() {
        _applyCameras(cameras);
        _status = _usb == null || _back == null
            ? 'need USB + rear camera (usb=${_usb?.id ?? "none"} back=${_back?.id ?? "none"})'
            : 'USB + rear ready — tap Open 3 cameras';
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _status = 'refresh failed: $e');
    }
  }

  void _useLabPreset() {
    setState(() {
      _url.text = kLabRtspUrl;
      _user.clear();
      _pass.clear();
    });
  }

  Future<void> _connect() async {
    final usb = _usb;
    final back = _back;
    final url = _url.text.trim();
    if (usb == null || back == null || url.isEmpty) {
      setState(() {
        _status =
            'missing sources: usb=${usb?.id ?? "none"} back=${back?.id ?? "none"} url=$url';
      });
      return;
    }
    setState(() {
      _rtsp = FlutterAnycamCameraSelector.rtsp(
        url: url,
        username: _user.text.trim(),
        password: _pass.text,
      );
      _usbLane.reset();
      _rtspLane.reset();
      _backLane.reset();
      _open = true;
      _status = 'running vehicle_detection.tflite on 3 cameras';
    });
  }

  Future<void> _disconnect() async {
    await _usbLane.disposeSession();
    await _rtspLane.disposeSession();
    await _backLane.disposeSession();
    if (!mounted) return;
    setState(() {
      _open = false;
      _rtsp = null;
      _status = 'closed';
    });
  }

  Future<void> _onLaneFrame(_TfLane lane, FlutterAnycamTfFrame frame) async {
    if (lane.busy) {
      await frame.close();
      return;
    }
    lane.busy = true;
    final sw = Stopwatch()..start();
    try {
      lane.session ??= await FlutterAnycamTfModel.loadModel(
        assetPath: kVehicleModelAsset,
        inputSize: const FlutterAnycamSize(224, 224),
        outputProcessor: processVehicleDetections,
        trackerOptions: FlutterAnycamTfTrackerOptions(threshold: .1),
      );
      final res = await lane.session!.runInference(
        inputFrame: frame,
        normalize: FlutterAnycamTfNormalize.simple,
      );
      await frame.close();
      lane.mesure.count();
      Uint8List? jpeg;
      if (res.isNotEmpty) {
        final crop = await res.first.getScaledCroppedFrame();
        jpeg = await crop?.getJpeg();
        res.first.close();
      }
      if (!mounted) return;
      setState(() {
        lane.inferences++;
        lane.detections = res.length;
        lane.lastMs = sw.elapsedMilliseconds;
        if (jpeg != null) lane.cropJpeg = jpeg;
        if (_status.startsWith('running')) {
          _status = 'inferences running';
        }
      });
    } catch (e) {
      await frame.close();
      if (mounted) setState(() => _status = 'error: $e');
    } finally {
      lane.busy = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    return ScenarioScaffold(
      title: 'Triple TF analysis',
      checklist: widget.checklist,
      statusLines: [
        'status=$_status',
        'usb inf=${_usbLane.inferences} det=${_usbLane.detections} fps=${_usbLane.fps} ms=${_usbLane.lastMs}',
        'rtsp inf=${_rtspLane.inferences} det=${_rtspLane.detections} fps=${_rtspLane.fps} ms=${_rtspLane.lastMs}',
        'back inf=${_backLane.inferences} det=${_backLane.detections} fps=${_backLane.fps} ms=${_backLane.lastMs}',
      ],
      body: Column(
        children: [
          TripleLabBar(
            url: _url,
            user: _user,
            pass: _pass,
            usbLabel: _usb?.id ?? 'none',
            backLabel: _back?.id ?? 'none',
            onRefresh: _refresh,
            onConnect: _connect,
            onDisconnect: _disconnect,
            onLabPreset: _useLabPreset,
          ),
          Expanded(
            child: !_open || _usb == null || _back == null || _rtsp == null
                ? const ColoredBox(
                    color: Colors.black,
                    child: Center(
                      child: Text(
                        'USB + RTSP + rear analysis not open',
                        style: TextStyle(color: Colors.white70),
                      ),
                    ),
                  )
                : Column(
                    children: [
                      Expanded(
                        child: _tfLane(
                          title: 'USB ${_usb!.id}',
                          camera: _usb!,
                          lane: _usbLane,
                        ),
                      ),
                      const Divider(height: 1),
                      Expanded(
                        child: _tfLane(
                          title: 'RTSP',
                          camera: _rtsp!,
                          lane: _rtspLane,
                        ),
                      ),
                      const Divider(height: 1),
                      Expanded(
                        child: _tfLane(
                          title: 'Back ${_back!.id}',
                          camera: _back!,
                          lane: _backLane,
                        ),
                      ),
                    ],
                  ),
          ),
        ],
      ),
    );
  }

  Widget _tfLane({
    required String title,
    required FlutterAnycamCameraSelector camera,
    required _TfLane lane,
  }) {
    return CameraLane(
      title: title,
      badge: AnalysisFrameThumb(
        jpeg: lane.cropJpeg,
        label: 'crop inf=${lane.inferences} det=${lane.detections}',
        width: 96,
        height: 72,
      ),
      child: FlutterAnycamTfWidget(
        key: ValueKey('triple-tf-${camera.id}'),
        camera: camera,
        fps: 5,
        preferredSize: const FlutterAnycamSize(640, 480),
        resizeFrame: const FlutterAnycamSize(224, 224),
        maxConcurrentFrames: 1,
        onFrame: (frame) => _onLaneFrame(lane, frame),
      ),
    );
  }
}
