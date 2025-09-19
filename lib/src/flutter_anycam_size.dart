// ignore_for_file: public_member_api_docs, sort_constructors_first
import 'dart:convert';

final class FlutterAnycamSize {
  final int width;
  final int height;

  const FlutterAnycamSize(this.width, this.height);

  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'width': width,
      'height': height,
    };
  }

  factory FlutterAnycamSize.fromMap(Map<String, dynamic> map) {
    return FlutterAnycamSize(
      map['width'] as int,
      map['height'] as int,
    );
  }

  String toJson() => json.encode(toMap());

  factory FlutterAnycamSize.fromJson(String source) =>
      FlutterAnycamSize.fromMap(json.decode(source) as Map<String, dynamic>);
}
