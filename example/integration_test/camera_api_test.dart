import 'package:flutter/material.dart';
import 'package:flutter_anycam/flutter_anycam.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:flutter_anycam_example/app.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('availableCameras returns a List', (tester) async {
    final cameras = await FlutterAnycam.availableCameras();
    expect(cameras, isA<List<FlutterAnycamCameraSelector>>());

    await tester.pumpWidget(AnycamExampleApp(cameras: cameras));
    await tester.pumpAndSettle();
    expect(find.text('flutter_anycam scenarios'), findsOneWidget);
  });

  testWidgets('preview widget mounts when a camera exists', (tester) async {
    final cameras = await FlutterAnycam.availableCameras();
    if (cameras.isEmpty) {
      // Emulator / CI without camera — skip soft.
      expect(cameras, isEmpty);
      return;
    }

    final camera = cameras.first;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: FlutterAnycamWidget(
            key: const Key('api_preview'),
            camera: camera,
            fps: 3,
            frameDeliveryEnabled: false,
          ),
        ),
      ),
    );

    await tester.pump(const Duration(seconds: 2));
    expect(find.byKey(const Key('api_preview')), findsOneWidget);
  });
}
