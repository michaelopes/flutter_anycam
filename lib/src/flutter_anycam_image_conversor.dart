import 'dart:io';
import 'dart:typed_data';
import 'package:flutter_anycam/src/flutter_anycam_platform_interface.dart';
import 'flutter_anycam_frame.dart';

class FlutterAnycamFrameConversor {
  Future<Uint8List?> convertToJpeg({
    required FlutterAnycamFrame frame,
    int quality = 100,
  }) {
    return Platform.isIOS
        ? _bgra888ToJpeg(frame: frame, quality: quality)
        : _nv21ToJpeg(frame: frame, quality: quality);
  }

  Future<
      ({
        Uint8List bytes,
        int height,
        int width,
      })> jpegToNv21(Uint8List bytes) async {
    return FlutterAnycamPlatform.instance.convertJpegToNV21(
      bytes: bytes,
    );
  }

  Future<Uint8List?> _nv21ToJpeg({
    required FlutterAnycamFrame frame,
    int quality = 100,
  }) async {
    final nv21Bytes = frame.bytes;
    final width = frame.width;
    final height = frame.height;

    return FlutterAnycamPlatform.instance.convertNv21ToJpeg(
      bytes: nv21Bytes,
      width: width,
      height: height,
      quality: quality,
      rotation: frame.rotation,
    );
  }

  Future<Uint8List?> _bgra888ToJpeg({
    required FlutterAnycamFrame frame,
    int quality = 100,
  }) async {
    return FlutterAnycamPlatform.instance.convertBGRA8888ToJpeg(
      bytes: frame.bytes,
      width: frame.width,
      height: frame.height,
      quality: quality,
      rotation: 0,
    );
  }
}
