import '../channel/flutter_anycam_platform_interface.dart';
import 'flutter_anycam_webrtc_callbacks.dart';
import 'flutter_anycam_webrtc_i420_image.dart';
import 'flutter_anycam_webrtc_ice_server.dart';

class FlutterAnycamWebRtcStream {
  late final String _id;
  late final FlutterAnycamWebRtcCallbacksDisposer _callbacksDisposer;
  final FlutterAnycamWebRtcCallbacks? callbacks;

  String get id => _id;

  FlutterAnycamWebRtcStream(String id, {this.callbacks}) {
    _id = id;
    if (callbacks != null) {
      _callbacksDisposer = FlutterAnycamPlatform.instance.addWebRtcCallbacks(
        streamId: id,
        callbacks: callbacks!,
      );
    } else {
      _callbacksDisposer = () {};
    }
  }

  Future<void> dispose() async {
    await FlutterAnycamPlatform.instance.stopWebRtcStream(_id);
    _callbacksDisposer();
  }

  Future<Map<String, dynamic>> createAnswer(
    String offerSdp, {
    List<FlutterAnycamWebRtcIceServer>? iceServers,
  }) async {
    final answerSdp = await FlutterAnycamPlatform.instance.createWebRtcAnswer(
      _id,
      {
        'sdp': offerSdp,
        'iceServers': iceServers?.map((e) => e.toMap()).toList(),
      },
    );
    return {
      'type': 'answer',
      'sdp': answerSdp,
    };
  }

  Future<bool?> addCandidate(Map<String, dynamic> data) {
    return FlutterAnycamPlatform.instance.addWebRtcCandidate(_id, data);
  }

  Future<bool?> sendDataMessage(Map<String, dynamic> data) {
    return FlutterAnycamPlatform.instance.sendWebRtcDataMessage(_id, data);
  }
}

class FlutterAnycamWebRtc {
  FlutterAnycamWebRtc._internal();

  static final I = FlutterAnycamWebRtc._internal();

  Future<FlutterAnycamWebRtcStream?> newStream({
    FlutterAnycamWebRtcCallbacks? callbacks,
    List<FlutterAnycamWebRtcIceServer>? iceServers,
  }) async {
    final id = await FlutterAnycamPlatform.instance.newWebRtcStream(
      iceServers: iceServers,
    );
    return id != null
        ? FlutterAnycamWebRtcStream(id, callbacks: callbacks)
        : null;
  }

  Future<bool?> pushFrame(
    FlutterAnycamWebRtcI420Image image, {
    String? streamId,
  }) {
    return FlutterAnycamPlatform.instance.pushWebRtcFrame(
      image.toMap(streamId: streamId),
    );
  }
}
