// ignore_for_file: public_member_api_docs, sort_constructors_first
import 'package:flutter/material.dart';

import 'package:flutter_anycam/flutter_anycam.dart';

class FlutterAnycamTfWidget extends StatefulWidget {
  const FlutterAnycamTfWidget({
    super.key,
    required this.camera,
    this.preferredSize = const FlutterAnycamSize(640, 480),
    this.enableDebug = false,
    this.texts = const FlutterAnycamTexts(),
    this.previewScale = 0,
    this.autoRetry = false,
    this.fps = 5,
    this.resizeFrame,
    this.filter = FlutterAnycamFilter.none,
    this.viewId,
    this.aspectRatio,
    this.onFrame,
    this.maxConcurrentFrames = 2,
    this.previewRotation,
  });

  final FlutterAnycamCameraSelector camera;
  final FlutterAnycamSize preferredSize;
  final FlutterAnycamSize? resizeFrame;
  final FlutterAnycamFilter filter;
  final int? viewId;
  final bool enableDebug;
  final FlutterAnycamTexts texts;
  final bool autoRetry;
  final int maxConcurrentFrames;

  final int fps;
  final double? aspectRatio;
  final double previewScale;
  final FlutterAnycamStreamTfFrameCallback? onFrame;
  final int? previewRotation;

  @override
  State<FlutterAnycamTfWidget> createState() => _FlutterAnycamTfWidgetState();
}

class _FlutterAnycamTfWidgetState extends State<FlutterAnycamTfWidget> {
  int _inProcess = 0;
  bool _disposed = false;

  bool get _hasReachedLimit => _inProcess >= widget.maxConcurrentFrames;

  void _startProcess() {
    _inProcess++;
  }

  void _stopProcess() {
    if (_inProcess > 0) {
      _inProcess--;
    }
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return FlutterAnycamWidget(
      camera: widget.camera,
      preferredSize: widget.preferredSize,
      resizeFrame: widget.resizeFrame,
      filter: widget.filter,
      viewId: widget.viewId,
      enableDebug: widget.enableDebug,
      texts: widget.texts,
      autoRetry: widget.autoRetry,
      fps: widget.fps,
      aspectRatio: widget.aspectRatio,
      previewScale: widget.previewScale,
      type: FlutterAnycamType.tf,
      previewRotation: widget.previewRotation,
      onRawVideoFrameReceived: (data) async {
        final id = data["id"];
        if (id is! String) {
          return;
        }

        final frame = FlutterAnycamTfFrame.fromMap(data);
        final onFrame = widget.onFrame;
        // A frame nobody consumes must be closed here: native memory is only
        // released by close().
        if (_disposed || onFrame == null || _hasReachedLimit) {
          await frame.close();
          return;
        }

        _startProcess();
        try {
          await onFrame(frame);
        } catch (e, s) {
          FlutterError.reportError(FlutterErrorDetails(
            exception: e,
            stack: s,
            library: 'flutter_anycam',
            context: ErrorDescription('while handling a TF camera frame'),
          ));
          await frame.close();
        } finally {
          _stopProcess();
        }
      },
    );
  }
}
