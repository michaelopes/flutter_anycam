typedef FlutterAnycamWebRtcOnConnectionCallback = void Function();
typedef FlutterAnycamWebRtcOnCandidateCallback = void Function(
  Map<String, dynamic> data,
);
typedef FlutterAnycamWebRtcOnDataMessageCallback = void Function(
  Map<String, dynamic>? data,
);
typedef FlutterAnycamWebRtcCallbacksDisposer = void Function();

class FlutterAnycamWebRtcCallbacks {
  final FlutterAnycamWebRtcOnConnectionCallback? onConnectedCallback;
  final FlutterAnycamWebRtcOnConnectionCallback? onDisconnectedCallback;
  final FlutterAnycamWebRtcOnCandidateCallback? onCandidateCallback;
  final FlutterAnycamWebRtcOnDataMessageCallback? onDataMessageCallback;

  FlutterAnycamWebRtcCallbacks({
    this.onConnectedCallback,
    this.onDisconnectedCallback,
    this.onCandidateCallback,
    this.onDataMessageCallback,
  });
}
