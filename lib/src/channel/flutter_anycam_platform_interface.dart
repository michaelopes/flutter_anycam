import 'dart:typed_data';

import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import '../core/flutter_anycam_camera_selector.dart';
import '../core/flutter_anycam_crop.dart';
import '../core/flutter_anycam_filter.dart';
import '../core/flutter_anycam_size.dart';
import '../core/flutter_anycam_stream_listener.dart';
import '../core/flutter_anycam_typedefs.dart';
import '../tensorflow/flutter_anycam_tf_delegate.dart';
import '../tensorflow/flutter_anycam_tf_frame.dart';
import '../tensorflow/flutter_anycam_tf_normalize.dart';
import 'flutter_anycam_method_channel.dart';

abstract class FlutterAnycamPlatform extends PlatformInterface {
  /// Constructs a FlutterAnycamPlatform.
  FlutterAnycamPlatform() : super(token: _token);

  static final Object _token = Object();

  static FlutterAnycamPlatform _instance = MethodChannelFlutterAnycam();

  /// The default instance of [FlutterAnycamPlatform] to use.
  ///
  /// Defaults to [MethodChannelFlutterAnycam].
  static FlutterAnycamPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [FlutterAnycamPlatform] when
  /// they register themselves.
  static set instance(FlutterAnycamPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    if (_instance is MethodChannelFlutterAnycam) {
      (_instance as MethodChannelFlutterAnycam).dispose();
    }
    _instance = instance;
  }

  Future<List<FlutterAnycamCameraSelector>> availableCameras() {
    throw UnimplementedError('availableCameras() has not been implemented.');
  }

  Future<Uint8List?> convertNv21ToJpeg({
    required Uint8List bytes,
    required int width,
    required int height,
    required int rotation,
    FlutterAnycamFilter filter = FlutterAnycamFilter.none,
    FlutterAnycamCrop? crop,
    int quality = 100,
  }) {
    throw UnimplementedError('convertNv21ToJpeg() has not been implemented.');
  }

  Future<Uint8List?> convertBGRA8888ToJpeg({
    required Uint8List bytes,
    required int width,
    required int height,
    required int rotation,
    FlutterAnycamFilter filter = FlutterAnycamFilter.none,
    FlutterAnycamCrop? crop,
    int quality = 100,
  }) {
    throw UnimplementedError(
        'convertBGRA8888ToJpeg() has not been implemented.');
  }

  Future<void> setZoom(double value, String cameraId) {
    throw UnimplementedError('setZoom() has not been implemented.');
  }

  Future<void> setFlash(bool value) {
    throw UnimplementedError('setFlash() has not been implemented.');
  }

  Future<bool> requestPermission() {
    throw UnimplementedError('requestPermission() has not been implemented.');
  }

  Future<void> broadcastPermissionGranted() {
    throw UnimplementedError(
        'broadcastPermissionGranted() has not been implemented.');
  }

  FlutterAnycamStreamListenerDisposer addStreamListener(
    FlutterAnycamStreamListener listener,
  ) {
    throw UnimplementedError('addStreamListener() has not been implemented.');
  }

  Future<int?> createView(Map<String, dynamic> args) {
    throw UnimplementedError('createView() has not been implemented.');
  }

  Future<bool> disposeView(Map<String, dynamic> args) {
    throw UnimplementedError('disposeView() has not been implemented.');
  }

  Future<bool> registerRawStream(String cameraId, int fps) {
    throw UnimplementedError('registerRawStream() has not been implemented.');
  }

  Future<bool> disposeRawStream(String cameraId) {
    throw UnimplementedError('disposeRawStream() has not been implemented.');
  }

  Future<bool> loadTfModel({
    required String assetPath,
    required String key,
    FlutterAnycamTfDelegate delegate = FlutterAnycamTfDelegate.nnapi,
    int threads = 1,
  }) {
    throw UnimplementedError('loadTFModel() has not been implemented.');
  }

  Future<dynamic> runTfInference({
    required FlutterAnycamTfFrame inputFrame,
    required String modelKey,
    required FlutterAnycamSize inputSize,
    FlutterAnycamFilter filter = FlutterAnycamFilter.none,
    FlutterAnycamTfNormalize normalize = FlutterAnycamTfNormalize.none,
  }) {
    throw UnimplementedError('runTFInference() has not been implemented.');
  }

  Future<bool> disposeTfModel({
    required String key,
  }) {
    throw UnimplementedError('disposeTFModel() has not been implemented.');
  }

  Future<bool> closeTfFrame({
    required String frameId,
  }) {
    throw UnimplementedError('closeTfFrame() has not been implemented.');
  }

  Future<Map<String, dynamic>> getTfFrameJpeg({
    required String frameId,
  }) {
    throw UnimplementedError('getTfFrameJpeg() has not been implemented.');
  }

  Future<bool> closeTfInferenceResult({
    required String inferenceId,
  }) {
    throw UnimplementedError(
        'closeTfInferenceResult() has not been implemented.');
  }

  Future<Map<String, dynamic>?> getInferenceResultScaledCroppedFrame({
    required String id,
  }) {
    throw UnimplementedError(
        'getInferenceResultScaledCroppedFrame() has not been implemented.');
  }

  Future<Map<String, dynamic>?> getInferenceResultCroppedFrame({
    required String id,
  }) {
    throw UnimplementedError(
        'getInferenceResultCroppedFrame() has not been implemented.');
  }

  Future<Map<String, dynamic>?> getInferenceResultInferenceFrame({
    required String id,
  }) {
    throw UnimplementedError(
        'getInferenceResultInferenceFrame() has not been implemented.');
  }

  Future<Map<String, dynamic>?> getInferenceResultRawFrame({
    required String id,
  }) {
    throw UnimplementedError(
        'getInferenceResultRawFrame() has not been implemented.');
  }
}
