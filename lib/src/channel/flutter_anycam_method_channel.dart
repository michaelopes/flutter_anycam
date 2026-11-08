import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import '../core/flutter_anycam_size.dart';
import 'flutter_anycam_event_stream.dart';
import '../tensorflow/flutter_anycam_tf_blur_roi.dart';
import '../tensorflow/flutter_anycam_tf_delegate.dart';
import '../tensorflow/flutter_anycam_tf_frame.dart';
import '../tensorflow/flutter_anycam_tf_normalize.dart';
import 'flutter_anycam_platform_interface.dart';
import '../core/flutter_anycam_camera_selector.dart';
import '../core/flutter_anycam_crop.dart';
import '../core/flutter_anycam_filter.dart';
import '../core/flutter_anycam_stream_listener.dart';
import '../core/flutter_anycam_typedefs.dart';
import '../webrtc/flutter_anycam_webrtc_callbacks.dart';
import '../webrtc/flutter_anycam_webrtc_ice_server.dart';
import 'flutter_anycam_tf_output_processor.dart';
import 'flutter_anycam_webrtc_callback_processor.dart';

class MethodChannelFlutterAnycam extends FlutterAnycamPlatform {
  MethodChannelFlutterAnycam() {
    FlutterAnycamEventStream.I.add(_listen);
    methodChannel.setMethodCallHandler((call) async {
      if (call.method == 'processTfOutput') {
        return FlutterAnycamTfOutputProcessor.I.notify(call.arguments);
      }
      return FlutterAnycamWebRtcCallbackProcessor.I.handle(call);
    });
  }

  final _streamListeners = <FlutterAnycamStreamListener>[];

  @visibleForTesting
  final methodChannel =
      const MethodChannel('br.dev.michaellopes.flutter_anycam/channel');

  static FlutterAnycamStreamMethod? _handlerFor(
    FlutterAnycamStreamListener listener,
    String method,
  ) {
    switch (method) {
      case "onConnected":
        return listener.onConnected;
      case "onDisconnected":
        return listener.onDisconnected;
      case "onUnauthorized":
        return listener.onUnauthorized;
      case "onFailed":
        return listener.onFailed;
      case "onVideoFrameReceived":
        return listener.onVideoFrameReceived;
      case "onCameraRawFrame":
        return listener.onCameraRawFrame;
      case "onTfCameraStreamConnected":
        return listener.onTfCameraStreamConnected;
      case "onTfCameraStreamFrame":
        return listener.onTfCameraStreamFrame;
      case "onTfCameraStreamFailed":
        return listener.onTfCameraStreamFailed;
    }
    return null;
  }

  /// A native TF frame carries only an id; nothing frees it unless Dart calls
  /// closeTfFrame.
  static bool _isTfFrame(String method, Map<String, dynamic> data) {
    if (method != "onVideoFrameReceived" && method != "onTfCameraStreamFrame") {
      return false;
    }
    return data["id"] is String &&
        !data.containsKey("bytes") &&
        !data.containsKey("planes");
  }

  @visibleForTesting
  void dispatchEvent(dynamic event) => _listen(event);

