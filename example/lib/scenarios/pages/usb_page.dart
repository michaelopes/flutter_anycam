import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_anycam/flutter_anycam.dart';

import '../lab_config.dart';
import '../widgets/analysis_frame_thumb.dart';
import '../widgets/scenario_scaffold.dart';

class UsbPage extends StatefulWidget {
  const UsbPage({
    super.key,
    required this.cameras,
    required this.checklist,
  });

  final List<FlutterAnycamCameraSelector> cameras;
  final List<String> checklist;

  @override
  State<UsbPage> createState() => _UsbPageState();
}

class _UsbPageState extends State<UsbPage> {
  final _analysisMesure = FlutterAnycamMesure(seconds: 1);
  final _jpegMesure = FlutterAnycamMesure(seconds: 1);

  List<FlutterAnycamCameraSelector> _usbCameras = const [];
  FlutterAnycamCameraSelector? _selected;
  FlutterAnycamCameraSelector? _connected;
  int _targetFps = 30;
  int _frames = 0;
  int _analysisFps = 0;
  int _maxAnalysisFps = 0;
  int _jpegFps = 0;
  String _status = 'plug a UVC device, then Refresh';
  Uint8List? _analysisJpeg;
  int _lastJpegMs = 0;
  bool _jpegBusy = false;
  bool _showAnalysisThumb = true;
  bool _refreshing = false;

  @override
  void initState() {
    super.initState();
    _usbCameras = widget.cameras
        .where((c) => c.lensFacing == FlutterAnycamLensFacing.usb)
        .toList();
    _selected = _usbCameras.isNotEmpty ? _usbCameras.first : null;
    if (_selected != null) {
      _status = 'USB listed — tap Connect';
    }
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
    super.dispose();
  }

  void _resetFpsStats() {
    _frames = 0;
    _analysisFps = 0;
    _maxAnalysisFps = 0;
    _jpegFps = 0;
    _lastJpegMs = 0;
  }

  Future<void> _refresh() async {
    setState(() {
      _refreshing = true;
      _status = 'refreshing availableCameras()';
    });
    try {
      final all = await FlutterAnycam.availableCameras();
      if (!mounted) return;
      final usb = all
          .where((c) => c.lensFacing == FlutterAnycamLensFacing.usb)
          .toList();
      setState(() {
        _usbCameras = usb;
        _selected = usb.any((c) => c.id == _selected?.id)
            ? usb.firstWhere((c) => c.id == _selected!.id)
            : (usb.isNotEmpty ? usb.first : null);
        _status = usb.isEmpty
            ? 'no USB camera — plug UVC and grant permission'
            : 'USB listed — tap Connect';
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _status = 'refresh failed: $e');
    } finally {
      if (mounted) setState(() => _refreshing = false);
    }
  }

  void _connect() {
    final camera = _selected;
    if (camera == null) {
      setState(() => _status = 'no USB camera selected — Refresh first');
      return;
    }
    setState(() {
      _connected = camera;
      _analysisJpeg = null;
      _resetFpsStats();
      _status = 'connecting';
    });
  }

  void _disconnect() {
    setState(() {
      _connected = null;
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
      if (!mounted || _connected == null) return;
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
      title: 'USB camera',
      checklist: widget.checklist,
      statusLines: [
        'status=$_status',
        'analysisFps=$_analysisFps  max=$_maxAnalysisFps  target=$_targetFps',
        'frames=$_frames  jpegFps=$_jpegFps  jpegMs=$_lastJpegMs',
        'usb=${_connected?.id ?? _selected?.id ?? "none"}',
      ],
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(8),
            child: Column(
              children: [
                Row(
                  children: [
                    ActionChip(
                      avatar: _refreshing
                          ? const SizedBox(
                              width: 14,
                              height: 14,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.usb, size: 18),
                      label: const Text('Refresh USB'),
                      onPressed: _refreshing ? null : _refresh,
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        _usbCameras.isEmpty
                            ? 'No USB device in availableCameras()'
                            : '${_usbCameras.length} USB device(s)',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ),
                  ],
                ),
                if (_usbCameras.isNotEmpty)
                  SizedBox(
                    height: 56,
                    child: ListView(
                      scrollDirection: Axis.horizontal,
                      children: [
                        for (final cam in _usbCameras)
                          Padding(
                            padding: const EdgeInsets.only(right: 8, top: 8),
                            child: ChoiceChip(
                              label: Text(
                                cam.name.isEmpty ? cam.id : cam.name,
                              ),
                              selected: _selected?.id == cam.id,
                              onSelected: (_) {
                                setState(() => _selected = cam);
                              },
                            ),
                          ),
                      ],
                    ),
                  ),
                const SizedBox(height: 8),
                Row(
                  children: [
                    const Text('Target FPS'),
                    const SizedBox(width: 8),
                    for (final fps in kLabFpsPresets) ...[
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
            child: _connected == null
                ? const ColoredBox(
                    color: Colors.black,
                    child: Center(
                      child: Text(
                        'No active USB session',
                        style: TextStyle(color: Colors.white70),
                      ),
                    ),
                  )
                : Stack(
                    fit: StackFit.expand,
                    children: [
                      FlutterAnycamWidget(
                        key: ValueKey('${_connected!.id}|$_targetFps'),
                        camera: _connected!,
                        fps: _targetFps,
                        preferredSize: const FlutterAnycamSize(640, 480),
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
