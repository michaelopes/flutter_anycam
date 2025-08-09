export './src/flutter_anycam_camera_selector.dart';
export './src/flutter_anycam_typedefs.dart';
export './src/flutter_anycam_stream_listener.dart';
export './src/flutter_anycam_widget.dart';
export './src/flutter_anycam_frame.dart';

import 'src/flutter_anycam_camera_selector.dart';
import 'src/flutter_anycam_platform_interface.dart';

class FlutterAnycam {
  static Future<List<FlutterAnycamCameraSelector>> availableCameras() {
    return FlutterAnycamPlatform.instance.availableCameras();
  }
}
