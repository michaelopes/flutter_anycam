import 'package:flutter/material.dart';
import 'package:flutter_anycam/flutter_anycam.dart';

import '../lab_config.dart';
import '../widgets/camera_lane.dart';
import '../widgets/camera_selector_tile.dart';
import '../widgets/scenario_scaffold.dart';
import '../widgets/triple_lab_bar.dart';

class TripleCameraPage extends StatefulWidget {
  const TripleCameraPage({
    super.key,
    required this.cameras,
    required this.checklist,
  });

  final List<FlutterAnycamCameraSelector> cameras;
  final List<String> checklist;

  @override
  State<TripleCameraPage> createState() => _TripleCameraPageState();
}

class _LaneStats {
  final FlutterAnycamMesure mesure = FlutterAnycamMesure(seconds: 1);
  int frames = 0;
  int fps = 0;

  void start(VoidCallback onTick) {
    mesure.startCounting(callback: (count) {
      fps = count;
      onTick();
    });
  }

  void stop() => mesure.stopCounting();

  void onFrame() {
    frames++;
    mesure.count();
  }

  void reset() {
    frames = 0;
    fps = 0;
  }
}

class _TripleCameraPageState extends State<TripleCameraPage> {
  final _url = TextEditingController(text: kLabRtspUrl);
  final _user = TextEditingController();
  final _pass = TextEditingController();
  final _usbStats = _LaneStats();
  final _rtspStats = _LaneStats();
  final _backStats = _LaneStats();

  FlutterAnycamCameraSelector? _usb;
  FlutterAnycamCameraSelector? _back;
  FlutterAnycamCameraSelector? _rtsp;
  bool _open = false;
  String _status = 'refresh cams, then Open 3 cameras';

  @override
  void initState() {
    super.initState();
    _applyCameras(widget.cameras);
    void syncFps() {
      if (mounted) setState(() {});
    }

    _usbStats.start(syncFps);
    _rtspStats.start(syncFps);
    _backStats.start(syncFps);
  }

  @override
  void dispose() {
    _usbStats.stop();
    _rtspStats.stop();
    _backStats.stop();
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

  void _connect() {
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
      _usbStats.reset();
      _rtspStats.reset();
      _backStats.reset();
      _open = true;
      _status = 'opening USB + RTSP + rear';
    });
  }

  void _disconnect() {
    setState(() {
      _open = false;
      _rtsp = null;
      _status = 'closed';
    });
  }

  @override
  Widget build(BuildContext context) {
    return ScenarioScaffold(
      title: 'Triple cameras',
      checklist: widget.checklist,
      statusLines: [
        'status=$_status',
        'usb fps=${_usbStats.fps} frames=${_usbStats.frames} id=${_usb?.id ?? "-"}',
        'rtsp fps=${_rtspStats.fps} frames=${_rtspStats.frames} id=${_rtsp?.id ?? "-"}',
        'back fps=${_backStats.fps} frames=${_backStats.frames} id=${_back?.id ?? "-"}',
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
                        'USB + RTSP + rear not open',
                        style: TextStyle(color: Colors.white70),
                      ),
                    ),
                  )
                : Column(
                    children: [
                      Expanded(
                        child: CameraLane(
                          title: 'USB ${_usb!.id}',
                          child: FlutterAnycamWidget(
                            key: ValueKey('triple-usb-${_usb!.id}'),
                            camera: _usb!,
                            fps: 8,
                            preferredSize: const FlutterAnycamSize(640, 480),
                            onFrame: (_) {
                              _usbStats.onFrame();
                              if (_status != 'receiving frames') {
                                setState(() => _status = 'receiving frames');
                              }
                            },
                          ),
                        ),
                      ),
                      const Divider(height: 1),
                      Expanded(
                        child: CameraLane(
                          title: 'RTSP',
                          child: FlutterAnycamWidget(
                            key: ValueKey('triple-rtsp-${_rtsp!.id}'),
                            camera: _rtsp!,
                            fps: 8,
                            preferredSize: const FlutterAnycamSize(640, 480),
                            onFrame: (_) {
                              _rtspStats.onFrame();
                              if (_status != 'receiving frames') {
                                setState(() => _status = 'receiving frames');
                              }
                            },
                          ),
                        ),
                      ),
                      const Divider(height: 1),
                      Expanded(
                        child: CameraLane(
                          title: 'Back ${_back!.id}',
                          child: FlutterAnycamWidget(
                            key: ValueKey('triple-back-${_back!.id}'),
                            camera: _back!,
                            fps: 8,
                            preferredSize: const FlutterAnycamSize(640, 480),
                            onFrame: (_) {
                              _backStats.onFrame();
                              if (_status != 'receiving frames') {
                                setState(() => _status = 'receiving frames');
                              }
                            },
                          ),
                        ),
                      ),
                    ],
                  ),
          ),
        ],
      ),
    );
  }
}
