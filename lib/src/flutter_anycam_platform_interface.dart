import 'dart:typed_data';

import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'flutter_anycam_camera_selector.dart';
import 'flutter_anycam_method_channel.dart';
import 'flutter_anycam_typedefs.dart';
import 'flutter_anycam_stream_listener.dart';

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
    _instance = instance;
  }

  Future<List<FlutterAnycamCameraSelector>> availableCameras() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }

  Future<Uint8List?> convertNv21ToJpeg({
    required Uint8List bytes,
    required int width,
    required int height,
    required int rotation,
    int quality = 100,
  }) {
    throw UnimplementedError('convertNv21ToJpeg() has not been implemented.');
  }

  Future<Uint8List?> convertBGRA8888ToJpeg({
    required Uint8List bytes,
    required int width,
    required int height,
    required int rotation,
    int quality = 100,
  }) {
    throw UnimplementedError(
        'convertBGRA8888ToJpeg() has not been implemented.');
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
}
