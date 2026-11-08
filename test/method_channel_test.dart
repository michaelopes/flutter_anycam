import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_anycam/flutter_anycam.dart';
import 'package:flutter_anycam/src/channel/flutter_anycam_tf_output_processor.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_native.dart';

Future<void> _flush() => Future<void>.delayed(Duration.zero);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final native = FakeNative.I;

  setUpAll(() async {
    native.install();
    expect(FlutterAnycamPlatform.instance, isA<MethodChannelFlutterAnycam>());
    await _flush();
  });

  setUp(native.reset);

  group('event dispatch', () {
    test('routes events through the real EventChannel to the matching view',
        () async {
      final received = <Map<String, dynamic>>[];
      final others = <Map<String, dynamic>>[];
      final disposeA = FlutterAnycamPlatform.instance.addStreamListener(
        FlutterAnycamStreamListener(viewId: 1, onConnected: received.add),
      );
      final disposeB = FlutterAnycamPlatform.instance.addStreamListener(
        FlutterAnycamStreamListener(viewId: 2, onConnected: others.add),
      );
      addTearDown(disposeA);
      addTearDown(disposeB);

      native.emit(1, 'onConnected', {'width': 640, 'height': 480});
      await _flush();

      expect(received, hasLength(1));
      expect(received.single['width'], 640);
      expect(others, isEmpty);
    });

    test('a global listener (-1) receives every view', () async {
      final received = <Map<String, dynamic>>[];
      final dispose = FlutterAnycamPlatform.instance.addStreamListener(
        FlutterAnycamStreamListener(onFailed: received.add),
      );
      addTearDown(dispose);

      native.emit(10, 'onFailed', {'message': 'a'});
      native.emit(11, 'onFailed', {'message': 'b'});
      await _flush();

      expect(received.map((e) => e['message']), ['a', 'b']);
    });

    test('malformed events are ignored instead of throwing', () async {
      final received = <Map<String, dynamic>>[];
      final dispose = FlutterAnycamPlatform.instance.addStreamListener(
        FlutterAnycamStreamListener(onConnected: received.add),
      );
      addTearDown(dispose);
      final platform = FlutterAnycamPlatform.instance as MethodChannelFlutterAnycam;

      platform.dispatchEvent(null);
      platform.dispatchEvent('text');
      platform.dispatchEvent({'viewId': '1', 'method': 'onConnected'});
      platform.dispatchEvent({'viewId': 1});
      platform.dispatchEvent({'viewId': 1, 'method': 'onConnected', 'data': 3});

      expect(received, hasLength(1));
      expect(received.single, isEmpty);
    });

    test('a native stream error does not cancel the event stream', () async {
      final received = <Map<String, dynamic>>[];
      final dispose = FlutterAnycamPlatform.instance.addStreamListener(
        FlutterAnycamStreamListener(viewId: 3, onConnected: received.add),
      );
      addTearDown(dispose);

      native.emitError();
      await _flush();
      native.emit(3, 'onConnected');
      await _flush();

      expect(received, hasLength(1));
    });

    test('a throwing listener does not stop the others', () async {
      final errors = <FlutterErrorDetails>[];
      final previous = FlutterError.onError;
      FlutterError.onError = errors.add;
      addTearDown(() => FlutterError.onError = previous);

      final received = <Map<String, dynamic>>[];
      final disposeA = FlutterAnycamPlatform.instance.addStreamListener(
        FlutterAnycamStreamListener(
          viewId: 4,
          onConnected: (_) => throw StateError('listener bug'),
        ),
      );
      final disposeB = FlutterAnycamPlatform.instance.addStreamListener(
        FlutterAnycamStreamListener(viewId: 4, onConnected: received.add),
      );
      addTearDown(disposeA);
      addTearDown(disposeB);

      native.emit(4, 'onConnected');
      await _flush();

      expect(received, hasLength(1));
      expect(errors, hasLength(1));
      expect(errors.single.exception, isA<StateError>());
    });

    test('a listener may remove itself and others while being dispatched',
        () async {
      final calls = <String>[];
      late FlutterAnycamStreamListenerDisposer disposeB;
      late FlutterAnycamStreamListenerDisposer disposeA;
      disposeA = FlutterAnycamPlatform.instance.addStreamListener(
        FlutterAnycamStreamListener(
          viewId: 5,
          onDisconnected: (_) {
            calls.add('a');
            disposeA();
            disposeB();
          },
        ),
      );
      disposeB = FlutterAnycamPlatform.instance.addStreamListener(
        FlutterAnycamStreamListener(
          viewId: 5,
          onDisconnected: (_) => calls.add('b'),
        ),
      );

      native.emit(5, 'onDisconnected');
      await _flush();
      native.emit(5, 'onDisconnected');
      await _flush();

      expect(calls, ['a', 'b']);
    });
  });

  group('orphan TF frames', () {
    test('a TF frame nobody listens to is closed natively', () async {
      native.emit(99, 'onVideoFrameReceived',
          {'id': 'orphan-1', 'width': 320, 'height': 240});
      await _flush();
      await _flush();

      final closes = native.callsOf('closeTfFrame').toList();
      expect(closes, hasLength(1));
      expect(closes.single.arguments, {'id': 'orphan-1'});
    });

    test('a TF frame from a headless stream is closed too', () async {
      native.emit(-7, 'onTfCameraStreamFrame',
          {'id': 'orphan-2', 'width': 1, 'height': 1});
      await _flush();
      await _flush();

      expect(native.callsOf('closeTfFrame').single.arguments, {'id': 'orphan-2'});
    });

    test('a handled TF frame is left to its listener', () async {
      final dispose = FlutterAnycamPlatform.instance.addStreamListener(
        FlutterAnycamStreamListener(viewId: 6, onVideoFrameReceived: (_) {}),
      );
      addTearDown(dispose);

      native.emit(6, 'onVideoFrameReceived',
          {'id': 'kept', 'width': 320, 'height': 240});
      await _flush();
      await _flush();

      expect(native.callsOf('closeTfFrame'), isEmpty);
    });

    test('raw NV21/BGRA frames (bytes/planes) are never "closed"', () async {
      native.emit(98, 'onVideoFrameReceived', {
        'id': 'raw',
        'bytes': Uint8List(4),
        'width': 2,
        'height': 1,
      });
      native.emit(98, 'onVideoFrameReceived', {
        'id': 'raw2',
        'planes': <Object?>[],
        'width': 2,
        'height': 1,
      });
      await _flush();
      await _flush();

      expect(native.callsOf('closeTfFrame'), isEmpty);
    });

    test('a failing closeTfFrame does not surface as an unhandled error',
        () async {
      native.handlers['closeTfFrame'] =
          (_) => throw PlatformException(code: 'gone');

      native.emit(97, 'onVideoFrameReceived',
          {'id': 'x', 'width': 1, 'height': 1});
      await _flush();
      await _flush();

      expect(native.callsOf('closeTfFrame'), hasLength(1));
    });
  });

  group('method results', () {
    test('null native replies map to safe defaults', () async {
      for (final method in [
        'disposeView',
        'closeTfFrame',
        'closeTfInferenceResult',
      ]) {
        native.handlers[method] = (_) => null;
      }
      final platform = FlutterAnycamPlatform.instance;

      expect(await platform.disposeView({'viewId': 1}), isFalse);
      expect(await platform.closeTfFrame(frameId: 'a'), isFalse);
      expect(await platform.closeTfInferenceResult(inferenceId: 'a'), isFalse);
    });

    test('getTfFrameJpeg tolerates a non-map reply', () async {
      native.handlers['getTfFrameJpeg'] = (_) => null;
      final res =
          await FlutterAnycamPlatform.instance.getTfFrameJpeg(frameId: 'a');
      expect(res['bytes'], isNull);
    });
  });

  group('FlutterAnycamTfFrame', () {
    test('close is idempotent and swallows native errors', () async {
      native.handlers['closeTfFrame'] =
          (_) => throw PlatformException(code: 'boom');
      final frame = FlutterAnycamTfFrame('f1', 10, 10);

      await frame.close();
      await frame.close();

      expect(frame.isClosed, isTrue);
      expect(native.callsOf('closeTfFrame'), hasLength(1));
    });

    test('a closed frame never reaches native again', () async {
      final frame = FlutterAnycamTfFrame('f2', 10, 10);
      await frame.close();
      native.reset();

      expect(await frame.getJpeg(), isNull);
      expect(await frame.getBlurScore(), isNull);
      expect(await frame.getIlluminationScore(), isNull);
      expect(native.calls, isEmpty);
    });

    test('getJpeg returns bytes only when native sends a Uint8List', () async {
      native.handlers['getTfFrameJpeg'] =
          (_) => {'bytes': Uint8List.fromList([1, 2, 3])};
      final frame = FlutterAnycamTfFrame('f3', 10, 10);
      expect(await frame.getJpeg(), [1, 2, 3]);

      native.handlers['getTfFrameJpeg'] = (_) => {'bytes': null};
      expect(await frame.getJpeg(), isNull);
    });

    test('fromMap keeps the optional raw size', () {
      final frame = FlutterAnycamTfFrame.fromMap(
          {'id': 'a', 'width': 4, 'height': 2, 'rawWidth': 8, 'rawHeight': 4});
      expect(frame.hasRawFrame, isTrue);
      expect(frame.rawFrameSize?.width, 8);
      expect(FlutterAnycamTfFrame.fromMap({'id': 'b', 'width': 1, 'height': 1})
          .hasRawFrame, isFalse);
    });
  });

  group('FlutterAnycamTfInferenceResult', () {
    test('close is idempotent and tolerates native errors', () async {
      native.handlers['closeTfInferenceResult'] =
          (_) => throw PlatformException(code: 'boom');
      final result = FlutterAnycamTfInferenceResult(
        id: 'r1',
        output: FlutterAnycamTfInferenceOutput(score: 1),
      );

      expect(await result.close(), isFalse);
      expect(await result.close(), isTrue);
      expect(result.isClosed, isTrue);
      expect(native.callsOf('closeTfInferenceResult'), hasLength(1));
    });

    test('derived frames are fetched once and cached', () async {
      native.handlers['getInferenceResultCroppedFrame'] =
          (_) => {'id': 'crop', 'width': 20, 'height': 10};
      final result = FlutterAnycamTfInferenceResult(
        id: 'r2',
        output: FlutterAnycamTfInferenceOutput(score: 1),
      );

      final a = await result.getCroppedFrame();
      final b = await result.getCroppedFrame();

      expect(a, same(b));
      expect(a?.id, 'crop');
      expect(native.callsOf('getInferenceResultCroppedFrame'), hasLength(1));
    });

    test('a missing derived frame yields null', () async {
      native.handlers['getInferenceResultRawFrame'] = (_) => null;
      final result = FlutterAnycamTfInferenceResult(
        id: 'r3',
        output: FlutterAnycamTfInferenceOutput(score: 1),
      );
      expect(await result.getRawFrame(), isNull);
    });
  });

  group('processTfOutput', () {
    Future<Object?> callFromNative(Object? arguments) async {
      const codec = StandardMethodCodec();
      ByteData? reply;
      await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .handlePlatformMessage(
        kMethodChannel.name,
        codec.encodeMethodCall(MethodCall('processTfOutput', arguments)),
        (data) => reply = data,
      );
      return codec.decodeEnvelope(reply!);
    }

    test('routes native outputs to the listener of the model key', () async {
      final dispose = FlutterAnycamTfOutputProcessor.I.addListener(
        modelKey: 'm1',
        listener: (data) async => [
          {'score': (data as Map)[0]}
        ],
      );
      addTearDown(dispose);

      final res = await callFromNative({
        'modelKey': 'm1',
        'output': {0: 0.5},
      });

      expect(res, [
        {'score': 0.5}
      ]);
    });

    test('an unknown model or a bad payload replies with an empty list',
        () async {
      expect(await callFromNative({'modelKey': 'none', 'output': {}}), isEmpty);
      expect(await callFromNative(null), isEmpty);
      expect(await callFromNative('bad'), isEmpty);
    });
  });
}
