import '../../flutter_anycam.dart';
import '../tensorflow/flutter_anycam_tf_frame.dart';
import '../tensorflow/flutter_anycam_tf_inference_output.dart';
import 'flutter_anycam_frame.dart';

typedef FlutterAnycamStreamMethod = void Function(
  Map<String, dynamic> data,
);

typedef FlutterAnycamStreamFrameCallback = void Function(
  FlutterAnycamFrame frame,
);

typedef FlutterAnycamStreamTfFrameCallback = void Function(
  FlutterAnycamTfFrame frame,
);

typedef FlutterAnycamStreamListenerDisposer = void Function();

typedef FlutterAnycamMesureCallback = void Function(int counter);

typedef TfOutputProcessor = List<FlutterAnycamTfInferenceOutput> Function(
  dynamic data,
  FlutterAnycamSize inputSize,
);
