import 'flutter_anycam_camera_selector.dart';
import 'flutter_anycam_platform_interface.dart';
import 'flutter_anycam_size.dart';

class FlutterAnycamViewFactory {
  Future<int?> createView(
      {required int viewId,
      required FlutterAnycamCameraSelector camera,
      required FlutterAnycamSize preferredSize,
      required int fps}) {
    return FlutterAnycamPlatform.instance.createView({
      "viewId": viewId,
      "cameraSelector": camera.toMap(),
      "preferredSize": preferredSize.toMap(),
      "fps": fps,
    });
  }

  Future<bool> disposeView(int viewId) {
    return FlutterAnycamPlatform.instance.disposeView({
      "viewId": viewId,
    });
  }
}
