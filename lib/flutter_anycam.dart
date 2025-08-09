export './src/flutter_anycam_camera_selector.dart';
export './src/flutter_anycam_typedefs.dart';
export './src/flutter_anycam_stream_listener.dart';
export './src/flutter_anycam_widget.dart';
export './src/flutter_anycam_frame.dart';

import 'dart:typed_data';

import 'src/flutter_anycam_camera_selector.dart';
import 'src/flutter_anycam_platform_interface.dart';

class FlutterAnycam {
  static Future<List<FlutterAnycamCameraSelector>> availableCameras() {
    return FlutterAnycamPlatform.instance.availableCameras();
  }

  static Future<Uint8List?> convertNv21ToJpeg({
    required Uint8List bytes,
    required int width,
    required int height,
    required int rotation,
    int quality = 100,
  }) {
    return FlutterAnycamPlatform.instance.convertNv21ToJpeg(
      bytes: bytes,
      width: width,
      height: height,
      rotation: rotation,
      quality: quality,
    );
  }
}
