// ignore_for_file: public_member_api_docs, sort_constructors_first
import 'dart:typed_data';

class FlutterAnycamFrame {
  final String format;
  final int width;
  final int height;
  final int rotation;
  final List<FlutterAnycamPlane> planes;
  final Map<String, dynamic>? extras;

  int? _bytesPerRow;
  int? _pixelStride;
  int? _rowStride;
  Uint8List? _bytes;

  FlutterAnycamFrame({
    required this.format,
    required this.width,
    required this.height,
    required this.planes,
    required Uint8List? bytes,
    required this.rotation,
    this.extras,
  }) : _bytes = bytes;

  Uint8List get bytes {
    return _bytes ?? Uint8List.fromList([]);
  }

  int get bytesPerRow {
    if (format != "NV21" && planes.isNotEmpty) {
      return planes.first.bytesPerRow;
    }
    return _bytesPerRow ?? -1;
  }

  int get pixelStride {
    if (format != "NV21" && planes.isNotEmpty) {
      return planes.first.pixelStride;
    }
    return _pixelStride ?? -1;
  }

  int get rowStride {
    if (format != "NV21" && planes.isNotEmpty) {
      return planes.first.rowStride;
    }
    return _rowStride ?? -1;
  }

  factory FlutterAnycamFrame.fromMap(Map<String, dynamic> map) {
    final rawBytes = map['bytes'];
    Uint8List? bytes;

    if (rawBytes != null) {
      bytes = rawBytes is Uint8List
          ? rawBytes
          : Uint8List.fromList(rawBytes as List<int>);
    }

    final result = FlutterAnycamFrame(
      format: map['format'] as String,
      width: map['width'] as int,
      height: map['height'] as int,
      rotation: map['rotation'] as int,
      bytes: bytes,
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

    result._pixelStride = map['pixelStride'] as int?;
    result._rowStride = map['rowStride'] as int?;
    result._bytesPerRow = map['rowStride'] as int?;

    return result;
  }

  void dispose() {
    _bytes = null;
    for (var plane in planes) {
      plane.dispose();
    }
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

  FlutterAnycamFrame copyWith({
    String? format,
    int? width,
    int? height,
    int? rotation,
    List<FlutterAnycamPlane>? planes,
    Map<String, dynamic>? extras,
    Uint8List? bytes,
  }) {
    return FlutterAnycamFrame(
      format: format ?? this.format,
      width: width ?? this.width,
      height: height ?? this.height,
      rotation: rotation ?? this.rotation,
      planes: planes ?? this.planes,
      extras: extras ?? this.extras,
      bytes: bytes ?? this._bytes,
    );
  }
}

class FlutterAnycamPlane {
  final int bytesPerRow;
  final int pixelStride;
  final int rowStride;

  Uint8List? _bytes;

  FlutterAnycamPlane({
    required Uint8List bytes,
    required this.bytesPerRow,
    required this.pixelStride,
    required this.rowStride,
  }) : _bytes = bytes;

  Uint8List get bytes {
    return _bytes ?? Uint8List.fromList([]);
  }

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

  void dispose() {
    _bytes = null;
  }
}
