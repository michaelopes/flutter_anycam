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

  Future<dynamic> notify(dynamic data) async {
    final map = Map<String, dynamic>.from(data);
    final modelKey = map["modelKey"];
    final listener = _listeners[modelKey];
    if (listener != null) {
      return listener(map["output"]);
    }
  }

  TfOutputListenerDisposer addListener({
    required String modelKey,
    required TfOutputListener listener,
  }) {
    if (!_listeners.containsKey(modelKey)) {
      _listeners[modelKey] = listener;
    }
    return () {
      _listeners.remove(modelKey);
    };
  }
}
