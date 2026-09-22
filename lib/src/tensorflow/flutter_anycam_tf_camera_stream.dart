import 'dart:io';

import '../channel/flutter_anycam_platform_interface.dart';
import '../core/flutter_anycam_camera_selector.dart';
import '../core/flutter_anycam_filter.dart';
import '../core/flutter_anycam_size.dart';
import '../core/flutter_anycam_stream_listener.dart';
import 'flutter_anycam_tf_frame.dart';

/// Event channel viewId used by headless Tf camera streams.
const kTfCameraStreamViewId = -3;

typedef FlutterAnycamTfCameraStreamListener = void Function(
  FlutterAnycamTfFrame frame,
);
typedef FlutterAnycamTfCameraStreamConnectedListener = void Function({
  required String cameraId,
  required int width,
  required int height,
});
typedef FlutterAnycamTfCameraStreamFailedListener = void Function({
  required String cameraId,
  required String message,
});
typedef FlutterAnycamTfCameraStreamDisposer = Future<void> Function();

class FlutterAnycamTfCameraStream {
  FlutterAnycamTfCameraStream._internal();
  static final I = FlutterAnycamTfCameraStream._internal();

  Future<FlutterAnycamTfCameraStreamDisposer> register({
    required FlutterAnycamCameraSelector camera,
    int fps = 5,
    FlutterAnycamSize preferredSize = const FlutterAnycamSize(640, 480),
    FlutterAnycamFilter filter = FlutterAnycamFilter.none,
    FlutterAnycamTfCameraStreamListener? onFrame,
    FlutterAnycamTfCameraStreamConnectedListener? onConnected,
    FlutterAnycamTfCameraStreamFailedListener? onFailed,
  }) async {
    if (!Platform.isAndroid) {
      return () async {};
    }

    final result = await FlutterAnycamPlatform.instance.registerTfCameraStream(
      cameraId: camera.id,
      fps: fps,
      preferredSize: preferredSize,
      filter: filter,
      camera: camera,
    );

    if (!result) {
      return () async {};
    }

    final disposer = FlutterAnycamPlatform.instance.addStreamListener(
      FlutterAnycamStreamListener(
        viewId: kTfCameraStreamViewId,
        onTfCameraStreamConnected: (data) {
          onConnected?.call(
            cameraId: data['cameraId'] as String? ?? camera.id,
            width: (data['width'] as num).toInt(),
            height: (data['height'] as num).toInt(),
          );
        },
        onTfCameraStreamFrame: (data) {
          onFrame?.call(FlutterAnycamTfFrame.fromMap(data));
        },
        onTfCameraStreamFailed: (data) {
          onFailed?.call(
            cameraId: data['cameraId'] as String? ?? camera.id,
            message: data['message'] as String? ?? 'unknown',
          );
        },
      ),
    );

    return () async {
      await FlutterAnycamPlatform.instance.disposeTfCameraStream(camera.id);
      disposer();
    };
  }
}
