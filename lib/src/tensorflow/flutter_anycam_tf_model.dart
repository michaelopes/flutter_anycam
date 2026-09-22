import 'dart:math';

import '../channel/flutter_anycam_platform_interface.dart';
import '../core/flutter_anycam_size.dart';
import '../core/flutter_anycam_typedefs.dart';

import 'flutter_anycam_tf_delegate.dart';
import 'flutter_anycam_tf_session.dart';
import 'flutter_anycam_tf_tracker_options.dart';

class FlutterAnycamTfModel {
  static Future<FlutterAnycamTfSession> loadModel({
    required String assetPath,
    required FlutterAnycamSize inputSize,
    required TfOutputProcessor outputProcessor,
    FlutterAnycamTfTrackerOptions? trackerOptions,
    FlutterAnycamTfDelegate delegate = FlutterAnycamTfDelegate.nnapi,
    int threads = 1,
  }) async {
    final String key = generateRandomHash();
    await FlutterAnycamPlatform.instance.loadTfModel(
      assetPath: assetPath,
      key: key,
      delegate: delegate,
      threads: threads,
    );
    return FlutterAnycamTfSession(
      key,
      inputSize,
      outputProcessor,
      trackerOptions ?? FlutterAnycamTfTrackerOptions(),
    );
  }

  static String generateRandomHash({int length = 32}) {
    final random = Random.secure();
    final buffer = StringBuffer();

    for (int i = 0; i < length; i++) {
      final value = random.nextInt(16);
      buffer.write(value.toRadixString(16));
    }

    return buffer.toString();
  }
}
