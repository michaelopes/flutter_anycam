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

  FlutterAnycamStreamListenerDisposer addStreamListener(
    FlutterAnycamStreamListener listener,
  ) {
    throw UnimplementedError('addStreamListener() has not been implemented.');
  }
}
