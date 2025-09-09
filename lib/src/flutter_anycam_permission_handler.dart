import 'flutter_anycam_platform_interface.dart';

typedef PermissionCallback = void Function(bool result);

class FlutterAnycamPermissionHandler {
  FlutterAnycamPermissionHandler._internal();
  static final I = FlutterAnycamPermissionHandler._internal();
  bool _inProgress = false;

  final _listeners = <int, PermissionCallback>{};

  Future<void> requestPermission({
    required int viewId,
    required PermissionCallback onResult,
  }) async {
    _listeners[viewId] = onResult;
    if (!_inProgress) {
      _inProgress = true;
      try {
        final res = await FlutterAnycamPlatform.instance.requestPermission();
        for (var item in _listeners.values) {
          item(res);
        }
      } catch (_) {
        for (var item in _listeners.values) {
          item(false);
        }
      } finally {
        _inProgress = false;
      }
    }
  }
}
