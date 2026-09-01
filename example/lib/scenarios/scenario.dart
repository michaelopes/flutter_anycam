import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_anycam/flutter_anycam.dart';

enum ScenarioPlatform { android, ios }

class Scenario {
  const Scenario({
    required this.id,
    required this.title,
    required this.subtitle,
    required this.checklist,
    required this.builder,
    this.platforms = const {ScenarioPlatform.android, ScenarioPlatform.ios},
    this.androidOnlyReason,
  });

  final String id;
  final String title;
  final String subtitle;
  final List<String> checklist;
  final Set<ScenarioPlatform> platforms;
  final String? androidOnlyReason;
  final Widget Function(List<FlutterAnycamCameraSelector> cameras) builder;

  bool get isAvailableOnThisPlatform {
    if (Platform.isAndroid) {
      return platforms.contains(ScenarioPlatform.android);
    }
    if (Platform.isIOS) {
      return platforms.contains(ScenarioPlatform.ios);
    }
    return false;
  }

  String get unavailableReason {
    if (androidOnlyReason != null) return androidOnlyReason!;
    return 'Not supported on this platform';
  }
}
