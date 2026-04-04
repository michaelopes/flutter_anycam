import 'dart:async';
import 'dart:math';
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
  UniqueKey k = UniqueKey();
  late final FlutterAnycamCameraSelector selectedCamera;

  FlutterAnycamTfSession? modelSession;

  @override
  void initState() {
    super.initState();
    m.startCounting(
      callback: (counter) {},
    );
    /*FlutterAnycamCameraRawStream.I.register(
      cameraId: cameras.first.id,
      fps: 15,
      listener: (data) {},
    );*/

    selectedCamera =
        cameras.firstWhere((e) => e.lensFacing == FlutterAnycamLensFacing.back);
    /*FlutterAnycamCameraSelector.rtsp(
                            url:
                                "rtsp://192.168.1.16:554/mode=real&idc=1&ids=1",
                            username: "admin",
                            password: "1",
                          ),*/

    /*Future.delayed(const Duration(seconds: 15), () {
      setState(() {
        FlutterAnycam.setZoom(camera: selectedCamera, zoom: 1.2);
      });
    });*/

    // Future.delayed(Duration(seconds: 15), () {
    //   setState(() {
    //     k = UniqueKey();
    //   });
    // });
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

  final m = FlutterAnycamMesure(seconds: 1);

  List<FlutterAnycamTfInferenceOutput> _processor(
    data,
    FlutterAnycamSize inputSize,
  ) {
    final output = {
      0: List.filled(1 * 300 * 6, 0.0).reshape([1, 300, 6]),
    };

    FlutterAnycamTfSession.cast(data, output);
    final lst = output[0]![0];
    int lastKey = -1;

    final vehicles = <FlutterAnycamTfInferenceOutput>[];

    for (var i = 0; i < lst.length; i++) {
      final item = lst[i];
      final key =
          Object.hash(item[0], item[1], item[2], item[3], item[4], item[5]);

      if (lastKey == key) {
        continue;
      }
      lastKey = key;
      final confidence = item[4];
      final classId = item[5].toInt();

      if (confidence >= .35 &&
          confidence <= .999 &&
          VehicleLabels.isVehicle(classId)) {
        final y1 = item[1] as double; // ymin
        final x1 = item[0] as double; // xmin
        final y2 = item[3] as double; // ymax
        final x2 = item[2] as double; // xmax

        final rX1 = max(0.0, x1 * inputSize.width);
        final rY1 = max(0.0, y1 * inputSize.height);

        final rX2 = min(inputSize.width.toDouble(), x2 * inputSize.width);
        final rY2 = min(inputSize.width.toDouble(), y2 * inputSize.width);

        // final rW = rX2 - rX1;
        // final rH = rY2 - rY1;
        try {
          vehicles.add(
            FlutterAnycamTfInferenceOutput(
              score: confidence,
              box: FlutterAnycamTfInferenceOutputBox(
                xMin: rX1.toInt(),
                yMin: rY1.toInt(),
                xMax: rX2.toInt(),
                yMax: rY2.toInt(),
                srcWidth: inputSize.width,
                srcHeight: inputSize.height,
                classId: classId,
                minScaledCropSize: FlutterAnycamSize(64, 64),
              ),
            ),
          );
        } catch (e) {
          print(e);
        }
      }
    }

    return vehicles;

    /* return [
      FlutterAnycamTfInferenceOutput(
        box: FlutterAnycamTfInferenceOutputBox(
          xMin: 0,
          yMin: 0,
          xMax: 100,
          yMax: 100,
          score: 1,
          classId: 1,
        ),
      ),
    ];*/
  }

  Future<void> _onFrame(FlutterAnycamFrame frame) async {
    m.count();
    //Frame para jpeg
    // ignore: unused_local_variable
//cropX=52cropY=0cropW=1196cropH=720

    final img = await FlutterAnycam.frameConversor.convertToJpeg(
      frame: frame.rawFrame!,
      crop: FlutterAnycamCrop(
        width: 720, //bbox.width.floor(),
        height: 1196, //bbox.height.floor(),
        left: 0, //bbox.left.floor(),
        top: 52, //bbox.top.floor(),
        // resize: FlutterAnycamSize.square(),
      ),
      /*  crop: FlutterAnycamCrop(
        width: 100,
        height: frame.height,
        left: 100,
        top: 0,
        resize: const FlutterAnycamSize(50, 50),
      ),*/
    );
    setState(() {
      _img = img;
    });
  }

  Stopwatch? _stopwatch;
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
                          camera: cameras.last,
                          onFrame: _onFrame,
                        ),
                      ),
                    if (show2)
                      Expanded(
                        key: k,
                        child: FlutterAnycamTfWidget(
                          fps: 30,
                          preferredSize: const FlutterAnycamSize(1280, 720),
                          resizeFrame: const FlutterAnycamSize(224, 224),
                          filter: FlutterAnycamFilter.none,
                          camera: selectedCamera,
                          /*FlutterAnycamCameraSelector.rtsp(
                            url:
                                "rtsp://192.168.1.16:554/mode=real&idc=1&ids=1",
                            username: "admin",
                            password: "1",
                          ),*/
                          onFrame: (frame) async {
                            print("aki 1");
                            m.count();
                            modelSession ??=
                                await FlutterAnycamTfModel.loadModel(
                              assetPath: "assets/vehicle_detection.tflite",
                              inputSize: const FlutterAnycamSize(224, 224),
                              outputProcessor: _processor,
                              trackerOptions: FlutterAnycamTfTrackerOptions(
                                threshold: .1,
                              ),
                            );
                            final res = await modelSession!.runInference(
                              inputFrame: frame,
                              normalize: FlutterAnycamTfNormalize.simple,
                            );

                            await frame.close();
                            if (res.isNotEmpty) {
                              print("aki 2");
                              print(
                                "TrackerId: ${res.first.output.box?.trackingId}",
                              );
                              final frame =
                                  await res.first.getScaledCroppedFrame();

                              /*  await res.first.getCroppedFrame();
                              await res.first.getFrame();
                              await res.first.getRawFrame();*/

                              final img = await frame?.getJpeg();
                              setState(() {
                                _img = img;
                              });
                              res.first.close();
                            }

                            //  await frame.close();
                            if (_stopwatch != null) {
                              _stopwatch!.stop();
                              final time = _stopwatch!.elapsedMilliseconds;
                              debugPrint(
                                'Tempo-geral: ${time}ms',
                              );
                            }
                            _stopwatch = Stopwatch()..start();
                          },
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

class VehicleLabels {
  static final Map<int, String> _labels = {
    0: "car",
    1: "motorcycle",
    2: "bus",
    3: "truck",

    /*2: "car",
    3: "motorcycle",
    4: "airplane",
    5: "bus",
    6: "train",
    7: "truck",*/
  };

  /// Recebe o id do modelo e retorna o label do veículo
  static String getLabel(num id) {
    return _labels[id.toInt()] ?? "unknown";
  }

  /// Verifica se é um veículo
  static bool isVehicle(num id) {
    return _labels.containsKey(id.toInt());
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