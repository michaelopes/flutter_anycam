import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_anycam/src/flutter_anycam_frame.dart';
import 'package:flutter_anycam/src/flutter_anycam_view_factory.dart';

import 'flutter_anycam_camera_selector.dart';
import 'flutter_anycam_lifecycle.dart';
import 'flutter_anycam_mesure.dart';
import 'flutter_anycam_permission_handler.dart';
import 'flutter_anycam_platform_interface.dart';
import 'flutter_anycam_preview_info.dart';
import 'flutter_anycam_rotation.dart';
import 'flutter_anycam_size.dart';
import 'flutter_anycam_stream_listener.dart';
import 'flutter_anycam_texts.dart';
import 'flutter_anycam_typedefs.dart';

const kDefaultFrameChangePercent = 6.0;

typedef ConnectResult = ({int textureId, double width, double height});

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

    /// An Android-only parameter.
    ///
    /// This parameter has no effect on iOS platform.
    this.preferredSize = const FlutterAnycamSize(640, 480),
  });

  /// An Android-only parameter.
  ///
  /// This parameter has no effect on iOS platform.
  final FlutterAnycamSize preferredSize;
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

  final _factory = FlutterAnycamViewFactory();

  _Debouncer? _disconnectDeboucer;
  bool autoRetryTrigged = false;

  bool _isInactive = false;

  late final _livecicle = FlutterAnycamLifecycle(
    onExecute: _refresh,
  );

  // ignore: unused_field
  FlutterAnycamPreviewInfo? _previewInfo;

  ConnectResult? _connectResult;

  @override
  void initState() {
    WidgetsBinding.instance.addObserver(this);
    _createView();
    _onPlatformViewCreated();
    super.initState();
  }

  Future<void> _createView() async {
    _factory.createView(
      viewId: viewId,
      camera: widget.camera,
      preferredSize: widget.preferredSize,
      fps: widget.fps,
    );
  }

  int get viewId {
    return widget.viewId ?? hashCode;
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _listenerDisposer?.call();
    _factory.disposeView(viewId);
    super.dispose();
  }

  void _onPlatformViewCreated() {
    if (_listenerDisposer != null) {
      _listenerDisposer?.call();
    }

    _listenerDisposer = FlutterAnycamPlatform.instance.addStreamListener(
      FlutterAnycamStreamListener(
        viewId: viewId,
        onConnected: (data) {
          _disconnectDeboucer?.cancel();
          debugPrint("CM: onConnected");
          if (mounted) {
            Future.delayed(const Duration(milliseconds: 500), () {
              setState(() {
                _connectResult = (
                  width: (data["width"] as num).toDouble(),
                  height: (data["height"] as num).toDouble(),
                  textureId: data["textureId"]
                );
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
                    print("FPS $viewId: $counter");
                  }
                  _framesPerSecond = counter;
                });
              },
            );
          }
        },
        onUnauthorized: (data) async {
          debugPrint("CM: onUnauthorized");
          if (_viewState != _ViewState.unauthorized) {
            setState(() {
              _viewState = _ViewState.unauthorized;
            });
            if (widget.camera.lensFacing != FlutterAnycamLensFacing.rtsp) {
              FlutterAnycamPermissionHandler.I.requestPermission(
                viewId: viewId,
              );
            }
          }
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
    if (state == AppLifecycleState.paused && !_isInactive) {
      _isInactive = true;
      _livecicle.pause();
    } else if (state == AppLifecycleState.resumed && _isInactive) {
      _isInactive = false;
      _livecicle.resume();
    }
    super.didChangeAppLifecycleState(state);
  }

  void _refresh() {
    setState(() {
      _viewState = _ViewState.loading;
    });
    _factory.disposeView(viewId);
    Future.delayed(const Duration(milliseconds: 200), () {
      _createView();
    });
  }

  Widget get _generateView => _connectResult == null
      ? const SizedBox.shrink()
      : AspectRatio(
          aspectRatio: getAspectRatio,
          child: FlutterAnycamRotation(
            degress: widget.camera.previewRotation,
            child: Texture(
              textureId: _connectResult!.textureId,
            ),
          ),
        );

  Widget get _buildStates {
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

  Widget _buildState({required String message}) {
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
              onPressed: () {
                if (_viewState == _ViewState.unauthorized &&
                    widget.camera.lensFacing != FlutterAnycamLensFacing.rtsp) {
                  FlutterAnycamPermissionHandler.I.requestPermission(
                    viewId: viewId,
                    onResult: (result) {
                      if (result) {
                        _refresh();
                      }
                    },
                  );
                } else {
                  _refresh();
                }
              },
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
    return _buildState(message: widget.texts.disconnectedMessage);
  }

  Widget get _buildFailure {
    return _buildState(message: widget.texts.failureMessage);
  }

  Widget get _buildUnauthorized {
    return _buildState(message: widget.texts.unauthorizedMessage);
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

  double get getAspectRatio {
    final dstWidth = _connectResult!.width;
    final dstHeight = _connectResult!.height;
    return dstWidth / dstHeight;
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
                    _buildStates,
                    LayoutBuilder(builder: (_, x) {
                      return SizedBox(
                        width: x.maxWidth,
                        height: constraints.maxHeight,
                        child: ClipRect(
                          clipBehavior: Clip.none,
                          child: FittedBox(
                            fit: BoxFit.cover,
                            child: SizedBox(
                              width: x.maxWidth,
                              child: _generateView,
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
