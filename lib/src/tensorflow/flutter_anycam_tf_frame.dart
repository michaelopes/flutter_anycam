import 'dart:convert';

// ignore_for_file: public_member_api_docs, sort_constructors_first
class FlutterAnycamTfFrame {
  final String id;
  final int width;
  final int height;

  FlutterAnycamTfFrame(
    this.id,
    this.width,
    this.height,
  );

  Future<void> dispose() async {}

  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'id': id,
      'width': width,
      'height': height,
    };
  }

  factory FlutterAnycamTfFrame.fromMap(Map<String, dynamic> map) {
    return FlutterAnycamTfFrame(
      map['id'] as String,
      map['width'] as int,
      map['height'] as int,
    );
  }

  String toJson() => json.encode(toMap());

  factory FlutterAnycamTfFrame.fromJson(String source) =>
      FlutterAnycamTfFrame.fromMap(json.decode(source) as Map<String, dynamic>);
}
