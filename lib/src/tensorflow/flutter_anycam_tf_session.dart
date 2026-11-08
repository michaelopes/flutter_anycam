import '../channel/flutter_anycam_platform_interface.dart';
import '../channel/flutter_anycam_tf_output_processor.dart';
import 'flutter_anycam_tf_bbox_tracker.dart';
import '../core/flutter_anycam_filter.dart';

import '../core/flutter_anycam_size.dart';
import '../core/flutter_anycam_typedefs.dart';
import 'flutter_anycam_tf_inference_result.dart';
import 'flutter_anycam_tf_frame.dart';
import 'flutter_anycam_tf_normalize.dart';
import 'flutter_anycam_tf_tracker_options.dart';

class FlutterAnycamTfSession {
  final String modelKey;
  final TfOutputProcessor outputProcessor;
  final FlutterAnycamSize inputSize;
  late final TfOutputListenerDisposer _listenerDisposer;
  final FlutterAnycamTfTrackerOptions trackerOptions;

  late final FlutterAnycamTfBBoxTracker _tracker;

  FlutterAnycamTfSession(
    this.modelKey,
    this.inputSize,
    this.outputProcessor,
    this.trackerOptions,
  ) {
    _tracker = FlutterAnycamTfBBoxTracker(
      threshold: trackerOptions.threshold,
      maxMissed: trackerOptions.maxMissed,
      minHits: trackerOptions.minHits,
      matchFn: trackerOptions.matchFn,
    );
    _listenerDisposer = FlutterAnycamTfOutputProcessor.I.addListener(
      modelKey: modelKey,
      listener: (data) async {
        final lst = outputProcessor(data, inputSize);
        final bboxes =
            lst.where((e) => e.box != null).map((e) => e.box!).toList();
        _tracker.update(bboxes);
        return lst.map((e) => e.toMap()).toList();
      },
    );
  }

  Future<List<FlutterAnycamTfInferenceResult>> runInference({
    required FlutterAnycamTfFrame inputFrame,
    FlutterAnycamFilter filter = FlutterAnycamFilter.none,
    FlutterAnycamTfNormalize normalize = FlutterAnycamTfNormalize.none,
  }) async {
    // Throws rather than returning []: output processors often publish results
    // as a side effect, and an empty reply would leave the previous ones visible.
    if (_disposed) throw StateError('FlutterAnycamTfSession $modelKey is disposed');
    final res = await FlutterAnycamPlatform.instance.runTfInference(
      inputFrame: inputFrame,
      filter: filter,
      inputSize: inputSize,
      modelKey: modelKey,
      normalize: normalize,
    );

    return List.from(res).map((e) {
      final map = Map<String, dynamic>.from(e);
      return FlutterAnycamTfInferenceResult.fromMap(map);
    }).toList();
    //final map = res as Map;
    //_copyMapValues(map, output);
  }

  bool _disposed = false;

  bool get isDisposed => _disposed;

  /// Releases the native model. Idempotent; never throws, so it is safe to call
  /// unawaited from `State.dispose`.
  Future<void> dispose() async {
    if (_disposed) return;
    _disposed = true;
    _listenerDisposer();
    _tracker.reset();
    try {
      await FlutterAnycamPlatform.instance.disposeTfModel(
        key: modelKey,
      );
    } catch (_) {}
  }

  static void cast(Map origem, Map destino) {
    for (var item in origem.entries) {
      final k = item.key;
      final value = item.value;
      final key = k is String ? int.parse(k) : k as int;
      if (destino.containsKey(key)) {
        if (value is Map && destino[key] is Map) {
          cast(value, destino[key]);
        } else if (value is List && destino[key] is List) {
          destino[key] = List.from(value);
        } else {
          destino[key] = value;
        }
      }
    }
  }
}
