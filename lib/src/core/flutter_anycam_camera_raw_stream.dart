import 'dart:io';

import '../channel/flutter_anycam_platform_interface.dart';
import 'flutter_anycam_frame.dart';
import 'flutter_anycam_stream_listener.dart';

typedef FlutterAnycamCameraRawStreamListener = void Function(
  FlutterAnycamFrame frame,
);
typedef FlutterAnycamCameraRawStreamDisposer = void Function();

class FlutterAnycamCameraRawStream {
  FlutterAnycamCameraRawStream._internal();
  static final I = FlutterAnycamCameraRawStream._internal();

  String currentCameraId = "1";

  Future<FlutterAnycamCameraRawStreamDisposer> register({
    required String cameraId,
    required int fps,
    required FlutterAnycamCameraRawStreamListener listener,
  }) async {
    if (Platform.isAndroid) {
      final result =
          await FlutterAnycamPlatform.instance.registerRawStream(cameraId, fps);
      if (result) {
        final disposer = FlutterAnycamPlatform.instance.addStreamListener(
          FlutterAnycamStreamListener(
            viewId: -2,
            onCameraRawFrame: (data) {
              final frame = FlutterAnycamFrame.fromMap(data);
              listener(frame);
            },
          ),
        );
        return () {
          FlutterAnycamPlatform.instance.disposeRawStream(cameraId);
          disposer();
        };
      }
    }
    return () {};
  }
}
