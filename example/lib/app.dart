import 'package:flutter/material.dart';
import 'package:flutter_anycam/flutter_anycam.dart';

import 'scenarios/hub_page.dart';

class AnycamExampleApp extends StatelessWidget {
  const AnycamExampleApp({
    super.key,
    required this.cameras,
  });

  final List<FlutterAnycamCameraSelector> cameras;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'flutter_anycam scenarios',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.teal),
        useMaterial3: true,
      ),
      home: ScenarioHubPage(cameras: cameras),
    );
  }
}
