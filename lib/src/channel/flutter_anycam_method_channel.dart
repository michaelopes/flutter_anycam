import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import '../core/flutter_anycam_size.dart';
import 'flutter_anycam_event_stream.dart';
import '../tensorflow/flutter_anycam_tf_delegate.dart';
import '../tensorflow/flutter_anycam_tf_frame.dart';
import '../tensorflow/flutter_anycam_tf_normalize.dart';
import 'flutter_anycam_platform_interface.dart';
import '../core/flutter_anycam_camera_selector.dart';
import '../core/flutter_anycam_crop.dart';
import '../core/flutter_anycam_filter.dart';
import '../core/flutter_anycam_stream_listener.dart';
import '../core/flutter_anycam_typedefs.dart';
import 'flutter_anycam_tf_output_processor.dart';

class MethodChannelFlutterAnycam extends FlutterAnycamPlatform {
  MethodChannelFlutterAnycam() {
    FlutterAnycamEventStream.I.add(_listen);
    FlutterAnycamTfOutputProcessor.I.setChannel(methodChannel);
  }

  final _streamListeners = <FlutterAnycamStreamListener>[];

  @visibleForTesting
  final methodChannel =
      const MethodChannel('br.dev.michaellopes.flutter_anycam/channel');

  void _listen(ldata) {
    final viewId = ldata["viewId"] as int?;
    final method = ldata["method"] as String?;
    final data = Map<String, dynamic>.from(ldata["data"] ?? {});
    if (viewId != null && method != null) {
      final listeners =
          _streamListeners.where((e) => e.viewId == viewId || e.viewId == -1);

      for (var listener in listeners) {
        final methods = {
          "onConnected": listener.onConnected,
          "onDisconnected": listener.onDisconnected,
          "onUnauthorized": listener.onUnauthorized,
          "onFailed": listener.onFailed,
          "onVideoFrameReceived": listener.onVideoFrameReceived,
          "onCameraRawFrame": listener.onCameraRawFrame,
        };
        if (methods[method] != null) {
          methods[method]!(data);
        }
      }
    }
  }

  void dispose() {
    FlutterAnycamEventStream.I.remove(_listen);
  }

  @override
  FlutterAnycamStreamListenerDisposer addStreamListener(
    FlutterAnycamStreamListener listener,
  ) {
    _streamListeners.add(listener);
    return () {
      _streamListeners.remove(listener);
    };
  }

  @override
  Future<List<FlutterAnycamCameraSelector>> availableCameras() async {
    final list = (await methodChannel.invokeMethod('availableCameras'));
    if (list != null && list.isNotEmpty) {
      return (list as List)
          .map(
            (e) => FlutterAnycamCameraSelector.fromMap(
              Map<String, dynamic>.from(e),
            ),
          )
          .toList();
    }
    return [];
  }

  @override
  Future<void> setFlash(bool value) async {
    await methodChannel.invokeMethod('setFlash', {"value": value});
  }

  @override
  Future<int?> createView(Map<String, dynamic> args) async {
    final result = await methodChannel.invokeMethod('createView', args);
    return result;
  }

  @override
  Future<bool> disposeView(Map<String, dynamic> args) async {
    final res = await methodChannel.invokeMethod('disposeView', args);
    return res ?? false;
  }

  @override
  Future<Uint8List?> convertBGRA8888ToJpeg({
    required Uint8List bytes,
    required int width,
    required int height,
    required int rotation,
    FlutterAnycamFilter filter = FlutterAnycamFilter.none,
    FlutterAnycamCrop? crop,
    int quality = 100,
  }) async {
    final result = (await methodChannel.invokeMethod(
      'convertBGRA8888ToJpeg',
      {
        "bytes": bytes,
        "width": width,
        "height": height,
        "rotation": rotation.toDouble(),
        "quality": quality,
        "filter": filter.code,
        "crop": crop?.toMap()
      },
    ));
    return result;
  }

  @override
  Future<Uint8List?> convertNv21ToJpeg({
    required Uint8List bytes,
    required int width,
    required int height,
    required int rotation,
    FlutterAnycamFilter filter = FlutterAnycamFilter.none,
    FlutterAnycamCrop? crop,
    int quality = 100,
  }) async {
    final result = (await methodChannel.invokeMethod(
      'convertNv21ToJpeg',
      {
        "bytes": bytes,
        "width": width,
        "height": height,
        "rotation": rotation.toDouble(),
        "quality": quality,
        "filter": filter.code,
        "crop": crop?.toMap()
      },
    ));
    return result;
  }

