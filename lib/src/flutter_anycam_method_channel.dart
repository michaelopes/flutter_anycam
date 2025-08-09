import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_anycam/src/flutter_anycam_camera_selector.dart';

import 'flutter_anycam_platform_interface.dart';
import 'flutter_anycam_stream_listener.dart';
import 'flutter_anycam_typedefs.dart';

class MethodChannelFlutterAnycam extends FlutterAnycamPlatform {
  MethodChannelFlutterAnycam() {
    if (_eventStream == null) {
      _eventStream = eventChannel.receiveBroadcastStream();
      _eventStream?.listen((ldata) {
        final viewId = ldata["viewId"] as int?;
        final method = ldata["method"] as String?;
        final data = Map<String, dynamic>.from(ldata["data"] ?? {});
        if (viewId != null && method != null) {
          final listeners = _streamListeners
              .where((e) => e.viewId == viewId || e.viewId == -1);

          for (var listener in listeners) {
            final methods = {
              "onConnected": listener.onConnected,
              "onDisconnected": listener.onDisconnected,
              "onUnauthorized": listener.onUnauthorized,
              "onFailed": listener.onFailed,
              "onVideoFrameReceived": listener.onVideoFrameReceived,
            };
            if (methods[method] != null) {
              methods[method]!(data);
            }
          }
        }
      });
    }
  }

  Stream<dynamic>? _eventStream;
  final _streamListeners = <FlutterAnycamStreamListener>[];

  @visibleForTesting
  final methodChannel =
      const MethodChannel('br.dev.michaellopes.flutter_anycam/channel');

  @visibleForTesting
  final eventChannel =
      const EventChannel('br.dev.michaellopes.flutter_anycam/event');

  @override
  FlutterAnycamStreamListenerDisposer addStreamListener(
    FlutterAnycamStreamListener listener,
  ) {
    _streamListeners.add(listener);
    return () {
      _streamListeners.remove(listener);
    };
  }

  @override
  Future<List<FlutterAnycamCameraSelector>> availableCameras() async {
    final list = (await methodChannel.invokeMethod('availableCameras'));
    if (list != null && list.isNotEmpty) {
      return (list as List)
          .map(
            (e) => FlutterAnycamCameraSelector.fromMap(
              Map<String, dynamic>.from(e),
            ),
          )
          .toList();
    }
    return [];
  }
}
