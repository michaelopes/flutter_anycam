import 'dart:typed_data';

import 'package:flutter_anycam/flutter_anycam.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('FlutterAnycamTfInferenceOutput.fromMap', () {
    test('accepts typed lists decoded by the platform codec', () {
      final output = FlutterAnycamTfInferenceOutput.fromMap({
        'score': 0.9,
        'embedding': Float32List.fromList([0.5, -1.5]),
        'segmentation': [
          Float64List.fromList([1, 2]),
          Float32List.fromList([3, 4]),
        ],
      });

      expect(output.embedding, [0.5, -1.5]);
      expect(output.segmentation, [
        [1.0, 2.0],
        [3.0, 4.0]
      ]);
    });

    test('accepts plain lists of ints and doubles', () {
      final output = FlutterAnycamTfInferenceOutput.fromMap({
        'score': 1,
        'embedding': <Object?>[1, 2.5, 3],
      });

      expect(output.score, 1.0);
      expect(output.embedding, [1.0, 2.5, 3.0]);
    });

    test('a typed embedding is copied, not aliased', () {
      final source = Float32List.fromList([1, 2]);
      final output = FlutterAnycamTfInferenceOutput.fromMap({
        'score': 1.0,
        'embedding': source,
      });
      source[0] = 99;
      expect(output.embedding!.first, 1.0);
    });

    test('missing optional fields default safely', () {
      final output = FlutterAnycamTfInferenceOutput.fromMap({});

      expect(output.score, 0.0);
      expect(output.box, isNull);
      expect(output.keypoint, isNull);
      expect(output.embedding, isNull);
      expect(output.segmentation, isNull);
      expect(output.text, isNull);
    });

    test('box coordinates may arrive as doubles', () {
      final output = FlutterAnycamTfInferenceOutput.fromMap({
        'score': 0.7,
        'box': {
          'xMin': 10.7,
          'yMin': 20.0,
          'xMax': 110,
          'yMax': 220.2,
          'srcWidth': 640.0,
          'srcHeight': 480,
        },
        'keypoint': {'x': 1, 'y': 2},
      });

      final box = output.box!;
      expect(box.xMin, 10);
      expect(box.yMax, 220);
      expect(box.srcWidth, 640);
      expect(box.classId, -1);
      expect(box.cropPadPercent, 0);
      expect(box.width, 100);
      expect(box.height, 200);
      expect(box.x, 10);
      expect(box.y, 20);
      expect(output.keypoint!.x, 1.0);
      expect(output.keypoint!.score, isNull);
    });

    test('survives a JSON round trip', () {
      final original = FlutterAnycamTfInferenceOutput(
        score: 0.25,
        text: 'face',
        embedding: [0.1, 0.2],
        segmentation: [
          [1, 0],
          [0, 1]
        ],
        keypoint: FlutterAnycamTfInferenceOutputKeypoint(3, 4, 0.5),
        box: FlutterAnycamTfInferenceOutputBox(
          xMin: 1,
          yMin: 2,
          xMax: 3,
          yMax: 4,
          srcWidth: 10,
          srcHeight: 20,
          classId: 2,
          cropPadPercent: 0.1,
          minScaledCropSize: const FlutterAnycamSize(112, 112),
        ),
      );

      final copy = FlutterAnycamTfInferenceOutput.fromJson(original.toJson());

      expect(copy.toMap(), original.toMap());
    });
  });

  group('FlutterAnycamTfInferenceResult.fromMap', () {
    test('decodes the nested output map from the channel', () {
      final result = FlutterAnycamTfInferenceResult.fromMap({
        'id': 'r',
        'output': <Object?, Object?>{'score': 0.5},
      });
      expect(result.id, 'r');
      expect(result.output.score, 0.5);
    });
  });
}
