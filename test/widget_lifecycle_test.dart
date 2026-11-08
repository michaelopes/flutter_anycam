import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_anycam/flutter_anycam.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_native.dart';

final cameraA = FlutterAnycamCameraSelector(
  id: '0',
  name: 'back',
  lensFacing: FlutterAnycamLensFacing.back,
  sensorOrientation: 90,
);

final cameraB = FlutterAnycamCameraSelector(
  id: '1001',
  name: 'usb',
  lensFacing: FlutterAnycamLensFacing.usb,
  sensorOrientation: 0,
);

const texts = FlutterAnycamTexts();

Widget host(Widget child, {bool bounded = true}) => MaterialApp(
      home: Scaffold(
        body: bounded
            ? Center(child: SizedBox(width: 400, height: 300, child: child))
            : ListView(children: [child]),
      ),
    );

Widget camera({
  FlutterAnycamCameraSelector? selector,
  int viewId = 1,
  bool autoRetry = false,
  FlutterAnycamStreamFrameCallback? onFrame,
}) =>
    FlutterAnycamWidget(
      camera: selector ?? cameraA,
      viewId: viewId,
      autoRetry: autoRetry,
      autoRetryDelay: const Duration(seconds: 1),
      onFrame: onFrame,
    );

Map<String, Object?> connected({int textureId = 7}) =>
    {'width': 640, 'height': 480, 'textureId': textureId};

