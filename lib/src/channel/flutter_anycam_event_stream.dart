import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

typedef FlutterAnycamEventStreamListener = void Function(dynamic event);

final class FlutterAnycamEventStream {
  FlutterAnycamEventStream._() {
    final stream = _eventChannel.receiveBroadcastStream();
    stream.listen(
      _listen,
      onError: (Object error, StackTrace stack) {
        debugPrint("FlutterAnycam event channel error: $error");
      },
      cancelOnError: false,
    );
  }

  static final I = FlutterAnycamEventStream._();

  final _eventChannel =
      const EventChannel('br.dev.michaellopes.flutter_anycam/event');

  final _listeners = <FlutterAnycamEventStreamListener>[];

  void _listen(dynamic data) {
    for (final listener in List.of(_listeners)) {
      listener(data);
    }
  }

  void add(FlutterAnycamEventStreamListener listener) {
    if (!_listeners.contains(listener)) {
      _listeners.add(listener);
    }
  }

  void remove(FlutterAnycamEventStreamListener listener) {
    _listeners.remove(listener);
  }
}
