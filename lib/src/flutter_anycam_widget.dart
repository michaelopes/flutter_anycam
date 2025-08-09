import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/material.dart';
import 'package:flutter_anycam/src/flutter_anycam_frame.dart';

import 'flutter_anycam_camera_selector.dart';
import 'flutter_anycam_mesure.dart';
import 'flutter_anycam_platform_interface.dart';
import 'flutter_anycam_preview_info.dart';
import 'flutter_anycam_stream_listener.dart';
import 'flutter_anycam_texts.dart';
import 'flutter_anycam_typedefs.dart';

const kDefaultFrameChangePercent = 6.0;

class FlutterAnycamWidget extends StatefulWidget {
  const FlutterAnycamWidget({
    super.key,
    required this.camera,
    this.onFrame,
    this.texts = const FlutterAnycamTexts(),
    this.enableDebug = false,
    this.viewId,
    this.autoRetry = false,
    this.fps = 5,
    this.aspectRatio,
  });

  final int? viewId;
  final bool enableDebug;
  final FlutterAnycamTexts texts;
  final bool autoRetry;
  final FlutterAnycamCameraSelector camera;
  final FlutterAnycamStreamFrameCallback? onFrame;
  final int fps;
  final double? aspectRatio;

  @override
  State<FlutterAnycamWidget> createState() => _FlutterAnycamWidgetState();
}

enum _ViewState { loading, disconnected, failure, unauthorized, connected }

