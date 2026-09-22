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

/// Built-in / logical cameras (excludes USB and RTSP).
List<FlutterAnycamCameraSelector> deviceCameras(
  List<FlutterAnycamCameraSelector> cameras,
) {
  return cameras
      .where(
        (c) =>
            c.lensFacing == FlutterAnycamLensFacing.back ||
            c.lensFacing == FlutterAnycamLensFacing.front ||
            c.lensFacing == FlutterAnycamLensFacing.unknown,
      )
      .toList();
}

int? numericCameraId(String id) => int.tryParse(id);

bool hasHighCameraId(FlutterAnycamCameraSelector camera) {
  final n = numericCameraId(camera.id);
  return n != null && n >= 100;
}

/// Two cameras for dual / switch scenarios.
///
/// 1. Classic back + front when both exist and ids differ
/// 2. Else two device cameras with distinct ids (prefers id >= 100,
///    common on multi-cam / external adapter devices without front/back)
({FlutterAnycamCameraSelector primary, FlutterAnycamCameraSelector secondary})?
    pickDualCameras(List<FlutterAnycamCameraSelector> cameras) {
  final back = firstOfFacing(cameras, FlutterAnycamLensFacing.back);
  final front = firstOfFacing(cameras, FlutterAnycamLensFacing.front);
  if (back != null && front != null && back.id != front.id) {
    return (primary: back, secondary: front);
  }

  final device = deviceCameras(cameras);
  if (device.length < 2) return null;

  final highId = device.where(hasHighCameraId).toList();
  if (highId.length >= 2) {
    return (primary: highId[0], secondary: highId[1]);
  }

  if (highId.isNotEmpty) {
    for (final other in device) {
      if (other.id != highId.first.id) {
        return (primary: highId.first, secondary: other);
      }
    }
  }

  // Same facing (e.g. two "back") differentiated only by id.
  for (var i = 0; i < device.length; i++) {
    for (var j = i + 1; j < device.length; j++) {
      if (device[i].id != device[j].id) {
        return (primary: device[i], secondary: device[j]);
      }
    }
  }
  return null;
}
