import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_anycam/flutter_anycam.dart';

import '../lab_config.dart';
import '../widgets/analysis_frame_thumb.dart';
import '../widgets/scenario_scaffold.dart';

class RtspPage extends StatefulWidget {
  const RtspPage({
    super.key,
    required this.checklist,
  });

  final List<String> checklist;

  @override
  State<RtspPage> createState() => _RtspPageState();
}

class _RtspPageState extends State<RtspPage> {
  static const _fpsPresets = kLabFpsPresets;

  final _url = TextEditingController(text: kLabRtspUrl);
  final _user = TextEditingController();
  final _pass = TextEditingController();
  final _analysisMesure = FlutterAnycamMesure(seconds: 1);
  final _jpegMesure = FlutterAnycamMesure(seconds: 1);

  FlutterAnycamCameraSelector? _camera;
  int _targetFps = 30;
  int _frames = 0;
  int _analysisFps = 0;
  int _maxAnalysisFps = 0;
  int _jpegFps = 0;
  String _status = 'tap Connect (lab URL prefilled)';
  Uint8List? _analysisJpeg;
  int _lastJpegMs = 0;
  bool _jpegBusy = false;
  bool _showAnalysisThumb = true;

  @override
  void initState() {
    super.initState();
    _analysisMesure.startCounting(callback: (count) {
      if (!mounted) return;
      setState(() {
        _analysisFps = count;
        if (count > _maxAnalysisFps) {
          _maxAnalysisFps = count;
        }
      });
    });
    _jpegMesure.startCounting(callback: (count) {
      if (!mounted) return;
      setState(() => _jpegFps = count);
    });
  }

  @override
  void dispose() {
    _analysisMesure.stopCounting();
    _jpegMesure.stopCounting();
    _url.dispose();
    _user.dispose();
    _pass.dispose();
    super.dispose();
  }

  void _useLabPreset() {
    setState(() {
      _url.text = kLabRtspUrl;
      _user.clear();
      _pass.clear();
      _status = 'lab preset loaded — tap Connect';
    });
  }

  void _resetFpsStats() {
    _frames = 0;
    _analysisFps = 0;
    _maxAnalysisFps = 0;
    _jpegFps = 0;
    _lastJpegMs = 0;
  }

  void _connect() {
    setState(() {
      _camera = FlutterAnycamCameraSelector.rtsp(
        url: _url.text.trim(),
        username: _user.text.trim(),
        password: _pass.text,
      );
      _analysisJpeg = null;
      _resetFpsStats();
      _status = 'connecting';
    });
  }

  void _disconnect() {
    setState(() {
      _camera = null;
      _analysisJpeg = null;
      _status = 'disconnected';
    });
  }

  Future<void> _onFrame(FlutterAnycamFrame frame) async {
    _frames++;
    _analysisMesure.count();
    if (_status != 'receiving frames') {
      setState(() => _status = 'receiving frames');
    }
    if (!_showAnalysisThumb || _jpegBusy) return;
    _jpegBusy = true;
    final sw = Stopwatch()..start();
    try {
      final jpeg = await FlutterAnycam.frameConversor.convertToJpeg(
        frame: frame,
        quality: 70,
      );
      if (!mounted || _camera == null) return;
      _jpegMesure.count();
      setState(() {
        _analysisJpeg = jpeg;
        _lastJpegMs = sw.elapsedMilliseconds;
      });
    } catch (_) {
      if (mounted) {
        setState(() => _lastJpegMs = -1);
      }
    } finally {
      _jpegBusy = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    return ScenarioScaffold(
      title: 'RTSP',
      checklist: widget.checklist,
      statusLines: [
        'status=$_status',
        'analysisFps=$_analysisFps  max=$_maxAnalysisFps  target=$_targetFps',
        'frames=$_frames  jpegFps=$_jpegFps  jpegMs=$_lastJpegMs',
        'url=${_url.text}',
      ],
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(8),
            child: Column(
              children: [
                Align(
                  alignment: Alignment.centerLeft,
                  child: ActionChip(
                    avatar: const Icon(Icons.lan, size: 18),
                    label: const Text('Lab: 192.168.1.21:8554'),
                    onPressed: _useLabPreset,
                  ),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: _url,
                  decoration: const InputDecoration(
                    labelText: 'RTSP URL',
                    border: OutlineInputBorder(),
                    isDense: true,
                  ),
                ),
                const SizedBox(height: 8),
                Row(
                  children: [
                    Expanded(
                      child: TextField(
                        controller: _user,
                        decoration: const InputDecoration(
                          labelText: 'User (optional)',
                          border: OutlineInputBorder(),
                          isDense: true,
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: TextField(
                        controller: _pass,
                        obscureText: true,
                        decoration: const InputDecoration(
                          labelText: 'Password (optional)',
                          border: OutlineInputBorder(),
                          isDense: true,
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                Row(
                  children: [
                    const Text('Target FPS'),
                    const SizedBox(width: 8),
                    for (final fps in _fpsPresets) ...[
                      ChoiceChip(
                        label: Text('$fps'),
                        selected: _targetFps == fps,
                        onSelected: (_) {
                          setState(() {
                            _targetFps = fps;
                            _maxAnalysisFps = 0;
                          });
                        },
                      ),
                      const SizedBox(width: 4),
                    ],
                  ],
                ),
                const SizedBox(height: 4),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  dense: true,
                  title: const Text('Show analysis thumb (costs FPS)'),
                  value: _showAnalysisThumb,
                  onChanged: (v) {
                    setState(() {
                      _showAnalysisThumb = v;
                      if (!v) {
                        _analysisJpeg = null;
                        _jpegFps = 0;
                      }
                    });
                  },
                ),
                Row(
                  children: [
                    Expanded(
                      child: FilledButton(
                        onPressed: _connect,
                        child: const Text('Connect'),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: OutlinedButton(
                        onPressed: _disconnect,
                        child: const Text('Disconnect'),
                      ),
                    ),
                    const SizedBox(width: 8),
                    OutlinedButton(
                      onPressed: () => setState(_resetFpsStats),
                      child: const Text('Reset max'),
                    ),
                  ],
                ),
              ],
            ),
          ),
          Expanded(
            child: _camera == null
                ? const ColoredBox(
                    color: Colors.black,
                    child: Center(
                      child: Text(
                        'No active RTSP session',
                        style: TextStyle(color: Colors.white70),
                      ),
                    ),
                  )
                : Stack(
                    fit: StackFit.expand,
                    children: [
                      FlutterAnycamWidget(
                        key: ValueKey('${_camera!.id}|$_targetFps'),
                        camera: _camera!,
                        fps: _targetFps,
                        onFrame: _onFrame,
                      ),
                      if (_showAnalysisThumb)
                        Positioned(
                          top: 12,
                          right: 12,
                          child: AnalysisFrameThumb(jpeg: _analysisJpeg),
                        ),
                    ],
                  ),
          ),
        ],
      ),
    );
  }
}
