import 'package:flutter/services.dart';

typedef FlutterAnycamEventStreamListener = void Function(dynamic event);

final class FlutterAnycamEventStream {
  FlutterAnycamEventStream._() {
    final stream = _eventChannel.receiveBroadcastStream();
    stream.listen(_listen);
  }

  static final I = FlutterAnycamEventStream._();

  final _eventChannel =
      const EventChannel('br.dev.michaellopes.flutter_anycam/event');

  final _listeners = <FlutterAnycamEventStreamListener>[];

  void _listen(data) {
    for (var listener in _listeners) {
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
