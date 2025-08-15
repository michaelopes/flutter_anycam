import 'dart:io';
import 'dart:typed_data';

import 'package:imagekit_ffi/imagekit_ffi.dart';

import 'flutter_anycam_frame.dart';

class FlutterAnycamFrameConversor {
  final _conversor = ImageKitFfi();
  Future<Uint8List?> convertToJpeg({
    required FlutterAnycamFrame frame,
    int quality = 100,
  }) {
    return Platform.isIOS
        ? _bra888ToJpeg(frame: frame, quality: quality)
        : _n21ToJpeg(frame: frame, quality: quality);
  }

  Future<Uint8List?> _n21ToJpeg({
    required FlutterAnycamFrame frame,
    int quality = 100,
  }) async {
    final nv21Bytes = frame.bytes;
    final width = frame.width;
    final height = frame.height;

    final ySize = width * height;
    final yPlane = nv21Bytes.sublist(0, ySize);
    final uvPlane = nv21Bytes.sublist(ySize);

    return _conversor.convertNv21ToJpegBuffer(
      yPlane: yPlane,
      uvPlane: uvPlane,
      width: width,
      height: height,
      yStride: width,
      uvStride: width,
      uvPixStride: 2,
      rotationDegrees: frame.rotation,
      quality: quality,
    );
  }

  Future<Uint8List?> _bra888ToJpeg({
    required FlutterAnycamFrame frame,
    int quality = 100,
  }) async {
    return _conversor.encodeBgraToJpegBuffer(
      bgraBytes: frame.bytes,
      width: frame.width,
      height: frame.height,
      quality: quality,
    );
  }
}
