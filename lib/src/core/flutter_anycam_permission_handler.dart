import '../channel/flutter_anycam_platform_interface.dart';

typedef PermissionCallback = void Function(bool result);

class FlutterAnycamPermissionHandler {
  FlutterAnycamPermissionHandler._internal();
  static final I = FlutterAnycamPermissionHandler._internal();
  bool _inProgress = false;

  final _listeners = <int, PermissionCallback>{};

  Future<void> requestPermission({
    required int viewId,
    PermissionCallback? onResult,
  }) async {
    _listeners[viewId] = onResult ?? (bool result) {};
    if (!_inProgress) {
      _inProgress = true;
      try {
        final res = await FlutterAnycamPlatform.instance.requestPermission();
        final listeners = Map<int, PermissionCallback>.from(_listeners);
        _listeners.clear();
        for (final item in listeners.values) {
          item(res);
        }
        // Only start cameras when permission was actually granted.
        if (res) {
          await FlutterAnycamPlatform.instance.broadcastPermissionGranted();
        }
      } catch (_) {
        final listeners = Map<int, PermissionCallback>.from(_listeners);
        _listeners.clear();
        for (final item in listeners.values) {
          item(false);
        }
      } finally {
        _inProgress = false;
      }
    }
  }
}
