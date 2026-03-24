// ignore_for_file: public_member_api_docs, sort_constructors_first
import 'dart:convert';

class FlutterAnycamTfInferenceOutput {
  final FlutterAnycamTfInferenceOutputBox? box;
  final FlutterAnycamTfInferenceOutputKeypoint? keypoint;
  final List<double>? embedding;
  final List<List<double>>? segmentation;
  final String? text;

  FlutterAnycamTfInferenceOutput({
    this.box,
    this.keypoint,
    this.embedding,
    this.segmentation,
    this.text,
  });

  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'box': box?.toMap(),
      'keypoint': keypoint?.toMap(),
      'embedding': embedding,
      'segmentation': segmentation,
      'text': text,
    };
  }

  factory FlutterAnycamTfInferenceOutput.fromMap(Map<String, dynamic> map) {
    return FlutterAnycamTfInferenceOutput(
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
          ? List<double>.from((map['embedding'] as List<double>))
          : null,
      segmentation: map['segmentation'] != null
          ? List<List<double>>.from(
              (map['segmentation'])
                  .map(
                    (x) => List<double>.from(x),
                  )
                  .toList(),
            )
          : null,
      text: map['text'] != null ? map['text'] as String : null,
    );
  }

  String toJson() => json.encode(toMap());

  factory FlutterAnycamTfInferenceOutput.fromJson(String source) =>
      FlutterAnycamTfInferenceOutput.fromMap(
          json.decode(source) as Map<String, dynamic>);
}

class FlutterAnycamTfInferenceOutputBox {
  final int xMin;
  final int yMin;
  final int xMax;
  final int yMax;
  final int classId;
  final int srcWidth;
  final int srcHeight;
  final double score;

  FlutterAnycamTfInferenceOutputBox({
    required this.xMin,
    required this.yMin,
    required this.xMax,
    required this.yMax,
    required this.srcWidth,
    required this.srcHeight,
    required this.score,
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
      'score': score,
      'classId': classId,
    };
  }

  factory FlutterAnycamTfInferenceOutputBox.fromMap(Map<String, dynamic> map) {
    return FlutterAnycamTfInferenceOutputBox(
      xMin: map['xMin'],
      yMin: map['yMin'],
      xMax: map['xMax'],
      yMax: map['yMax'],
      srcWidth: map['srcWidth'],
      srcHeight: map['srcHeight'],
      score: map['score'] as double,
      classId: map['classId'] as int,
    );
  }

  String toJson() => json.encode(toMap());

  factory FlutterAnycamTfInferenceOutputBox.fromJson(String source) =>
      FlutterAnycamTfInferenceOutputBox.fromMap(
          json.decode(source) as Map<String, dynamic>);
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
      map['x'] as double,
      map['y'] as double,
      map['score'] != null ? map['score'] as double : null,
    );
  }

  String toJson() => json.encode(toMap());

  factory FlutterAnycamTfInferenceOutputKeypoint.fromJson(String source) =>
      FlutterAnycamTfInferenceOutputKeypoint.fromMap(
          json.decode(source) as Map<String, dynamic>);
}
