import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_anycam/flutter_anycam.dart';

// ignore_for_file: public_member_api_docs, sort_constructors_first
class FlutterAnycamTfFrame {
  final String id;
  final int width;
  final int height;

  final int? rawWidth;
  final int? rawHeight;

  bool _closed = false;

  FlutterAnycamTfFrame(
    this.id,
    this.width,
    this.height, {
    this.rawWidth,
    this.rawHeight,
  });

  Future<void> close() async {
    _closed = true;
    await FlutterAnycamPlatform.instance.closeTfFrame(frameId: id);
  }

  Future<Uint8List?> getJpeg() async {
    if (_closed) return null;
    final res =
        await FlutterAnycamPlatform.instance.getTfFrameJpeg(frameId: id);
    if (res["bytes"] != null) {
      return res["bytes"];
    }
    return null;
  }

  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'id': id,
      'width': width,
      'height': height,
      'rawWidth': rawWidth,
      'rawHeight': rawHeight,
    };
  }

  factory FlutterAnycamTfFrame.fromMap(Map<String, dynamic> map) {
    return FlutterAnycamTfFrame(
      map['id'] as String,
      map['width'] as int,
      map['height'] as int,
      rawWidth: map['rawWidth'],
      rawHeight: map['rawHeight'],
    );
  }

  bool get hasRawFrame => rawWidth != null && rawHeight != null;

  FlutterAnycamSize? get rawFrameSize {
    if (hasRawFrame) {
      return FlutterAnycamSize(rawWidth!, rawHeight!);
    }
    return null;
  }

  String toJson() => json.encode(toMap());

  factory FlutterAnycamTfFrame.fromJson(String source) =>
      FlutterAnycamTfFrame.fromMap(json.decode(source) as Map<String, dynamic>);
}
