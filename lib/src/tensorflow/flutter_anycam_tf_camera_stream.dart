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

    // Every headless stream shares one event view id: only events of this camera belong here.
    bool isMine(Map<String, dynamic> data) {
      final id = data['cameraId'];
      return id == null || id == camera.id;
    }

    // Registered before the native stream starts so its first events are not lost.
    final removeListener = FlutterAnycamPlatform.instance.addStreamListener(
      FlutterAnycamStreamListener(
        viewId: kTfCameraStreamViewId,
        onTfCameraStreamConnected: (data) {
          if (!isMine(data)) return;
          onConnected?.call(
            cameraId: data['cameraId'] as String? ?? camera.id,
            width: (data['width'] as num?)?.toInt() ?? 0,
            height: (data['height'] as num?)?.toInt() ?? 0,
          );
        },
        onTfCameraStreamFrame: (data) {
          if (!isMine(data)) return;
          final id = data['id'];
          if (id is! String) return;
          final FlutterAnycamTfFrame frame;
          try {
            frame = FlutterAnycamTfFrame.fromMap(data);
          } catch (_) {
            FlutterAnycamPlatform.instance
                .closeTfFrame(frameId: id)
                .catchError((_) => false);
            return;
          }
          // Native memory is only released by close(): a frame nobody takes is closed here.
          if (onFrame == null) {
            frame.close();
            return;
          }
          try {
            onFrame(frame);
          } catch (_) {
            frame.close();
            rethrow;
          }
        },
        onTfCameraStreamFailed: (data) {
          if (!isMine(data)) return;
          onFailed?.call(
            cameraId: data['cameraId'] as String? ?? camera.id,
            message: data['message'] as String? ?? 'unknown',
          );
        },
      ),
    );

    final bool result;
    try {
      result = await FlutterAnycamPlatform.instance.registerTfCameraStream(
        cameraId: camera.id,
        fps: fps,
        preferredSize: preferredSize,
        filter: filter,
        camera: camera,
      );
    } catch (_) {
      removeListener();
      rethrow;
    }

    if (!result) {
      removeListener();
      return () async {};
    }

    var disposed = false;
    return () async {
      if (disposed) return;
      disposed = true;
      removeListener();
      try {
        await FlutterAnycamPlatform.instance.disposeTfCameraStream(camera.id);
      } catch (_) {}
    };
  }
}
