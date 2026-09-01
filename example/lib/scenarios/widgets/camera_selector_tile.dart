import 'package:flutter/material.dart';
import 'package:flutter_anycam/flutter_anycam.dart';

class CameraSelectorTile extends StatelessWidget {
  const CameraSelectorTile({
    super.key,
    required this.camera,
    this.selected = false,
    this.onTap,
  });

  final FlutterAnycamCameraSelector camera;
  final bool selected;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      selected: selected,
      onTap: onTap,
      leading: Icon(_iconFor(camera.lensFacing)),
      title: Text(camera.name.isEmpty ? camera.id : camera.name),
      subtitle: Text(
        '${camera.lensFacing.name} · id=${camera.id} · ori=${camera.sensorOrientation}',
      ),
      trailing: selected ? const Icon(Icons.check) : null,
    );
  }

  IconData _iconFor(FlutterAnycamLensFacing facing) {
    switch (facing) {
      case FlutterAnycamLensFacing.front:
        return Icons.camera_front;
      case FlutterAnycamLensFacing.back:
        return Icons.camera_rear;
      case FlutterAnycamLensFacing.usb:
        return Icons.usb;
      case FlutterAnycamLensFacing.rtsp:
        return Icons.videocam;
      case FlutterAnycamLensFacing.unknown:
        return Icons.camera;
    }
  }
}

FlutterAnycamCameraSelector? firstOfFacing(
  List<FlutterAnycamCameraSelector> cameras,
  FlutterAnycamLensFacing facing,
) {
  for (final c in cameras) {
    if (c.lensFacing == facing) return c;
  }
  return null;
}
