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
  });

  final FlutterAnycamCameraSelector camera;
  final FlutterAnycamSize preferredSize;
  final FlutterAnycamSize? resizeFrame;
  final FlutterAnycamFilter filter;
  final int? viewId;
  final bool enableDebug;
  final FlutterAnycamTexts texts;
  final bool autoRetry;

  final int fps;
  final double? aspectRatio;
  final double previewScale;
  final FlutterAnycamStreamTfFrameCallback? onFrame;

  @override
  State<FlutterAnycamTfWidget> createState() => _FlutterAnycamTfWidgetState();
}

class _FlutterAnycamTfWidgetState extends State<FlutterAnycamTfWidget> {
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
      onRawVideoFrameReceived: (data) {
        if (data.entries.isNotEmpty) {
          widget.onFrame?.call(FlutterAnycamTfFrame.fromMap(data));
        }
      },
    );
  }
}
