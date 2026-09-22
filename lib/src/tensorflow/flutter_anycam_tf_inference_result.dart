// ignore_for_file: public_member_api_docs, sort_constructors_first
import 'dart:convert';

import '../channel/flutter_anycam_platform_interface.dart';
import 'flutter_anycam_tf_frame.dart';
import 'flutter_anycam_tf_inference_output.dart';

class FlutterAnycamTfInferenceResult {
  final String id;
  //final FlutterAnycamTfFrame frame;
  final FlutterAnycamTfInferenceOutput output;

  FlutterAnycamTfFrame? _frame;
  FlutterAnycamTfFrame? _rawFrame;
  FlutterAnycamTfFrame? _croppedFrame;
  FlutterAnycamTfFrame? _scaledCroppedFrame;

  FlutterAnycamTfInferenceResult({
    required this.id,
    //  required this.frame,
    required this.output,
  });

  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'id': id,
      //  'frameId': frame.id,
      'output': output.toMap(),
    };
  }

  Future<FlutterAnycamTfFrame?> getFrame() async {
    if (_frame == null) {
      final data = await FlutterAnycamPlatform.instance
          .getInferenceResultInferenceFrame(id: id);
      if (data == null) return null;
      _frame = FlutterAnycamTfFrame.fromMap(data);
    }
    return _frame!;
  }

  Future<FlutterAnycamTfFrame?> getRawFrame() async {
    if (_rawFrame == null) {
      final data = await FlutterAnycamPlatform.instance
          .getInferenceResultRawFrame(id: id);
      if (data == null) return null;
      _rawFrame = FlutterAnycamTfFrame.fromMap(data);
    }
    return _rawFrame!;
  }

  Future<FlutterAnycamTfFrame?> getCroppedFrame() async {
    if (_croppedFrame == null) {
      final data = await FlutterAnycamPlatform.instance
          .getInferenceResultCroppedFrame(id: id);
      if (data == null) return null;
      _croppedFrame = FlutterAnycamTfFrame.fromMap(data);
    }
    return _croppedFrame!;
  }

  Future<FlutterAnycamTfFrame?> getScaledCroppedFrame() async {
    if (_scaledCroppedFrame == null) {
      final data = await FlutterAnycamPlatform.instance
          .getInferenceResultScaledCroppedFrame(id: id);
      if (data == null) return null;
      _scaledCroppedFrame = FlutterAnycamTfFrame.fromMap(data);
    }
    return _scaledCroppedFrame!;
  }

  Future<bool> close() async {
    _frame = null;
    _rawFrame = null;
    _croppedFrame = null;
    _scaledCroppedFrame = null;
    return await FlutterAnycamPlatform.instance
        .closeTfInferenceResult(inferenceId: id);
  }

  factory FlutterAnycamTfInferenceResult.fromMap(Map<String, dynamic> map) {
    return FlutterAnycamTfInferenceResult(
      id: map['id'] as String,
      /*frame: FlutterAnycamTfFrame.fromMap(
        Map<String, dynamic>.from(map['frame']),
      ),*/
      output: FlutterAnycamTfInferenceOutput.fromMap(
        Map<String, dynamic>.from(map['output']),
      ),
    );
  }

  String toJson() => json.encode(toMap());

  factory FlutterAnycamTfInferenceResult.fromJson(String source) =>
      FlutterAnycamTfInferenceResult.fromMap(
        json.decode(source) as Map<String, dynamic>,
      );
}
