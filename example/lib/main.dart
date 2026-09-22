import 'package:flutter/material.dart';
import 'package:flutter_anycam/flutter_anycam.dart';

import 'app.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  List<FlutterAnycamCameraSelector> cameras = const [];
  try {
    cameras = await FlutterAnycam.availableCameras();
  } catch (e, st) {
    debugPrint('availableCameras failed: $e\n$st');
  }
  runApp(AnycamExampleApp(cameras: cameras));
}
