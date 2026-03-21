// ignore_for_file: public_member_api_docs, sort_constructors_first
import 'dart:convert';

import 'package:flutter/material.dart';

class FlutterAnycamPreviewInfo {
  final int width;
  final int height;
  final int rotation;
  final bool isPortrait;

  FlutterAnycamPreviewInfo({
    required this.width,
    required this.height,
    required this.rotation,
    required this.isPortrait,
  });

  Size get size => Size(width.toDouble(), height.toDouble());

  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'width': width,
      'height': height,
      'rotation': rotation,
      'isPortrait': isPortrait,
    };
  }

  factory FlutterAnycamPreviewInfo.fromMap(Map<String, dynamic> map) {
    return FlutterAnycamPreviewInfo(
      width: map['width'] as int,
      height: map['height'] as int,
      rotation: map['rotation'] as int,
      isPortrait: map['isPortrait'] as bool,
    );
  }

  String toJson() => json.encode(toMap());

  factory FlutterAnycamPreviewInfo.fromJson(String source) =>
      FlutterAnycamPreviewInfo.fromMap(
          json.decode(source) as Map<String, dynamic>);

  factory FlutterAnycamPreviewInfo.of(BuildContext context) {
    final size = MediaQuery.sizeOf(context);
    return FlutterAnycamPreviewInfo(
      width: size.height.toInt(),
      height: size.width.toInt(),
      isPortrait: size.height > size.width,
      rotation: 0,
    );
  }
}
