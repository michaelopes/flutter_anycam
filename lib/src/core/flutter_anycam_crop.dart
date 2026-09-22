import 'dart:math';

import 'flutter_anycam_size.dart';

final class FlutterAnycamCrop {
  int? _width;
  int? _height;
  int? _left;
  int? _top;
  FlutterAnycamSize? _resize;

  FlutterAnycamCrop({
    required int width,
    required int height,
    required int left,
    required int top,
    FlutterAnycamSize? resize,
  }) {
    _width = width;
    _height = height;
    _left = left;
    _top = top;
    _resize = resize;
  }

  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'width': _width,
      'height': _height,
      'left': _left,
      'top': _top,
      'resize': _resize?.toMap(),
    };
  }

  int get x => _left ?? 1;
  int get y => _top ?? 1;

  int get width => _width ?? 1;
  int get height => _height ?? 1;

  FlutterAnycamCrop.square() {
    _resize = FlutterAnycamSize.square();
  }

  FlutterAnycamCrop.resizeCenter({
    required int width,
    required int height,
  }) {
    _resize = FlutterAnycamSize(width, height);
  }

  FlutterAnycamCrop scaleCrop({
    required FlutterAnycamSize toSize,
    FlutterAnycamSize minSize = const FlutterAnycamSize(64, 62),
  }) {
    try {
      final double ratioW = this.width / toSize.width;
      final double ratioH = this.height / toSize.height;

      final double scale = min(ratioW, ratioH);

      final double newWidth = toSize.width * scale;
      final double newHeight = toSize.height * scale;

      final double padX = (this.width - newWidth) / 2;
      final double padY = (this.height - newHeight) / 2;

      // Remove letterbox
      double left = (x - padX) / scale;
      double top = (y - padY) / scale;
      double width = this.width / scale;
      double height = this.height / scale;

      // Centro real do bbox
      final double centerX = left + width / 2;
      final double centerY = top + height / 2;

      // Padding proporcional (16% cada lado)
      width *= 1.32;
      height *= 1.32;

      const double minCropWidth = 64;
      const double minCropHeight = 32;

      width = max(width, minCropWidth);
      height = max(height, minCropHeight);

      left = centerX - width / 2;
      top = centerY - height / 2;

      if (left < 0) {
        left = 0;
      } else if (left + width > toSize.width) {
        left = toSize.width - width;
      }

      if (top < 0) {
        top = 0;
      } else if (top + height > toSize.height) {
        top = toSize.height - height;
      }

      return FlutterAnycamCrop(
        left: max(0, left.toInt()),
        top: max(0, top.toInt()),
        width: min(toSize.width, width.toInt()),
        height: min(toSize.height, height.toInt()),
      );
    } catch (e) {
      rethrow;
    }
  }

  @override
  String toString() {
    return 'LprBoundingBox(left: $_left, topy: $_top, width: $_width, height: $_height)';
  }
}
