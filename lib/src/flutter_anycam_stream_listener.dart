import 'flutter_anycam_typedefs.dart';

class FlutterAnycamStreamListener {
  final int viewId;
  final FlutterAnycamStreamMethod? onConnected;
  final FlutterAnycamStreamMethod? onDisconnected;
  final FlutterAnycamStreamMethod? onUnauthorized;
  final FlutterAnycamStreamMethod? onFailed;
  final FlutterAnycamStreamMethod? onVideoFrameReceived;

  FlutterAnycamStreamListener({
    this.viewId = -1,
    this.onConnected,
    this.onDisconnected,
    this.onUnauthorized,
    this.onFailed,
    this.onVideoFrameReceived,
  });
}
