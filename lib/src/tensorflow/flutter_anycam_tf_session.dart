import '../../flutter_anycam.dart';
import 'flutter_anycam_tf_frame.dart';

class FlutterAnycamTfSession {
  final String modelKey;

  FlutterAnycamTfSession(this.modelKey);

  Future<void> runInference({
    required FlutterAnycamTfFrame inputFrame,
    required FlutterAnycamSize inputSize,
    required Map<int, Object> output,
    FlutterAnycamFilter filter = FlutterAnycamFilter.none,
    FlutterAnycamCrop? crop,
  }) async {
    final res = await FlutterAnycamPlatform.instance.runTfInference(
      inputFrame: inputFrame,
      inputSize: inputSize,
      filter: filter,
      crop: crop,
      modelKey: modelKey,
    );
    final map = res as Map;
    _copyMapValues(map, output);
  }

  Future<void> dispose() async {
    await FlutterAnycamPlatform.instance.disposeTfModel(
      key: modelKey,
    );
  }

  void _copyMapValues(Map origem, Map destino) {
    for (var item in origem.entries) {
      final k = item.key;
      final value = item.value;
      final key = k is String ? int.parse(k) : k as int;
      if (destino.containsKey(key)) {
        if (value is Map && destino[key] is Map) {
          _copyMapValues(value, destino[key]);
        } else if (value is List && destino[key] is List) {
          destino[key] = List.from(value);
        } else {
          destino[key] = value;
        }
      }
    }
  }
}
