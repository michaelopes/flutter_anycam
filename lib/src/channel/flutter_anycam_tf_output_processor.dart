import 'package:flutter/services.dart';

typedef TfOutputListener = Future<dynamic> Function(dynamic data);
typedef TfOutputListenerDisposer = void Function();

class FlutterAnycamTfOutputProcessor {
  FlutterAnycamTfOutputProcessor._();

  static final I = FlutterAnycamTfOutputProcessor._();

  final _listeners = <String, TfOutputListener>{};

  void setChannel(MethodChannel channel) {
    channel.setMethodCallHandler(_call);
  }

  Future<dynamic> _call(MethodCall payload) async {
    if (payload.method == "processTfOutput") {
      return notify(payload.arguments);
    }
  }

  /// Returns the processed outputs, or an empty list when nobody listens for the model
  /// (e.g. the session was disposed while an inference was in flight).
  Future<dynamic> notify(dynamic data) async {
    if (data is! Map) return const [];
    final map = Map<String, dynamic>.from(data);
    final listener = _listeners[map["modelKey"]];
    if (listener == null) return const [];
    return listener(map["output"]);
  }

  /// The latest session for a model key wins; disposing an older one does not remove it.
  TfOutputListenerDisposer addListener({
    required String modelKey,
    required TfOutputListener listener,
  }) {
    _listeners[modelKey] = listener;
    return () {
      if (identical(_listeners[modelKey], listener)) {
        _listeners.remove(modelKey);
      }
    };
  }
}