  void _listen(dynamic ldata) {
    if (ldata is! Map) return;
    final viewId = ldata["viewId"];
    final method = ldata["method"];
    if (viewId is! int || method is! String) return;
    final rawData = ldata["data"];
    final data =
        rawData is Map ? Map<String, dynamic>.from(rawData) : <String, dynamic>{};

    // Snapshot: a handler may add or remove listeners (e.g. dispose a widget).
    final listeners = _streamListeners
        .where((e) => e.viewId == viewId || e.viewId == -1)
        .toList(growable: false);

    var handled = false;
    for (final listener in listeners) {
      final handler = _handlerFor(listener, method);
      if (handler == null) continue;
      handled = true;
      try {
        handler(data);
      } catch (e, s) {
        FlutterError.reportError(FlutterErrorDetails(
          exception: e,
          stack: s,
          library: 'flutter_anycam',
          context: ErrorDescription('while dispatching $method to view $viewId'),
        ));
      }
    }

    if (!handled && _isTfFrame(method, data)) {
      closeTfFrame(frameId: data["id"] as String).catchError((_) => false);
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
    final res = await methodChannel.invokeMethod<bool>(
      'registerRawStream',
      {
        "cameraId": cameraId,
        "fps": fps,
      },
    );
    return res ?? false;
  }

  @override
  Future<bool> disposeRawStream(String cameraId) async {
    final res = await methodChannel.invokeMethod<bool>(
      'disposeRawStream',
      {
        "cameraId": cameraId,
      },
    );
    return res ?? false;
  }

  @override
  Future<bool> registerTfCameraStream({
    required String cameraId,
    required int fps,
    required FlutterAnycamSize preferredSize,
    required FlutterAnycamFilter filter,
    required FlutterAnycamCameraSelector camera,
  }) async {
    final res = await methodChannel.invokeMethod<bool>(
      'registerTfCameraStream',
      {
        "cameraId": cameraId,
        "fps": fps,
        "preferredSize": preferredSize.toMap(),
        "filter": filter.code,
        "sensorOrientation": camera.sensorOrientation,
        "forceSensorOrientation": camera.toMap()["forceSensorOrientation"] ?? false,
      },
    );
    return res ?? false;
  }

  @override
  Future<bool> disposeTfCameraStream(String cameraId) async {
    final res = await methodChannel.invokeMethod<bool>(
      'disposeTfCameraStream',
      {
        "cameraId": cameraId,
      },
    );
    return res ?? false;
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
    final res = await methodChannel.invokeMethod<bool>(
      'loadTfModel',
      {
        "assetPath": assetPath,
        "key": key,
        "delegate": delegate.value,
        "threads": threads,
      },
    );
    return res ?? false;
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
    final res = await methodChannel.invokeMethod<bool>(
      'disposeTfModel',
      {"key": key},
    );
    return res ?? false;
  }

  @override
  Future<Map<String, dynamic>?> registerTfFrameFromJpeg({
    required Uint8List jpegBytes,
  }) async {
    final res = await methodChannel.invokeMethod(
      'registerTfFrameFromJpeg',
      {'jpegBytes': jpegBytes},
    );
    if (res == null) return null;
    return Map<String, dynamic>.from(res as Map);
  }

  @override
  Future<Map<String, dynamic>?> registerTfFrameCopy({
    required String frameId,
  }) async {
    final res = await methodChannel.invokeMethod(
      'registerTfFrameCopy',
      {'frameId': frameId},
    );
    if (res == null) return null;
    return Map<String, dynamic>.from(res as Map);
  }

  @override
  Future<Map<String, dynamic>?> registerTfFrameCrop({
    required String frameId,
    required int x,
    required int y,
    required int width,
    required int height,
    FlutterAnycamSize? resizeTo,
  }) async {
    final res = await methodChannel.invokeMethod(
      'registerTfFrameCrop',
      {
        'frameId': frameId,
        'x': x,
        'y': y,
        'width': width,
        'height': height,
        if (resizeTo != null) 'resizeTo': resizeTo.toMap(),
      },
    );
    if (res == null) return null;
    return Map<String, dynamic>.from(res as Map);
  }

  @override
  Future<bool> closeTfFrame({
    required String frameId,
  }) async {
    final res = await methodChannel.invokeMethod<bool>(
      'closeTfFrame',
      {"id": frameId},
    );
    return res ?? false;
  }

  @override
  Future<Map<String, dynamic>> getTfFrameJpeg({
    required String frameId,
  }) async {
    final res = await methodChannel.invokeMethod(
      'getTfFrameJpeg',
      {"id": frameId},
    );
    if (res is! Map) return <String, dynamic>{"bytes": null};
    return Map<String, dynamic>.from(res);
  }

  @override
  Future<double?> getTfFrameBlurScore({
    required String frameId,
    FlutterAnycamTfBlurRoi? roi,
    int sampleStep = 2,
  }) async {
    final res = await methodChannel.invokeMethod(
      'getTfFrameBlurScore',
      {
        "id": frameId,
        "sampleStep": sampleStep,
        if (roi != null) "roi": roi.toMap(),
      },
    );
    if (res == null) return null;
    return (res as num).toDouble();
  }

  @override
  Future<Map<String, dynamic>?> getTfFrameIlluminationScore({
    required String frameId,
    FlutterAnycamTfBlurRoi? roi,
    int sampleStep = 2,
  }) async {
    final res = await methodChannel.invokeMethod(
      'getTfFrameIlluminationScore',
      {
        "id": frameId,
        "sampleStep": sampleStep,
        if (roi != null) "roi": roi.toMap(),
      },
    );
    if (res == null) return null;
    return Map<String, dynamic>.from(res as Map);
  }

  @override
  Future<bool> closeTfInferenceResult({
    required String inferenceId,
  }) async {
    final res = await methodChannel.invokeMethod<bool>(
      'closeTfInferenceResult',
      {"id": inferenceId},
    );
    return res ?? false;
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

  @override
  Future<String?> newWebRtcStream({
    List<FlutterAnycamWebRtcIceServer>? iceServers,
  }) {
    return methodChannel.invokeMethod<String?>('newWebRtcStream', {
      'iceServers': iceServers?.map((e) => e.toMap()).toList(),
    });
  }

  @override
  Future<bool?> stopWebRtcStream(String streamId) {
    return methodChannel.invokeMethod<bool>('stopWebRtcStream', {
      'streamId': streamId,
    });
  }

  @override
  Future<String?> createWebRtcAnswer(
    String streamId,
    Map<String, dynamic> data,
  ) async {
    return await methodChannel.invokeMethod<String>('createWebRtcAnswer', {
      'streamId': streamId,
      ...data,
    });
  }

  @override
  Future<bool?> addWebRtcCandidate(
    String streamId,
    Map<String, dynamic> data,
  ) async {
    return await methodChannel.invokeMethod<bool>('addWebRtcCandidate', {
      'streamId': streamId,
      ...data,
    });
  }

  @override
  Future<bool?> pushWebRtcFrame(Map<String, dynamic> data) async {
    return await methodChannel.invokeMethod<bool>('pushWebRtcFrame', data);
  }

  @override
  Future<bool?> sendWebRtcDataMessage(
    String streamId,
    Map<String, dynamic> data,
  ) async {
    return await methodChannel.invokeMethod<bool>('sendWebRtcDataMessage', {
      'streamId': streamId,
      'message': jsonEncode(data),
    });
  }

  @override
  FlutterAnycamWebRtcCallbacksDisposer addWebRtcCallbacks({
    required String streamId,
    required FlutterAnycamWebRtcCallbacks callbacks,
  }) {
    return FlutterAnycamWebRtcCallbackProcessor.I.addCallbacks(
      streamId: streamId,
      callbacks: callbacks,
    );
  }

  @override
  Future<bool> registerWebRtcCameraFeed({
    required String cameraId,
    required int fps,
    bool alsoDeliverToFlutter = false,
    FlutterAnycamSize? streamSize,
  }) async {
    final result = await methodChannel.invokeMethod<bool>(
      'registerWebRtcCameraFeed',
      {
        'cameraId': cameraId,
        'fps': fps,
        'alsoDeliverToFlutter': alsoDeliverToFlutter,
        if (streamSize != null) 'streamSize': streamSize.toMap(),
      },
    );
    return result ?? false;
  }

  @override
  Future<bool> disposeWebRtcCameraFeed(String cameraId) async {
    final result = await methodChannel.invokeMethod<bool>(
      'disposeWebRtcCameraFeed',
      {'cameraId': cameraId},
    );
    return result ?? false;
  }
}
