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

  FlutterAnycamCrop.square() {
    _resize = FlutterAnycamSize.square();
  }

  FlutterAnycamCrop.resizeCenter({
    required int width,
    required int height,
  }) {
    _resize = FlutterAnycamSize(width, height);
  }

  @override
  String toString() {
    return 'LprBoundingBox(left: $_left, topy: $_top, width: $_width, height: $_height)';
  }
}
