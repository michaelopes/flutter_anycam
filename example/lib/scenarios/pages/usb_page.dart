import 'package:flutter/material.dart';
import 'package:flutter_anycam/flutter_anycam.dart';

import '../widgets/camera_selector_tile.dart';
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
  int _frames = 0;

  @override
  Widget build(BuildContext context) {
    final usb = firstOfFacing(widget.cameras, FlutterAnycamLensFacing.usb);
    return ScenarioScaffold(
      title: 'USB camera',
      checklist: widget.checklist,
      statusLines: [
        'usb=${usb?.id ?? "none"}',
        'frames=$_frames',
        'Plug a UVC device and reopen this scenario if missing',
      ],
      body: usb == null
          ? const Center(
              child: Text('No USB camera in availableCameras()'),
            )
          : FlutterAnycamWidget(
              camera: usb,
              fps: 8,
              onFrame: (_) => setState(() => _frames++),
            ),
    );
  }
}