class _FlutterAnycamWidgetState extends State<FlutterAnycamWidget>
    with WidgetsBindingObserver {
  _ViewState _viewState = _ViewState.loading;
  FlutterAnycamStreamListenerDisposer? _listenerDisposer;

  FlutterAnycamMesure? _mesureFrames;
  int _framesPerSecond = 0;
  late UniqueKey _viewKey;
  _Debouncer? _disconnectDeboucer;
  bool autoRetryTrigged = false;

  bool _isInactive = false;

  // ignore: unused_field
  FlutterAnycamPreviewInfo? _previewInfo;

  @override
  void initState() {
    _viewKey = UniqueKey();
    WidgetsBinding.instance.addObserver(this);
    super.initState();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _listenerDisposer?.call();
    super.dispose();
  }

  void _onPlatformViewCreated(int id) {
    if (_listenerDisposer != null) {
      _listenerDisposer?.call();
    }

    _listenerDisposer = FlutterAnycamPlatform.instance.addStreamListener(
      FlutterAnycamStreamListener(
        viewId: widget.viewId ?? id,
        onConnected: (data) {
          _disconnectDeboucer?.cancel();
          debugPrint("CM: onConnected");
          if (mounted) {
            Future.delayed(const Duration(milliseconds: 500), () {
              setState(() {
                _viewState = _ViewState.connected;
              });
            });
          } else {
            WidgetsBinding.instance.addPostFrameCallback((_) {
              Future.delayed(const Duration(milliseconds: 500), () {
                setState(() {
                  _viewState = _ViewState.connected;
                });
              });
            });
          }
          if (widget.enableDebug) {
            _mesureFrames?.stopCounting();
            _mesureFrames = FlutterAnycamMesure(seconds: 1);
            _mesureFrames?.startCounting(
              callback: (counter) {
                setState(() {
                  if (kDebugMode) {
                    print("FPS $id: $counter");
                  }
                  _framesPerSecond = counter;
                });
              },
            );
          }
        },
        onUnauthorized: (data) {
          debugPrint("CM: onUnauthorized");
          setState(() {
            _viewState = _ViewState.unauthorized;
          });
        },
        onDisconnected: (data) {
          debugPrint("CM: onDisconnected");

          _disconnectDeboucer = _Debouncer(milliseconds: 2000);
          _disconnectDeboucer?.call(() {
            setState(() {
              _viewState = _ViewState.disconnected;
            });
            _autoRetry();
          });
        },
        onFailed: (data) {
          debugPrint("CM: onFailed");
          setState(() {
            _viewState = _ViewState.failure;
            _autoRetry();
          });
        },
        onVideoFrameReceived: (data) {
          _mesureFrames?.count();
          if (widget.onFrame != null) {
            final frame = FlutterAnycamFrame.fromMap(data);
            widget.onFrame?.call(frame);
          }
        },
      ),
    );
  }

  void _autoRetry() {
    if (widget.autoRetry && !autoRetryTrigged) {
      autoRetryTrigged = true;
      Future.delayed(const Duration(seconds: 60), () {
        _refresh();
        autoRetryTrigged = false;
      });
    }
  }

  @override
  void reassemble() {
    _refresh();
    super.reassemble();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.inactive && !_isInactive) {
      _isInactive = true;
      _refresh();
    } else if (state == AppLifecycleState.resumed) {
      _isInactive = false;
    }
    super.didChangeAppLifecycleState(state);
  }

  void _refresh() {
    setState(() {
      _viewState = _ViewState.loading;
    });

    Future.delayed(const Duration(milliseconds: 200), () {
      setState(() {
        _viewKey = UniqueKey();
      });
    });
  }

  Map<String, dynamic> get _params => {
        "viewId": widget.viewId,
        "cameraSelector": widget.camera.toMap(),
        "fps": widget.fps,
      };

  Widget get _generateView => Platform.isAndroid
      ? AndroidView(
          key: _viewKey,
          viewType: 'br.dev.michaellopes.flutter_anycam/view',
          onPlatformViewCreated: _onPlatformViewCreated,
          creationParams: _params,
          creationParamsCodec: const StandardMessageCodec(),
        )
      : UiKitView(
          key: _viewKey,
          viewType: 'br.dev.michaellopes.flutter_anycam/view',
          onPlatformViewCreated: _onPlatformViewCreated,
          creationParams: _params,
          creationParamsCodec: const StandardMessageCodec(),
        );

  Widget get _buildNotConnectedStates {
    switch (_viewState) {
      case _ViewState.disconnected:
        return _buildDisconnected;
      case _ViewState.failure:
        return _buildFailure;
      case _ViewState.unauthorized:
        return _buildUnauthorized;
      case _ViewState.loading:
      default:
        return const Center(
          child: CircularProgressIndicator(),
        );
    }
  }

  Widget _buildNonConnect({required String message}) {
    return SizedBox.expand(
      child: Padding(
        padding: const EdgeInsets.all(8),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            Text(
              message,
              style: const TextStyle(fontSize: 18, color: Colors.white),
              textAlign: TextAlign.center,
            ),
            const SizedBox(
              height: 24,
            ),
            TextButton(
              onPressed: _refresh,
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  const Icon(Icons.refresh, color: Colors.white),
                  const SizedBox(
                    width: 8,
                  ),
                  Text(
                    widget.texts.retry,
                    style: const TextStyle(color: Colors.white, fontSize: 20),
                  ),
                ],
              ),
            )
          ],
        ),
      ),
    );
  }

  Widget get _buildDisconnected {
    return _buildNonConnect(message: widget.texts.disconnectedMessage);
  }

  Widget get _buildFailure {
    return _buildNonConnect(message: widget.texts.failureMessage);
  }

  Widget get _buildUnauthorized {
    return _buildNonConnect(message: widget.texts.unauthorizedMessage);
  }

  Widget get _infos {
    return Padding(
      padding: EdgeInsets.only(top: MediaQuery.of(context).padding.top),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
            child: Text(
              "FramesPS: $_framesPerSecond",
              style: const TextStyle(fontSize: 16, color: Colors.white),
              textAlign: TextAlign.center,
            ),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (_, constraints) {
      return UnconstrainedBox(
        child: Container(
          color: Colors.black,
          width: constraints.maxWidth,
          child: AspectRatio(
            aspectRatio: widget.aspectRatio ??
                (constraints.maxWidth / constraints.maxHeight),
            child: Stack(
              children: [
                IndexedStack(
                  index: _ViewState.connected == _viewState ? 1 : 0,
                  children: [
                    _buildNotConnectedStates,
                    LayoutBuilder(builder: (_, x) {
                      return SizedBox(
                        width: x.maxWidth,
                        height: constraints.maxHeight,
                        child: ClipRect(
                          clipBehavior: Clip.hardEdge,
                          child: FittedBox(
                            fit: BoxFit.cover,
                            child: SizedBox(
                              width: x.maxWidth,
                              child: AspectRatio(
                                aspectRatio: 1,
                                child: _generateView,
                              ),
                            ),
                          ),
                        ),
                      );
                    }),
                  ],
                ),
                if (widget.enableDebug)
                  Align(
                    alignment: Alignment.topCenter,
                    child: _infos,
                  )
              ],
            ),
          ),
        ),
      );
    });
  }
}

class _Debouncer {
  final int milliseconds;
  VoidCallback? _action;
  Timer? _timer;

  _Debouncer({required this.milliseconds});

  /// Chama a função fornecida após o tempo de debounce.
  void call(VoidCallback action) {
    _action = action;
    _timer?.cancel(); // Cancela o timer anterior, se existir.
    _timer = Timer(Duration(milliseconds: milliseconds), () {
      _action?.call();
    });
  }

  /// Cancela qualquer execução pendente.
  void cancel() {
    _timer?.cancel();
    _timer = null;
  }
}
