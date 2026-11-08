import 'dart:typed_data';

class FlutterAnycamWebRtcI420Image {
  final int width;
  final int height;
  final int rotation;

  final Uint8List dataY;
  final int strideY;

  final Uint8List dataU;
  final int strideU;

  final Uint8List dataV;
  final int strideV;

  final int pixelStrideU;
  final int pixelStrideV;

  FlutterAnycamWebRtcI420Image({
    required this.width,
    required this.height,
    required this.rotation,
    required this.dataY,
    required this.strideY,
    required this.dataU,
    required this.strideU,
    required this.dataV,
    required this.strideV,
    required this.pixelStrideU,
    required this.pixelStrideV,
  });

  Map<String, dynamic> toMap({String? streamId}) {
    return <String, dynamic>{
      'width': width,
      'height': height,
      'rotation': rotation,
      'dataY': dataY,
      'strideY': strideY,
      'dataU': dataU,
      'strideU': strideU,
      'dataV': dataV,
      'strideV': strideV,
      'pixelStrideU': pixelStrideU,
      'pixelStrideV': pixelStrideV,
      if (streamId != null) 'streamId': streamId,
    };
  }
}
