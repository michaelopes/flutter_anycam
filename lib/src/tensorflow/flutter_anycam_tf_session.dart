import '../channel/flutter_anycam_platform_interface.dart';
import '../channel/flutter_anycam_tf_output_processor.dart';
import '../core/flutter_anycam_filter.dart';

import '../core/flutter_anycam_size.dart';
import '../core/flutter_anycam_typedefs.dart';
import 'flutter_anycam_tf_inference_result.dart';
import 'flutter_anycam_tf_frame.dart';
import 'flutter_anycam_tf_normalize.dart';

class FlutterAnycamTfSession {
  final String modelKey;
  final TfOutputProcessor outputProcessor;
  final FlutterAnycamSize inputSize;
  late final TfOutputListenerDisposer _listenerDisposer;

  FlutterAnycamTfSession(this.modelKey, this.inputSize, this.outputProcessor) {
    _listenerDisposer = FlutterAnycamTfOutputProcessor.I.addListener(
      modelKey: modelKey,
      listener: (data) async {
        final lst = outputProcessor(data, inputSize);
        return lst.map((e) => e.toMap()).toList();
      },
    );
  }

  Future<List<FlutterAnycamTfInferenceResult>> runInference({
    required FlutterAnycamTfFrame inputFrame,
    FlutterAnycamFilter filter = FlutterAnycamFilter.none,
    FlutterAnycamTfNormalize normalize = FlutterAnycamTfNormalize.none,
  }) async {
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

  Future<void> dispose() async {
    _listenerDisposer();
    await FlutterAnycamPlatform.instance.disposeTfModel(
      key: modelKey,
    );
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