  @override
  Future<bool> requestPermission() async {
    final res = await methodChannel.invokeMethod('requestPermission');
    return res ?? false;
  }

  @override
  Future<void> broadcastPermissionGranted() async {
    await methodChannel.invokeMethod('broadcastPermissionGranted');
  }

  @override
  Future<bool> registerRawStream(String cameraId, int fps) async {
    return await methodChannel.invokeMethod(
      'registerRawStream',
      {
        "cameraId": cameraId,
        "fps": fps,
      },
    );
  }

  @override
  Future<bool> disposeRawStream(String cameraId) async {
    return await methodChannel.invokeMethod(
      'disposeRawStream',
      {
        "cameraId": cameraId,
      },
    );
  }

  @override
  Future<void> setExposureCompensation(int value, String cameraId) async {
    await methodChannel.invokeMethod(
      'setExposureCompensation',
      {
        "cameraId": cameraId,
        "value": value,
      },
    );
  }

  @override
  Future<void> setZoom(double value, String cameraId) async {
    await methodChannel.invokeMethod(
      'setZoom',
      {
        "cameraId": cameraId,
        "zoom": value,
      },
    );
  }

  @override
  Future<bool> loadTfModel({
    required String assetPath,
    required String key,
    FlutterAnycamTfDelegate delegate = FlutterAnycamTfDelegate.nnapi,
    int threads = 1,
  }) async {
    return await methodChannel.invokeMethod(
      'loadTfModel',
      {
        "assetPath": assetPath,
        "key": key,
        "delegate": delegate.value,
        "threads": threads,
      },
    );
  }

  @override
  Future<dynamic> runTfInference({
    required FlutterAnycamTfFrame inputFrame,
    required FlutterAnycamSize inputSize,
    required String modelKey,
    FlutterAnycamFilter filter = FlutterAnycamFilter.none,
    FlutterAnycamTfNormalize normalize = FlutterAnycamTfNormalize.none,
  }) {
    return methodChannel.invokeMethod(
      'runTfInference',
      {
        "inputFrame": inputFrame.toMap(),
        "modelKey": modelKey,
        "filter": filter.code,
        "inputSize": inputSize.toMap(),
        "normalize": normalize.value,
      },
    );
  }

  @override
  Future<bool> disposeTfModel({
    required String key,
  }) async {
    return await methodChannel.invokeMethod(
      'disposeTfModel',
      {"key": key},
    );
  }

  @override
  Future<bool> closeTfFrame({
    required String frameId,
  }) async {
    return await methodChannel.invokeMethod(
      'closeTfFrame',
      {"id": frameId},
    );
  }

  @override
  Future<Map<String, dynamic>> getTfFrameJpeg({
    required String frameId,
  }) async {
    final res = await methodChannel.invokeMethod(
      'getTfFrameJpeg',
      {"id": frameId},
    );
    return Map<String, dynamic>.from(res);
  }

  @override
  Future<bool> closeTfInferenceResult({
    required String inferenceId,
  }) async {
    return await methodChannel.invokeMethod(
      'closeTfInferenceResult',
      {"id": inferenceId},
    );
  }

  @override
  Future<Map<String, dynamic>?> getInferenceResultScaledCroppedFrame({
    required String id,
  }) async {
    final res = await methodChannel.invokeMethod(
      'getInferenceResultScaledCroppedFrame',
      {"id": id},
    );
    return res != null ? Map<String, dynamic>.from(res) : null;
  }

  @override
  Future<Map<String, dynamic>?> getInferenceResultCroppedFrame({
    required String id,
  }) async {
    final res = await methodChannel.invokeMethod(
      'getInferenceResultCroppedFrame',
      {"id": id},
    );
    return res != null ? Map<String, dynamic>.from(res) : null;
  }

  @override
  Future<Map<String, dynamic>?> getInferenceResultInferenceFrame({
    required String id,
  }) async {
    final res = await methodChannel.invokeMethod(
      'getInferenceResultInferenceFrame',
      {"id": id},
    );
    return res != null ? Map<String, dynamic>.from(res) : null;
  }

  @override
  Future<Map<String, dynamic>?> getInferenceResultRawFrame({
    required String id,
  }) async {
    final res = await methodChannel.invokeMethod(
      'getInferenceResultRawFrame',
      {"id": id},
    );
    return res != null ? Map<String, dynamic>.from(res) : null;
  }
}