bool isPreviewVisible(WidgetTester tester) =>
    tester.widget<IndexedStack>(find.byType(IndexedStack)).index == 1;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final native = FakeNative.I;

  setUpAll(() async {
    native.install();
    FlutterAnycamPlatform.instance;
    await Future<void>.delayed(Duration.zero);
  });

  setUp(native.reset);

  group('FlutterAnycamWidget', () {
    testWidgets('creates the native view and shows the texture once connected',
        (tester) async {
      await tester.pumpWidget(host(camera()));
      await tester.pump();

      final create = native.callsOf('createView').single;
      final args = Map<String, Object?>.from(create.arguments as Map);
      expect(args['viewId'], 1);
      expect((args['cameraSelector'] as Map)['id'], '0');
      expect(isPreviewVisible(tester), isFalse);

      native.dispatch(1, 'onConnected', connected());
      await tester.pump();

      expect(isPreviewVisible(tester), isTrue);
      expect(tester.widget<Texture>(find.byType(Texture)).textureId, 7);

      await tester.pumpWidget(const SizedBox());
    });

    testWidgets('an invalid onConnected payload shows the failure state',
        (tester) async {
      await tester.pumpWidget(host(camera()));
      await tester.pump();

      native.dispatch(1, 'onConnected', {'width': 0, 'height': 480});
      await tester.pump();

      expect(isPreviewVisible(tester), isFalse);
      expect(find.text(texts.failureMessage), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
    });

    testWidgets('createView returning null shows failure', (tester) async {
      native.handlers['createView'] = (_) => null;
      await tester.pumpWidget(host(camera()));
      await tester.pump();

      expect(find.text(texts.failureMessage), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
    });

    testWidgets('createView throwing shows failure without crashing',
        (tester) async {
      native.handlers['createView'] =
          (_) => throw PlatformException(code: 'createView', message: 'boom');
      await tester.pumpWidget(host(camera()));
      await tester.pump();

      expect(find.text(texts.failureMessage), findsOneWidget);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
    });

    testWidgets('dispose releases the native view and the event listener',
        (tester) async {
      await tester.pumpWidget(host(camera()));
      await tester.pump();
      native.dispatch(1, 'onConnected', connected());
      await tester.pump();

      await tester.pumpWidget(const SizedBox());
      await tester.pump();

      expect(native.callsOf('disposeView').single.arguments, {'viewId': 1});

      // Late events from the dying camera must be harmless, and a TF frame
      // nobody listens to anymore must be closed natively.
      native.dispatch(1, 'onConnected', connected());
      native.dispatch(1, 'onFailed');
      native.dispatch(1, 'onVideoFrameReceived',
          {'id': 'late', 'width': 1, 'height': 1});
      await tester.pump();
      await tester.pump();

      expect(tester.takeException(), isNull);
      expect(native.callsOf('closeTfFrame').single.arguments, {'id': 'late'});
    });

    testWidgets('dispose while createView is pending never resurrects state',
        (tester) async {
      final pending = Completer<Object?>();
      native.handlers['createView'] = (_) => pending.future;
      await tester.pumpWidget(host(camera()));
      await tester.pump();

      await tester.pumpWidget(const SizedBox());
      pending.complete(null);
      await tester.pump();

      expect(tester.takeException(), isNull);
      expect(native.callsOf('createView'), hasLength(1));
      expect(native.callsOf('disposeView'), hasLength(1));
    });

    testWidgets(
        'switching camera disposes first, ignores stale events and recreates',
        (tester) async {
      await tester.pumpWidget(host(camera()));
      await tester.pump();
      native.dispatch(1, 'onConnected', connected());
      await tester.pump();
      expect(isPreviewVisible(tester), isTrue);

      final disposing = Completer<Object?>();
      native.handlers['disposeView'] = (_) => disposing.future;
      await tester.pumpWidget(host(camera(selector: cameraB)));
      await tester.pump();

      expect(isPreviewVisible(tester), isFalse);
      expect(native.callsOf('disposeView'), hasLength(1));
      expect(native.callsOf('createView'), hasLength(1));

      // The old camera still reports while it is being torn down.
      native.dispatch(1, 'onConnected', connected(textureId: 3));
      native.dispatch(1, 'onFailed');
      await tester.pump();
      expect(isPreviewVisible(tester), isFalse);
      expect(find.text(texts.failureMessage), findsNothing);

      disposing.complete(true);
      await tester.pump();
      expect(native.callsOf('createView'), hasLength(1));
      await tester.pump(const Duration(milliseconds: 200));

      final recreated = native.callsOf('createView').toList();
      expect(recreated, hasLength(2));
      expect(((recreated.last.arguments as Map)['cameraSelector'] as Map)['id'],
          '1001');

      native.dispatch(1, 'onConnected', connected(textureId: 9));
      await tester.pump();
      expect(isPreviewVisible(tester), isTrue);
      expect(tester.widget<Texture>(find.byType(Texture)).textureId, 9);

      await tester.pumpWidget(const SizedBox());
    });

    testWidgets('rapid camera switches create only the last camera',
        (tester) async {
      await tester.pumpWidget(host(camera()));
      await tester.pump();

      for (var i = 0; i < 5; i++) {
        await tester.pumpWidget(
            host(camera(selector: i.isEven ? cameraB : cameraA)));
        await tester.pump(const Duration(milliseconds: 50));
      }
      await tester.pump(const Duration(milliseconds: 300));

      final creates = native.callsOf('createView').toList();
      expect(creates, hasLength(2),
          reason: 'initial camera plus the last switch only');
      expect(((creates.last.arguments as Map)['cameraSelector'] as Map)['id'],
          '1001');

      await tester.pumpWidget(const SizedBox());
    });

    testWidgets('a short disconnect is debounced', (tester) async {
      await tester.pumpWidget(host(camera()));
      await tester.pump();
      native.dispatch(1, 'onConnected', connected());
      await tester.pump();

      native.dispatch(1, 'onDisconnected');
      await tester.pump(const Duration(seconds: 1));
      native.dispatch(1, 'onConnected', connected());
      await tester.pump(const Duration(seconds: 3));

      expect(isPreviewVisible(tester), isTrue);
      expect(find.text(texts.disconnectedMessage), findsNothing);

      native.dispatch(1, 'onDisconnected');
      await tester.pump();
      await tester.pump(const Duration(seconds: 2));
      expect(find.text(texts.disconnectedMessage), findsOneWidget);

      await tester.pumpWidget(const SizedBox());
    });

    testWidgets('autoRetry recreates a failed camera once per delay',
        (tester) async {
      await tester.pumpWidget(host(camera(autoRetry: true)));
      await tester.pump();

      native.dispatch(1, 'onFailed');
      native.dispatch(1, 'onFailed');
      native.dispatch(1, 'onFailed');
      await tester.pump();
      expect(find.text(texts.failureMessage), findsOneWidget);

      await tester.pump(const Duration(seconds: 1));
      await tester.pump(const Duration(milliseconds: 200));

      expect(native.callsOf('disposeView'), hasLength(1));
      expect(native.callsOf('createView'), hasLength(2));

      await tester.pumpWidget(const SizedBox());
    });

    testWidgets('autoRetry also recovers from a null createView',
        (tester) async {
      native.handlers['createView'] = (_) => null;
      await tester.pumpWidget(host(camera(autoRetry: true)));
      await tester.pump();
      expect(find.text(texts.failureMessage), findsOneWidget);

      native.handlers.remove('createView');
      await tester.pump(const Duration(seconds: 1));
      await tester.pump(const Duration(milliseconds: 200));

      expect(native.callsOf('createView'), hasLength(2));
      await tester.pumpWidget(const SizedBox());
    });

    testWidgets('a plain widget with type tf frees the frames it cannot use',
        (tester) async {
      await tester.pumpWidget(host(FlutterAnycamWidget(
        camera: cameraA,
        viewId: 1,
        type: FlutterAnycamType.tf,
      )));
      await tester.pump();

      native.dispatch(
          1, 'onVideoFrameReceived', {'id': 'tf-1', 'width': 1, 'height': 1});
      await tester.pump();

      expect(tester.takeException(), isNull);
      expect(native.callsOf('closeTfFrame').single.arguments, {'id': 'tf-1'});
      await tester.pumpWidget(const SizedBox());
    });

    testWidgets('the retry button recreates the camera', (tester) async {
      await tester.pumpWidget(host(camera()));
      await tester.pump();
      native.dispatch(1, 'onFailed');
      await tester.pump();

      await tester.tap(find.text(texts.retry));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));

      expect(native.callsOf('createView'), hasLength(2));
      await tester.pumpWidget(const SizedBox());
    });

    testWidgets('works inside unbounded parents without layout errors',
        (tester) async {
      await tester.pumpWidget(host(camera(), bounded: false));
      await tester.pump();
      native.dispatch(1, 'onConnected', connected());
      await tester.pump();

      expect(tester.takeException(), isNull);
      expect(isPreviewVisible(tester), isTrue);
      final size = tester.getSize(find.byType(FlutterAnycamWidget));
      expect(size.height, closeTo(size.width * 480 / 640, 0.5));

      await tester.pumpWidget(const SizedBox());
    });

    testWidgets('frames reach onFrame for the right view only', (tester) async {
      final frames = <FlutterAnycamFrame>[];
      await tester.pumpWidget(host(camera(onFrame: frames.add)));
      await tester.pump();

      final frame = {
        'width': 2,
        'height': 2,
        'rotation': 0,
        'format': 'nv21',
        'bytes': Uint8List(6),
      };
      native.dispatch(1, 'onVideoFrameReceived', frame);
      native.dispatch(2, 'onVideoFrameReceived', frame);
      await tester.pump();

      expect(tester.takeException(), isNull);
      expect(frames, hasLength(1));
      await tester.pumpWidget(const SizedBox());
    });
  });

  group('FlutterAnycamTfWidget', () {
    Widget tf({
      FlutterAnycamStreamTfFrameCallback? onFrame,
      int maxConcurrentFrames = 2,
    }) =>
        host(FlutterAnycamTfWidget(
          camera: cameraA,
          viewId: 5,
          onFrame: onFrame,
          maxConcurrentFrames: maxConcurrentFrames,
        ));

    Map<String, Object?> tfFrame(String id) =>
        {'id': id, 'width': 320, 'height': 240};

    List<String> closedIds() => native
        .callsOf('closeTfFrame')
        .map((c) => (c.arguments as Map)['id'] as String)
        .toList();

    testWidgets('requests a TF pipeline', (tester) async {
      await tester.pumpWidget(tf());
      await tester.pump();

      final args = native.callsOf('createView').single.arguments as Map;
      expect(args['type'], FlutterAnycamType.tf.value);
      await tester.pumpWidget(const SizedBox());
    });

    testWidgets('frames without a consumer are closed', (tester) async {
      await tester.pumpWidget(tf());
      await tester.pump();

      native.dispatch(5, 'onVideoFrameReceived', tfFrame('a'));
      await tester.pump();
      await tester.pump();

      expect(closedIds(), ['a']);
      await tester.pumpWidget(const SizedBox());
    });

    testWidgets('frames above maxConcurrentFrames are closed immediately',
        (tester) async {
      final gate = Completer<void>();
      final consumed = <FlutterAnycamTfFrame>[];
      await tester.pumpWidget(tf(
        maxConcurrentFrames: 1,
        onFrame: (frame) async {
          consumed.add(frame);
          await gate.future;
          await frame.close();
        },
      ));
      await tester.pump();

      native.dispatch(5, 'onVideoFrameReceived', tfFrame('busy'));
      await tester.pump();
      native.dispatch(5, 'onVideoFrameReceived', tfFrame('dropped'));
      await tester.pump();
      await tester.pump();

      expect(consumed.map((f) => f.id), ['busy']);
      expect(closedIds(), ['dropped']);

      gate.complete();
      await tester.pump();
      await tester.pump();
      expect(closedIds(), ['dropped', 'busy']);

      native.dispatch(5, 'onVideoFrameReceived', tfFrame('next'));
      await tester.pump();
      expect(consumed.map((f) => f.id), ['busy', 'next']);

      await tester.pumpWidget(const SizedBox());
    });

    testWidgets('a throwing consumer gets its frame closed and is reported',
        (tester) async {
      await tester.pumpWidget(tf(
        onFrame: (frame) async => throw StateError('consumer bug'),
      ));
      await tester.pump();

      native.dispatch(5, 'onVideoFrameReceived', tfFrame('err'));
      await tester.pump();
      await tester.pump();

      expect(tester.takeException(), isA<StateError>());
      expect(closedIds(), ['err']);
      await tester.pumpWidget(const SizedBox());
    });

    testWidgets('after dispose, frames are closed by the channel',
        (tester) async {
      await tester.pumpWidget(tf(onFrame: (frame) async {}));
      await tester.pump();
      await tester.pumpWidget(const SizedBox());
      await tester.pump();

      native.dispatch(5, 'onVideoFrameReceived', tfFrame('after'));
      await tester.pump();
      await tester.pump();

      expect(closedIds(), ['after']);
    });
  });
}
