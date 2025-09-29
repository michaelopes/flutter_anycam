import 'dart:async';
import 'dart:typed_data';

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
  /*FlutterAnycamCameraSelector camera =
      cameras.firstWhere((e) => e.lensFacing == FlutterAnycamLensFacing.front);*/

  bool show1 = false;
  bool show2 = true;

  @override
  void initState() {
    super.initState();

    /* Future.delayed(const Duration(seconds: 20), () {
      setState(() {
        show1 = true;
      });
    });*/

    /*  Future.delayed(const Duration(milliseconds: 10000), () {
      setState(() {
        show1 = false;
      });

      Future.delayed(const Duration(seconds: 20), () {
        setState(() {
          show2 = false;
        });
      });
    });*/
  }

  Uint8List? _img;

  Future<void> _onFrame(FlutterAnycamFrame frame) async {
    //Frame para jpeg
    // ignore: unused_local_variable
    print(frame);
    final img = await FlutterAnycam.frameConversor.convertToJpeg(
      frame: frame,
    );

    setState(() {
      _img = img;
    });
  }

  final k = UniqueKey();

  @override
  Widget build(BuildContext _) {
    /* final usbCamera = cameras
        .where((e) => e.lensFacing == FlutterAnycamLensFacing.usb)
        .firstOrNull;

    final rtspCamera = FlutterAnycamCameraSelector.rtsp(
      url: "rtsp://192.168.18.93:554/mode=real&idc=1&ids=1",
      username: "admin",
      password: "1",
    );*/

    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('Plugin example app'),
        ),
        body: Builder(builder: (context) {
          return SizedBox.expand(
            child: Stack(
              children: [
                Column(
                  children: [
                    /* if (key != null)
                      Expanded(
                        key: key,
                        child: FlutterAnycamWidget(
                          camera: camera,
                          onFrame: _onFrame,
                        ),
                      ),*/
                    if (show1)
                      Expanded(
                        child: FlutterAnycamWidget(
                          key: k,
                          //   preferredSize: const FlutterAnycamSize(1280, 720),
                          camera: cameras.firstWhere(
                            (e) => e.lensFacing == FlutterAnycamLensFacing.back,
                          ),
                          onFrame: (result) {
                            print("Frame1");
                          },
                        ),
                      ),
                    if (show2)
                      Expanded(
                        // key: UniqueKey(),
                        child: FlutterAnycamWidget(
                          //   preferredSize: const FlutterAnycamSize(1280, 720),
                          camera: FlutterAnycamCameraSelector.rtsp(
                            url:
                                "rtsp://192.168.18.93:554/mode=real&idc=1&ids=1",
                            username: "admin",
                            password: "1",
                          ).customSensorOrientation(sensorOrientation: 90),
                          onFrame: _onFrame,
                        ),
                      ),
                  ],
                ),
                if (_img != null)
                  Positioned(
                    top: 20,
                    right: 20,
                    child: SizedBox(height: 150, child: Image.memory(_img!)),
                  )
              ],
            ),
          );
        }),
      ),
    );
  }
}


/*

 Expanded(
                  child: FlutterAnycamWidget(
                    camera: FlutterAnycamCameraSelector.rtsp(
                      url: "rtsp://192.168.18.93:554/mode=real&idc=1&ids=1",
                      username: "admin",
                      password: "1",
                    ),
                    onFrame: (frame) async {
                      if (!_stop) {
                        _stop = true;

                        /*   final img =
                            await FlutterAnycam.frameConversor.convertToJpeg(
                          frame: frame,
                          rotation: 0,
                        );

                        if (img != null) {
                          await showDialog(
                              context: context,
                              builder: (_) {
                                return Material(
                                  child: Dialog(
                                    child: Column(
                                      mainAxisSize: MainAxisSize.min,
                                      children: [
                                        Image.memory(img),
                                        const SizedBox(
                                          height: 16,
                                        ),
                                        ElevatedButton(
                                          onPressed: () {
                                            Navigator.pop(context);
                                          },
                                          child: const Text("Fechar"),
                                        )
                                      ],
                                    ),
                                  ),
                                );
                              });
                        }*/

                        _stop = false;
                      }
                    },
                  ),
                ),

 */