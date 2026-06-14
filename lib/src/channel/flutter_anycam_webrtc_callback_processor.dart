import 'dart:convert';

import 'package:flutter/services.dart';

import '../webrtc/flutter_anycam_webrtc_callbacks.dart';

class FlutterAnycamWebRtcCallbackProcessor {
  FlutterAnycamWebRtcCallbackProcessor._();

  static final I = FlutterAnycamWebRtcCallbackProcessor._();

  final _callbacks = <String, FlutterAnycamWebRtcCallbacks>{};

  Future<dynamic> handle(MethodCall call) async {
    Map<String, dynamic>? data;
    try {
      final args = call.arguments;
      if (args is Map) {
        data = Map<String, dynamic>.from(args);
      }
    } catch (_) {
      data = null;
    }

    if (data == null) {
      return null;
    }

    final streamId = data['streamId'] as String?;
    if (streamId == null) {
      return null;
    }

    final callbacks = _callbacks[streamId];
    if (callbacks == null) {
      return null;
    }

    switch (call.method) {
      case 'onWebRtcIceCandidate':
        callbacks.onCandidateCallback?.call(data);
        break;
      case 'onWebRtcDataChannelMessage':
        final rawMessage = data['message'];
        if (rawMessage is String) {
          callbacks.onDataMessageCallback?.call(
            Map<String, dynamic>.from(jsonDecode(rawMessage)),
          );
        }
        break;
      case 'onWebRtcConnected':
        callbacks.onConnectedCallback?.call();
        break;
      case 'onWebRtcDisconnected':
        callbacks.onDisconnectedCallback?.call();
        break;
    }
    return null;
  }

  FlutterAnycamWebRtcCallbacksDisposer addCallbacks({
    required String streamId,
    required FlutterAnycamWebRtcCallbacks callbacks,
  }) {
    _callbacks[streamId] = callbacks;
    return () {
      _callbacks.remove(streamId);
    };
  }
}
