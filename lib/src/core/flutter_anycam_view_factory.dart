import '../channel/flutter_anycam_platform_interface.dart';
import 'flutter_anycam_camera_selector.dart';
import 'flutter_anycam_size.dart';
import 'flutter_anycam_type.dart';

class FlutterAnycamViewFactory {
  Future<int?> createView({
    required int viewId,
    required FlutterAnycamCameraSelector camera,
    required FlutterAnycamSize preferredSize,
    required int fps,
    required int filter,
    required FlutterAnycamSize? resizeFrame,
    required FlutterAnycamType type,
  }) {
    return FlutterAnycamPlatform.instance.createView({
      "viewId": viewId,
      "cameraSelector": camera.toMap(),
      "preferredSize": preferredSize.toMap(),
      "resizeFrame": resizeFrame?.toMap(),
      "filter": filter,
      "fps": fps,
      "type": type.value,
    });
  }

  Future<bool> disposeView(int viewId) {
    return FlutterAnycamPlatform.instance.disposeView({
      "viewId": viewId,
    });
  }
}
