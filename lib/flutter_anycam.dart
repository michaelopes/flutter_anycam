export './src/core/flutter_anycam_camera_selector.dart';
export './src/core/flutter_anycam_typedefs.dart';
export './src/core/flutter_anycam_stream_listener.dart';
export './src/core/flutter_anycam_widget.dart';
export './src/core/flutter_anycam_frame.dart';
export './src/core/flutter_anycam_size.dart';
export './src/core/flutter_anycam_crop.dart';
export './src/core/flutter_anycam_texts.dart';
export './src/channel/flutter_anycam_platform_interface.dart';
export './src/channel/flutter_anycam_method_channel.dart';
export 'src/core/flutter_anycam_camera_raw_stream.dart';
export 'src/core/flutter_anycam_mesure.dart';
export 'src/core/flutter_anycam_filter.dart';

import 'src/core/flutter_anycam_camera_selector.dart';

import 'src/core/flutter_anycam_image_conversor.dart';
import 'src/channel/flutter_anycam_platform_interface.dart';

class FlutterAnycam {
  static final frameConversor = FlutterAnycamFrameConversor();
  static Future<List<FlutterAnycamCameraSelector>> availableCameras() {
    return FlutterAnycamPlatform.instance.availableCameras();
  }

  static Future<void> enableFlash() async {
    FlutterAnycamPlatform.instance.setFlash(true);
  }

  static Future<void> disableFlash() async {
    FlutterAnycamPlatform.instance.setFlash(false);
  }

  static Future<void> setZoom({
    required FlutterAnycamCameraSelector camera,
    double zoom = 1.0,
  }) async {
    if (camera.lensFacing == FlutterAnycamLensFacing.usb ||
        camera.lensFacing == FlutterAnycamLensFacing.back ||
        camera.lensFacing == FlutterAnycamLensFacing.front) {
      if (zoom < 1) {
        zoom = 1;
      }
      await FlutterAnycamPlatform.instance.setZoom(zoom, camera.id);
    }
  }
}
