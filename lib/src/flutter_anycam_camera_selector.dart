// ignore_for_file: public_member_api_docs, sort_constructors_first
import 'dart:convert';
import 'dart:io';

enum FlutterAnycamLensFacing { back, front, usb, rtsp, unknown }

class FlutterAnycamCameraSelector {
  final String id;
  final String name;
  final FlutterAnycamLensFacing lensFacing;
  final int sensorOrientation;
  final Map<String, dynamic>? extras;
  bool _forceSensorOrientation = false;

  FlutterAnycamCameraSelector({
    required this.id,
    required this.name,
    required this.lensFacing,
    required this.sensorOrientation,
    this.extras,
  });

  int get previewRotation {
    if (_forceSensorOrientation) {
      return sensorOrientation;
    }
    return 0;
  }

  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'id': id,
      'name': name,
      'lensFacing': lensFacing.name,
      'sensorOrientation': sensorOrientation,
      'forceSensorOrientation': _forceSensorOrientation,
      'extras': extras,
    };
  }

  factory FlutterAnycamCameraSelector.fromMap(Map<String, dynamic> map) {
    return FlutterAnycamCameraSelector(
      id: map['id'] as String,
      name: map['name'] as String,
      lensFacing: FlutterAnycamLensFacing.values.byName(map['lensFacing']),
      sensorOrientation: map['sensorOrientation'] as int,
      extras: map['extras'] != null
          ? Map<String, dynamic>.from(map['extras'])
          : null,
    );
  }

  String toJson() => json.encode(toMap());

  FlutterAnycamCameraSelector customSensorOrientation({
    required int sensorOrientation,
  }) {
    FlutterAnycamCameraSelector result;
    if (this is _FlutterAnycamCameraSelectorRtsp) {
      final thz = this as _FlutterAnycamCameraSelectorRtsp;
      result = _FlutterAnycamCameraSelectorRtsp(
        url: thz.url,
        password: thz.password,
        username: thz.username,
      );
    } else {
      result = FlutterAnycamCameraSelector(
        id: id,
        name: name,
        lensFacing: lensFacing,
        sensorOrientation: sensorOrientation,
      );
    }
    result._forceSensorOrientation = true;
    return result;
  }

  // ignore: library_private_types_in_public_api
  static FlutterAnycamCameraSelector rtsp({
    required String url,
    required String username,
    required String password,
  }) {
    if (Platform.isIOS) {
      throw UnsupportedError("Rtsp camera is not only suported on iOS yet");
    }
    return _FlutterAnycamCameraSelectorRtsp(
      url: url,
      username: username,
      password: password,
    );
  }

  factory FlutterAnycamCameraSelector.fromJson(String source) =>
      FlutterAnycamCameraSelector.fromMap(
          json.decode(source) as Map<String, dynamic>);
}

final class _FlutterAnycamCameraSelectorRtsp
    extends FlutterAnycamCameraSelector {
  final String url;
  final String username;
  final String password;

  _FlutterAnycamCameraSelectorRtsp({
    required this.url,
    required this.username,
    required this.password,
  }) : super(
            id: url,
            name: username,
            sensorOrientation: 0,
            lensFacing: FlutterAnycamLensFacing.rtsp);

  @override
  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      ...super.toMap(),
      'url': url,
      'username': username,
      'password': password,
    };
  }
}
