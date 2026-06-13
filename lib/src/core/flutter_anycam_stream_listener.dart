import 'flutter_anycam_typedefs.dart';

class FlutterAnycamStreamListener {
  final int viewId;
  final FlutterAnycamStreamMethod? onConnected;
  final FlutterAnycamStreamMethod? onDisconnected;
  final FlutterAnycamStreamMethod? onUnauthorized;
  final FlutterAnycamStreamMethod? onFailed;
  final FlutterAnycamStreamMethod? onVideoFrameReceived;
  final FlutterAnycamStreamMethod? onCameraRawFrame;
  final FlutterAnycamStreamMethod? onTfCameraStreamConnected;
  final FlutterAnycamStreamMethod? onTfCameraStreamFrame;
  final FlutterAnycamStreamMethod? onTfCameraStreamFailed;

  FlutterAnycamStreamListener({
    this.viewId = -1,
    this.onConnected,
    this.onDisconnected,
    this.onUnauthorized,
    this.onFailed,
    this.onVideoFrameReceived,
    this.onCameraRawFrame,
    this.onTfCameraStreamConnected,
    this.onTfCameraStreamFrame,
    this.onTfCameraStreamFailed,
  });
}
