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
  bool _stop = false;

  @override
  void initState() {
    super.initState();
  }

  @override
  Widget build(BuildContext _) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('Plugin example app'),
        ),
        body: Builder(builder: (context) {
          return SizedBox.expand(
            child: Column(
              children: [
                /*Expanded(
                    child: FlutterAnycamWidget(
                    FlutterAnycamWidget(
                    camera: FlutterAnycamCameraSelector.rtsp(
                      url: "rtsp://192.168.18.93:554/mode=real&idc=1&ids=1",
                      username: "admin",
                      password: "1",
                    ),
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
                /*   Expanded(
                    child: FlutterAnycamWidget(
                      camera: cameras[1],
                    ),
                  ),*/
              ],
            ),
          );
        }),
      ),
    );
  }
}
