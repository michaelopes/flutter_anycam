import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_anycam/flutter_anycam.dart';
import 'package:flutter_test/flutter_test.dart';

const kMethodChannel = MethodChannel('br.dev.michaellopes.flutter_anycam/channel');
const kEventChannel = EventChannel('br.dev.michaellopes.flutter_anycam/event');

typedef FakeHandler = FutureOr<Object?> Function(MethodCall call);

/// Simulates the native side of the plugin: records every method call and
/// pushes events through the real EventChannel codec path.
class FakeNative {
  FakeNative._();

  static final FakeNative I = FakeNative._();

  final List<MethodCall> calls = <MethodCall>[];
  final Map<String, FakeHandler> handlers = <String, FakeHandler>{};
  MockStreamHandlerEventSink? _sink;
  bool _installed = false;

  void install() {
    if (_installed) return;
    _installed = true;
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockStreamHandler(
      kEventChannel,
      MockStreamHandler.inline(
        onListen: (_, sink) => _sink = sink,
        onCancel: (_) => _sink = null,
      ),
    );
    messenger.setMockMethodCallHandler(kMethodChannel, (call) async {
      calls.add(call);
      final handler = handlers[call.method];
      if (handler != null) return handler(call);
      return _defaults(call);
    });
  }

  Object? _defaults(MethodCall call) {
    switch (call.method) {
      case 'createView':
        return 7;
      case 'disposeView':
      case 'closeTfFrame':
      case 'closeTfInferenceResult':
        return true;
    }
    return null;
  }

  void reset() {
    calls.clear();
    handlers.clear();
  }

  Iterable<MethodCall> callsOf(String method) =>
      calls.where((c) => c.method == method);

  /// Sends an event exactly like the native EventChannel does.
  void emit(int viewId, String method, [Map<String, Object?>? data]) {
    final sink = _sink;
    if (sink == null) {
      throw StateError('EventChannel has no listener');
    }
    sink.success(<String, Object?>{
      'viewId': viewId,
      'method': method,
      'data': data ?? <String, Object?>{},
    });
  }

  void emitError() => _sink?.error(code: 'native', message: 'boom');

  /// Synchronous variant for widget tests: EventChannel messages are delivered
  /// on the real event loop, which `tester.pump()` does not drive.
  void dispatch(int viewId, String method, [Map<String, Object?>? data]) {
    (FlutterAnycamPlatform.instance as MethodChannelFlutterAnycam)
        .dispatchEvent(<String, Object?>{
      'viewId': viewId,
      'method': method,
      'data': data ?? <String, Object?>{},
    });
  }
}
