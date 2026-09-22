import 'dart:io';

import '../channel/flutter_anycam_platform_interface.dart';
import '../core/flutter_anycam_size.dart';

typedef FlutterAnycamWebRtcCameraFeedDisposer = Future<void> Function();

class FlutterAnycamWebRtcCameraFeed {
  FlutterAnycamWebRtcCameraFeed._internal();

  static final I = FlutterAnycamWebRtcCameraFeed._internal();

  /// Binds a camera feed to all connected WebRTC peers on the native layer.
  ///
  /// Only one active attach is allowed at a time. Subsequent calls return
  /// a no-op disposer until [disposeWebRtcCameraFeed] is called.
  Future<FlutterAnycamWebRtcCameraFeedDisposer> attach({
    required String cameraId,
    required int fps,
    bool alsoDeliverToFlutter = false,
    FlutterAnycamSize? streamSize,
  }) async {
    if (!Platform.isAndroid) {
      return () async {};
    }

    final result = await FlutterAnycamPlatform.instance.registerWebRtcCameraFeed(
      cameraId: cameraId,
      fps: fps,
      alsoDeliverToFlutter: alsoDeliverToFlutter,
      streamSize: streamSize,
    );

    if (!result) {
      return () async {};
    }

    return () async {
      await FlutterAnycamPlatform.instance.disposeWebRtcCameraFeed(cameraId);
    };
  }

  Future<void> detach({required String cameraId}) async {
    if (!Platform.isAndroid) {
      return;
    }
    await FlutterAnycamPlatform.instance.disposeWebRtcCameraFeed(cameraId);
  }
}
