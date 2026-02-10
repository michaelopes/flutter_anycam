import 'dart:typed_data';

import 'flutter_anycam_platform_interface.dart';
import 'flutter_anycam_stream_listener.dart';

typedef FlutterAnycamH264StreamListener = void Function(Uint8List data);
typedef FlutterAnycamH264StreamDisposer = void Function();

class FlutterAnycamH264Stream {
  FlutterAnycamH264Stream._internal();
  static final I = FlutterAnycamH264Stream._internal();
  Future<FlutterAnycamH264StreamDisposer> register({
    required String cameraId,
    required int fps,
    required FlutterAnycamH264StreamListener listener,
  }) async {
    final result =
        await FlutterAnycamPlatform.instance.registerRawStream(cameraId, fps);
    if (result) {
      final disposer = FlutterAnycamPlatform.instance.addStreamListener(
        FlutterAnycamStreamListener(
          onVideoH264Frame: (data) {
            listener(data["h264"]);
          },
        ),
      );
      return () {
        FlutterAnycamPlatform.instance.disposeRawStream(cameraId);
        disposer();
      };
    }
    return () {};
  }
}
