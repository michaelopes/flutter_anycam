import 'package:flutter_anycam/src/channel/flutter_anycam_tf_output_processor.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  final processor = FlutterAnycamTfOutputProcessor.I;

  test('the latest listener of a model key wins', () async {
    final disposeOld = processor.addListener(
      modelKey: 'k',
      listener: (_) async => 'old',
    );
    final disposeNew = processor.addListener(
      modelKey: 'k',
      listener: (_) async => 'new',
    );
    addTearDown(disposeNew);

    expect(await processor.notify({'modelKey': 'k', 'output': 1}), 'new');

    disposeOld();
    expect(await processor.notify({'modelKey': 'k', 'output': 1}), 'new',
        reason: 'disposing a replaced session must not unregister the new one');
  });

  test('disposing the current listener unregisters it', () async {
    final dispose = processor.addListener(
      modelKey: 'gone',
      listener: (_) async => 'x',
    );
    dispose();
    dispose();

    expect(await processor.notify({'modelKey': 'gone', 'output': 1}), isEmpty);
  });

  test('the listener receives only the output payload', () async {
    Object? seen;
    final dispose = processor.addListener(
      modelKey: 'p',
      listener: (data) async {
        seen = data;
        return const [];
      },
    );
    addTearDown(dispose);

    await processor.notify({
      'modelKey': 'p',
      'output': {0: 1.0}
    });

    expect(seen, {0: 1.0});
  });

  test('non-map payloads reply with an empty list', () async {
    expect(await processor.notify(null), isEmpty);
    expect(await processor.notify(42), isEmpty);
    expect(await processor.notify([1, 2]), isEmpty);
  });
}
