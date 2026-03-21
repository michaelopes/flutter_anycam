import 'dart:io';

class FlutterAnycamLifecycle {
  FlutterAnycamLifecycle({
    required this.onExecute,
  });

  void Function() onExecute;

  DateTime? _pauseDt;

  void pause() {
    _pauseDt = DateTime.now();
  }

  void resume() {
    if (_pauseDt != null) {
      final now = DateTime.now();
      final diff = now.difference(_pauseDt!).inMilliseconds;
      if (diff >= 200) {
        if (Platform.isAndroid) {
          onExecute();
        }
      }
      _pauseDt = null;
    }
  }
}
