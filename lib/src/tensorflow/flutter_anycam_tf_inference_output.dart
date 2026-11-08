// ignore_for_file: public_member_api_docs, sort_constructors_first
import 'dart:convert';

import '../core/flutter_anycam_size.dart';
import 'flutter_anycam_tf_bbox_tracker.dart';

/// Platform channels decode typed lists as Float32List/Float64List and plain
/// lists as List<Object?>; both are accepted.
List<double> _toDoubleList(Object? value) {
  if (value is List<double>) return List<double>.of(value);
  return (value as List).map((e) => (e as num).toDouble()).toList();
}

class FlutterAnycamTfInferenceOutput {
  final FlutterAnycamTfInferenceOutputBox? box;
  final FlutterAnycamTfInferenceOutputKeypoint? keypoint;
  final List<double>? embedding;
  final List<List<double>>? segmentation;
  final String? text;
  final double score;

  FlutterAnycamTfInferenceOutput({
    this.box,
    this.keypoint,
    this.embedding,
    this.segmentation,
    this.text,
    required this.score,
  });

  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'score': score,
      'box': box?.toMap(),
      'keypoint': keypoint?.toMap(),
      'embedding': embedding,
      'segmentation': segmentation,
      'text': text,
    };
  }

  factory FlutterAnycamTfInferenceOutput.fromMap(Map<String, dynamic> map) {
    return FlutterAnycamTfInferenceOutput(
      score: (map['score'] as num?)?.toDouble() ?? 0.0,
      box: map['box'] != null
          ? FlutterAnycamTfInferenceOutputBox.fromMap(
              Map<String, dynamic>.from(map['box']),
            )
          : null,
      keypoint: map['keypoint'] != null
          ? FlutterAnycamTfInferenceOutputKeypoint.fromMap(
              Map<String, dynamic>.from(map['keypoint']),
            )
          : null,
      embedding: map['embedding'] != null
          ? _toDoubleList(map['embedding'])
          : null,
      segmentation: map['segmentation'] != null
          ? (map['segmentation'] as List)
              .map((row) => _toDoubleList(row))
              .toList()
          : null,
      text: map['text'] != null ? map['text'] as String : null,
    );
  }

  String toJson() => json.encode(toMap());

  factory FlutterAnycamTfInferenceOutput.fromJson(String source) =>
      FlutterAnycamTfInferenceOutput.fromMap(
          json.decode(source) as Map<String, dynamic>);
}

class FlutterAnycamTfInferenceOutputBox extends FlutterAnycamTfBoundingBox {
  final int xMin;
  final int yMin;
  final int xMax;
  final int yMax;
  final int classId;
  final int srcWidth;
  final int srcHeight;

  final double cropPadPercent;

  final FlutterAnycamSize? minScaledCropSize;

  FlutterAnycamTfInferenceOutputBox({
    required this.xMin,
    required this.yMin,
    required this.xMax,
    required this.yMax,
    required this.srcWidth,
    required this.srcHeight,
    super.trackingId,
    this.minScaledCropSize,
    this.cropPadPercent = 0,
    this.classId = -1,
  });

  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'xMin': xMin,
      'yMin': yMin,
      'xMax': xMax,
      'yMax': yMax,
      'srcWidth': srcWidth,
      'srcHeight': srcHeight,
      'classId': classId,
      'trackingId': trackingId,
      'minScaledCropSize': minScaledCropSize?.toMap(),
      'cropPadPercent': cropPadPercent,
    };
  }

  factory FlutterAnycamTfInferenceOutputBox.fromMap(Map<String, dynamic> map) {
    return FlutterAnycamTfInferenceOutputBox(
      xMin: (map['xMin'] as num).toInt(),
      yMin: (map['yMin'] as num).toInt(),
      xMax: (map['xMax'] as num).toInt(),
      yMax: (map['yMax'] as num).toInt(),
      srcWidth: (map['srcWidth'] as num).toInt(),
      srcHeight: (map['srcHeight'] as num).toInt(),
      classId: (map['classId'] as num?)?.toInt() ?? -1,
      trackingId: map['trackingId'] as String?,
      minScaledCropSize: map['minScaledCropSize'] != null
          ? FlutterAnycamSize.fromMap(
              Map<String, dynamic>.from(map['minScaledCropSize']),
            )
          : null,
      cropPadPercent: map['cropPadPercent'] != null
          ? (map['cropPadPercent'] as num).toDouble()
          : 0,
    );
  }

  String toJson() => json.encode(toMap());

  factory FlutterAnycamTfInferenceOutputBox.fromJson(String source) =>
      FlutterAnycamTfInferenceOutputBox.fromMap(
          json.decode(source) as Map<String, dynamic>);

  FlutterAnycamTfInferenceOutputBox copyWith({
    int? xMin,
    int? yMin,
    int? xMax,
    int? yMax,
    int? classId,
    int? srcWidth,
    int? srcHeight,
    String? trackingId,
  }) {
    return FlutterAnycamTfInferenceOutputBox(
      xMin: xMin ?? this.xMin,
      yMin: yMin ?? this.yMin,
      xMax: xMax ?? this.xMax,
      yMax: yMax ?? this.yMax,
      classId: classId ?? this.classId,
      srcWidth: srcWidth ?? this.srcWidth,
      srcHeight: srcHeight ?? this.srcHeight,
      trackingId: trackingId ?? this.trackingId,
    );
  }

  @override
  double get height => (yMax - yMin).toDouble();

  @override
  double get width => (xMax - xMin).toDouble();

  @override
  double get x => xMin.toDouble();

  @override
  double get y => yMin.toDouble();
}

class FlutterAnycamTfInferenceOutputKeypoint {
  final double x;
  final double y;
  final double? score;
  FlutterAnycamTfInferenceOutputKeypoint(this.x, this.y, [this.score]);

  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'x': x,
      'y': y,
      'score': score,
    };
  }

  factory FlutterAnycamTfInferenceOutputKeypoint.fromMap(
      Map<String, dynamic> map) {
    return FlutterAnycamTfInferenceOutputKeypoint(
      (map['x'] as num).toDouble(),
      (map['y'] as num).toDouble(),
      (map['score'] as num?)?.toDouble(),
    );
  }

  String toJson() => json.encode(toMap());

  factory FlutterAnycamTfInferenceOutputKeypoint.fromJson(String source) =>
      FlutterAnycamTfInferenceOutputKeypoint.fromMap(
          json.decode(source) as Map<String, dynamic>);
}
