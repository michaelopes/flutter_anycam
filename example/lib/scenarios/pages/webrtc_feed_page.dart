import 'package:flutter/material.dart';
import 'package:flutter_anycam/flutter_anycam.dart';

import '../widgets/camera_selector_tile.dart';
import '../widgets/scenario_scaffold.dart';

/// Attaches native WebRTC camera feed without a remote peer (signaling stub).
class WebRtcFeedPage extends StatefulWidget {
  const WebRtcFeedPage({
    super.key,
    required this.cameras,
    required this.checklist,
  });

  final List<FlutterAnycamCameraSelector> cameras;
  final List<String> checklist;

  @override
  State<WebRtcFeedPage> createState() => _WebRtcFeedPageState();
}

class _WebRtcFeedPageState extends State<WebRtcFeedPage> {
  FlutterAnycamWebRtcCameraFeedDisposer? _feedDisposer;
  FlutterAnycamWebRtcStream? _stream;
  bool _attached = false;
  String _status = 'idle';
  int _previewFrames = 0;

  late final FlutterAnycamCameraSelector? _camera;

  @override
  void initState() {
    super.initState();
    _camera = firstOfFacing(widget.cameras, FlutterAnycamLensFacing.back) ??
        (widget.cameras.isNotEmpty ? widget.cameras.first : null);
  }

  Future<void> _attach() async {
    final camera = _camera;
    if (camera == null) return;
    setState(() => _status = 'creating stream');
    _stream ??= await FlutterAnycamWebRtc.I.newStream(
      callbacks: FlutterAnycamWebRtcCallbacks(
        onConnectedCallback: () {
          if (mounted) setState(() => _status = 'webrtc connected');
        },
        onDisconnectedCallback: () {
          if (mounted) setState(() => _status = 'webrtc disconnected');
        },
        onCandidateCallback: (_) {},
      ),
    );
    setState(() => _status = 'attaching feed');
    _feedDisposer = await FlutterAnycamWebRtcCameraFeed.I.attach(
      cameraId: camera.id,
      fps: 10,
      alsoDeliverToFlutter: false,
      streamSize: const FlutterAnycamSize(640, 480),
    );
    if (mounted) {
      setState(() {
        _attached = true;
        _status = 'feed attached (no remote peer)';
      });
    }
  }

  Future<void> _detach() async {
    await _feedDisposer?.call();
    _feedDisposer = null;
    await _stream?.dispose();
    _stream = null;
    if (mounted) {
      setState(() {
        _attached = false;
        _status = 'detached';
      });
    }
  }

  @override
  void dispose() {
    _feedDisposer?.call();
    _stream?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ScenarioScaffold(
      title: 'WebRTC camera feed',
      checklist: widget.checklist,
      statusLines: [
        'status=$_status',
        'attached=$_attached',
        'previewFrames=$_previewFrames',
        'streamId=${_stream?.id ?? "-"}',
      ],
      body: _camera == null
          ? const Center(child: Text('No cameras'))
          : FlutterAnycamWidget(
              camera: _camera,
              fps: 5,
              onFrame: (_) => setState(() => _previewFrames++),
            ),
      bottomBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(8),
          child: Row(
            children: [
              Expanded(
                child: FilledButton(
                  onPressed: _attached ? null : _attach,
                  child: const Text('Attach feed'),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: OutlinedButton(
                  onPressed: _attached ? _detach : null,
                  child: const Text('Detach'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
