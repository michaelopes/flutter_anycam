// ignore_for_file: public_member_api_docs, sort_constructors_first
import 'dart:typed_data';

class FlutterAnycamFrame {
  final String format;
  final int width;
  final int height;
  final int rotation;
  final List<FlutterAnycamPlane> planes;
  final Uint8List bytes;
  final Map<String, dynamic>? extras;

  FlutterAnycamFrame({
    required this.format,
    required this.width,
    required this.height,
    required this.planes,
    required this.bytes,
    required this.rotation,
    this.extras,
  });

  factory FlutterAnycamFrame.fromMap(Map<String, dynamic> map) {
    return FlutterAnycamFrame(
      format: map['format'] as String,
      width: map['width'] as int,
      height: map['height'] as int,
      rotation: map['rotation'] as int,
      bytes: Uint8List.fromList(map['bytes']),
      extras: map['extras'] != null
          ? Map<String, dynamic>.from(map['extras'])
          : null,
      planes: map['planes'] != null
          ? (map['planes'] as List<dynamic>)
              .map((planeMap) => FlutterAnycamPlane.fromMap(
                  Map<String, dynamic>.from(planeMap)))
              .toList()
          : [],
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'format': format,
      'bytes': bytes.toList(),
      'width': width,
      'height': height,
      'rotation': rotation,
      'planes': planes.map((p) => p.toMap()).toList(),
      'extras': extras,
    };
  }

  @override
  String toString() {
    return 'FlutterAnycamFrame(format: $format, width: $width, height: $height, rotation: $rotation, bytes: [bytes(${bytes.length})])';
  }
}

class FlutterAnycamPlane {
  final Uint8List bytes;
  final int bytesPerRow;
  final int pixelStride;
  final int rowStride;

  FlutterAnycamPlane({
    required this.bytes,
    required this.bytesPerRow,
    required this.pixelStride,
    required this.rowStride,
  });

  factory FlutterAnycamPlane.fromMap(Map<String, dynamic> map) {
    return FlutterAnycamPlane(
      bytes: Uint8List.fromList(map['bytes']),
      rowStride: map['rowStride'] as int,
      pixelStride: map['pixelStride'] as int,
      bytesPerRow: map["bytesPerRow"] ?? map["rowStride"],
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'bytes': bytes.toList(),
      'rowStride': rowStride,
      'pixelStride': pixelStride,
      'bytesPerRow': bytesPerRow,
    };
  }
}
