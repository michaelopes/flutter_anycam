import 'package:flutter/services.dart';
import 'package:flutter_anycam/flutter_anycam.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_native.dart';

List<FlutterAnycamTfInferenceOutput> _noOutputs(dynamic _, FlutterAnycamSize __) =>
    const [];

Future<FlutterAnycamTfSession> _load() => FlutterAnycamTfModel.loadModel(
      assetPath: 'model.tflite',
      inputSize: const FlutterAnycamSize(120, 160),
      outputProcessor: _noOutputs,
      delegate: FlutterAnycamTfDelegate.xnnpack,
    );

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final native = FakeNative.I;

  setUpAll(() async {
    native.install();
    FlutterAnycamPlatform.instance;
    await Future<void>.delayed(Duration.zero);
  });

  setUp(() {
    native.reset();
    native.handlers['loadTfModel'] = (_) => true;
    native.handlers['disposeTfModel'] = (_) => true;
  });

  test('loadModel sends a fresh random key per session', () async {
    final a = await _load();
    final b = await _load();

    final keys = native
        .callsOf('loadTfModel')
        .map((c) => (c.arguments as Map)['key'])
        .toList();
    expect(keys, [a.modelKey, b.modelKey]);
    expect(a.modelKey, isNot(b.modelKey));
    expect((native.callsOf('loadTfModel').first.arguments as Map)['delegate'],
        FlutterAnycamTfDelegate.xnnpack.value);

    await a.dispose();
    await b.dispose();
  });

  test('a native load failure surfaces as an exception', () async {
    native.handlers['loadTfModel'] =
        (_) => throw PlatformException(code: 'loadTfModel', message: 'bad');
    await expectLater(_load(), throwsA(isA<PlatformException>()));
  });

  test('a null native reply is not mistaken for a loaded model', () async {
    native.handlers['loadTfModel'] = (_) => null;
    await expectLater(_load(), throwsStateError);
  });

  test('dispose is idempotent and never throws', () async {
    final session = await _load();
    native.handlers['disposeTfModel'] =
        (_) => throw PlatformException(code: 'gone');

    await session.dispose();
    await session.dispose();

    expect(session.isDisposed, isTrue);
    expect(native.callsOf('disposeTfModel'), hasLength(1));
  });

  test('runInference on a disposed session throws instead of returning []',
      () async {
    final session = await _load();
    await session.dispose();
    native.reset();

    await expectLater(
      session.runInference(inputFrame: FlutterAnycamTfFrame('f', 1, 1)),
      throwsStateError,
    );
    expect(native.callsOf('runTfInference'), isEmpty);
  });

  test('runInference parses native results', () async {
    final session = await _load();
    native.handlers['runTfInference'] = (_) => [
          {
            'id': 'r1',
            'output': {'score': 0.8},
          }
        ];

    final results = await session.runInference(
      inputFrame: FlutterAnycamTfFrame('f', 1, 1),
    );

    expect(results.single.id, 'r1');
    expect(results.single.output.score, 0.8);
    await session.dispose();
  });
}
