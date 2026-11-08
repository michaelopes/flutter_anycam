import 'dart:async';

import 'package:flutter/material.dart';

import 'flutter_anycam_typedefs.dart';

class FlutterAnycamMesure {
  final int seconds;

  int _counter = 0;
  Timer? _timer;

  FlutterAnycamMesure({this.seconds = 10});

  void startCounting({FlutterAnycamMesureCallback? callback}) {
    _counter = 0;
    _timer?.cancel();
    _timer = null;
    _timer = Timer.periodic(Duration(seconds: seconds), (timer) {
      debugPrint("Processed in $seconds seconds: $_counter");
      callback?.call(_counter);
      _counter = 0;
    });
  }

  void count() {
    _counter++;
  }

  void stopCounting() {
    _timer?.cancel();
  }

  int get counter => _counter;
}
