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
  FlutterAnycamCameraSelector? frontCamera;

  UniqueKey key = UniqueKey();

  @override
  void initState() {
    super.initState();

    Timer.periodic(const Duration(milliseconds: 5000), (timer) {
      setState(() {
        key = UniqueKey();
      });
    });

/*    Future.delayed(const Duration(seconds: 10), () {
      setState(() {
        frontCamera = cameras
            .where((e) => e.lensFacing == FlutterAnycamLensFacing.front)
            .firstOrNull;
      });
    });

    Future.delayed(const Duration(seconds: 30), () {
      setState(() {
        frontCamera = null;
      });
    });*/
  }

  Uint8List? _img;

  Future<void> _onFrame(FlutterAnycamFrame frame) async {
    //Frame para jpeg
    // ignore: unused_local_variable
    final img = await FlutterAnycam.frameConversor.convertToJpeg(
      frame: frame,
    );

    /*  setState(() {
      _img = img;
    });*/
  }

  @override
  Widget build(BuildContext _) {
    final backCamera = cameras[1];

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
            child: _img != null
                ? Image.memory(_img!)
                : Stack(
                    children: [
                      SizedBox.expand(
                        key: key,
                        child: FlutterAnycamWidget(
                          camera: cameras[0],
                          onFrame: _onFrame,
                        ),
                      ),
                      Positioned(
                        right: 20,
                        bottom: 20,
                        width: 250,
                        child: AspectRatio(
                          aspectRatio: 16 / 10,
                          child: FlutterAnycamWidget(
                            camera: cameras[2],
                          ),
                        ),
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