export './src/flutter_anycam_camera_selector.dart';
export './src/flutter_anycam_typedefs.dart';
export './src/flutter_anycam_stream_listener.dart';
export './src/flutter_anycam_widget.dart';
export './src/flutter_anycam_frame.dart';

import 'src/flutter_anycam_camera_selector.dart';
import 'src/flutter_anycam_image_conversor.dart';
import 'src/flutter_anycam_platform_interface.dart';

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
}
