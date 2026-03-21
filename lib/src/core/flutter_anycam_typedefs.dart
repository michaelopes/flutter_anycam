import 'flutter_anycam_frame.dart';

typedef FlutterAnycamStreamMethod = void Function(
  Map<String, dynamic> data,
);

typedef FlutterAnycamStreamFrameCallback = void Function(
  FlutterAnycamFrame frame,
);

typedef FlutterAnycamStreamListenerDisposer = void Function();

typedef FlutterAnycamMesureCallback = void Function(int counter);
