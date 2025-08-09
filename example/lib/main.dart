import 'package:flutter/material.dart';

import 'package:flutter_anycam/flutter_anycam.dart';

List<FlutterAnycamCameraSelector> cameras = [];

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  cameras = await FlutterAnycam.availableCameras();
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  @override
  void initState() {
    super.initState();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('Plugin example app'),
        ),
        body: SizedBox.expand(
          child: Column(
            children: [
              /*Expanded(
                child: FlutterAnycamWidget(
                  camera: cameras[1],
                  onFrame: (frame) {},
                ),
              ),*/
              /* Expanded(
                child: FlutterAnycamWidget(
                  camera: cameras[0],
                ),
              ),*/
              Expanded(
                child: FlutterAnycamWidget(
                  camera: cameras[0],
                ),
              ),
              Expanded(
                child: FlutterAnycamWidget(
                  camera: cameras[1],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
