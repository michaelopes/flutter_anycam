import 'dart:io';

import '../channel/flutter_anycam_platform_interface.dart';

typedef FlutterAnycamWebRtcCameraFeedDisposer = Future<void> Function();

class FlutterAnycamWebRtcCameraFeed {
  FlutterAnycamWebRtcCameraFeed._internal();

  static final I = FlutterAnycamWebRtcCameraFeed._internal();

  /// Binds a camera feed directly to a WebRTC stream on the native layer,
  /// avoiding Flutter bridge frame copies.
  Future<FlutterAnycamWebRtcCameraFeedDisposer> attach({
    required String cameraId,
    required int fps,
    required String streamId,
    bool alsoDeliverToFlutter = false,
  }) async {
    if (!Platform.isAndroid) {
      return () async {};
    }

    final result = await FlutterAnycamPlatform.instance.registerWebRtcCameraFeed(
      cameraId: cameraId,
      fps: fps,
      streamId: streamId,
      alsoDeliverToFlutter: alsoDeliverToFlutter,
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
