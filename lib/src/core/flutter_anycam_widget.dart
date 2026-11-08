import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_anycam/src/core/flutter_anycam_frame.dart';
import 'package:flutter_anycam/src/core/flutter_anycam_view_factory.dart';

import '../channel/flutter_anycam_platform_interface.dart';
import 'flutter_anycam_camera_selector.dart';
import 'flutter_anycam_lifecycle.dart';
import 'flutter_anycam_mesure.dart';
import 'flutter_anycam_permission_handler.dart';

import 'flutter_anycam_preview_info.dart';
import 'flutter_anycam_rotation.dart';
import 'flutter_anycam_size.dart';
import 'flutter_anycam_stream_listener.dart';
import 'flutter_anycam_texts.dart';
import 'flutter_anycam_type.dart';
import 'flutter_anycam_typedefs.dart';
import 'flutter_anycam_filter.dart';

const kDefaultFrameChangePercent = 6.0;

/// Fallback ratio while neither the parent nor the native preview defines one.
const double _kFallbackAspectRatio = 4 / 3;

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
    this.previewScale = 1,
    this.preferredSize = const FlutterAnycamSize(640, 480),
    this.resizeFrame,
    this.filter = FlutterAnycamFilter.none,
    this.type = FlutterAnycamType.standard,
    this.onRawVideoFrameReceived,
    this.previewRotation,
    this.previewEnabled = true,
    this.frameDeliveryEnabled = true,
    this.autoRetryDelay = const Duration(seconds: 60),
    this.disconnectDebounce = const Duration(seconds: 2),
  });

  final FlutterAnycamSize preferredSize;
  final FlutterAnycamSize? resizeFrame;
  final FlutterAnycamFilter filter;
  final int? viewId;
  final bool enableDebug;
  final FlutterAnycamTexts texts;
  final bool autoRetry;
  final FlutterAnycamCameraSelector camera;
  final int fps;
  final double? aspectRatio;
  final double previewScale;
  final FlutterAnycamType type;
  final FlutterAnycamStreamFrameCallback? onFrame;
  final FlutterAnycamStreamMethod? onRawVideoFrameReceived;
  final int? previewRotation;
  final bool previewEnabled;
  final bool frameDeliveryEnabled;

  /// Wait before [autoRetry] recreates a failed or disconnected camera.
  final Duration autoRetryDelay;

  /// A "disconnected" shorter than this (USB replug, RTSP hiccup) is not shown.
  final Duration disconnectDebounce;

  @override
  State<FlutterAnycamWidget> createState() => FlutterAnycamWidgetState();
}

enum _ViewState { loading, disconnected, failure, unauthorized, connected }

class FlutterAnycamWidgetState extends State<FlutterAnycamWidget>
    with WidgetsBindingObserver {
  _ViewState _viewState = _ViewState.loading;
  FlutterAnycamStreamListenerDisposer? _listenerDisposer;

  FlutterAnycamMesure? _mesureFrames;
  int _framesPerSecond = 0;

  final _factory = FlutterAnycamViewFactory();

  Timer? _disconnectTimer;
  Timer? _retryTimer;
  Timer? _recreateTimer;

  bool _disposed = false;

  /// Bumped on every (re)creation and on dispose. Deferred work tagged with an
  /// older value is dropped, so a disposed or refreshed widget never creates a
  /// native camera nobody will dispose.
  int _generation = 0;

  /// True while the previous native camera is being disposed. Lifecycle events
  /// it emitted before its disposal must not overwrite the new camera's state.
  bool _awaitingDispose = false;

  bool _isInactive = false;

  late final _livecicle = FlutterAnycamLifecycle(
    onExecute: _refresh,
  );

  // ignore: unused_field
  FlutterAnycamPreviewInfo? _previewInfo;

  ConnectResult? _connectResult;

  final _internalBoxKey = GlobalKey();
  final _externalBoxKey = GlobalKey();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _onPlatformViewCreated();
    _createNativeView(_generation);
  }

  bool _isCurrent(int generation) => !_disposed && generation == _generation;

  void _createNativeView(int generation) {
    if (!_isCurrent(generation)) return;
    _factory
        .createView(
      viewId: viewId,
      camera: widget.camera,
      preferredSize: widget.preferredSize,
      resizeFrame: widget.resizeFrame,
      filter: widget.filter.code,
      fps: widget.fps,
      type: widget.type,
      previewEnabled: widget.previewEnabled,
      frameDeliveryEnabled: widget.frameDeliveryEnabled,
    )
        .then((textureId) {
      if (textureId == null && _isCurrent(generation)) {
        _setViewState(_ViewState.failure);
        _autoRetry();
      }
    }, onError: (Object error) {
      debugPrint("FlutterAnycam createView failed: $error");
      if (_isCurrent(generation)) {
        _setViewState(_ViewState.failure);
        _autoRetry();
      }
    });
  }

  void _setViewState(_ViewState state) {
    if (!mounted || _viewState == state) return;
    setState(() {
      _viewState = state;
    });
  }

  ({
    double scaleX,
    double scaleY,
    Size flutterPreviewSize,
    Size nativePreviewSize
  }) getSceneData() {
    final internalBox =
        _internalBoxKey.currentContext?.findRenderObject() as RenderBox?;
    final externalBox =
        _externalBoxKey.currentContext?.findRenderObject() as RenderBox?;

    final nativePreviewSize = Size(
      _connectResult?.width ?? -1,
      _connectResult?.height ?? -1,
    );

    if (internalBox == null ||
        externalBox == null ||
        !internalBox.hasSize ||
        !externalBox.hasSize ||
        externalBox.size.isEmpty) {
      return (
        scaleX: 1,
        scaleY: 1,
        flutterPreviewSize: externalBox?.hasSize == true
            ? externalBox!.size
            : Size.zero,
        nativePreviewSize: nativePreviewSize,
      );
    }

    final internalBoxSize = internalBox.size;
    final externalBoxSize = externalBox.size;

    final scaleX = internalBoxSize.width / externalBoxSize.width;
    final scaleY = internalBoxSize.height / externalBoxSize.height;

    return (
      scaleX: widget.previewScale > 0 ? scaleX * widget.previewScale : scaleX,
      scaleY: widget.previewScale > 0 ? scaleY * widget.previewScale : scaleY,
      flutterPreviewSize: externalBoxSize,
      nativePreviewSize: nativePreviewSize,
    );
  }

  int get viewId {
    return widget.viewId ?? hashCode;
  }

  void _cancelTimers() {
    _disconnectTimer?.cancel();
    _disconnectTimer = null;
    _retryTimer?.cancel();
    _retryTimer = null;
    _recreateTimer?.cancel();
    _recreateTimer = null;
  }

  @override
  void dispose() {
    _disposed = true;
    _generation++;
    _cancelTimers();
    _mesureFrames?.stopCounting();
    WidgetsBinding.instance.removeObserver(this);
    _listenerDisposer?.call();
    _listenerDisposer = null;
    _factory.disposeView(viewId).catchError((_) => false);
    super.dispose();
  }

  void _onPlatformViewCreated() {
    _listenerDisposer?.call();

    _listenerDisposer = FlutterAnycamPlatform.instance.addStreamListener(
      FlutterAnycamStreamListener(
        viewId: viewId,
        onConnected: _handleConnected,
        onUnauthorized: (data) {
          if (_disposed || _awaitingDispose) return;
          debugPrint("CM: onUnauthorized");
          if (_viewState == _ViewState.unauthorized) return;
          _setViewState(_ViewState.unauthorized);
          if (_needsRuntimePermission) {
            FlutterAnycamPermissionHandler.I.requestPermission(
              viewId: viewId,
            );
          }
        },
        onDisconnected: (data) {
          if (_disposed || _awaitingDispose) return;
          debugPrint("CM: onDisconnected");
          _disconnectTimer?.cancel();
          _disconnectTimer = Timer(widget.disconnectDebounce, () {
            _disconnectTimer = null;
            if (!mounted) return;
            _setViewState(_ViewState.disconnected);
            _autoRetry();
          });
        },
        onFailed: (data) {
          if (_disposed || _awaitingDispose) return;
          debugPrint("CM: onFailed ${data["message"] ?? ""}");
          _disconnectTimer?.cancel();
          _disconnectTimer = null;
          _setViewState(_ViewState.failure);
          _autoRetry();
        },
        onVideoFrameReceived: (data) {
          _mesureFrames?.count();
          if (widget.onRawVideoFrameReceived != null) {
            widget.onRawVideoFrameReceived!(data);
          } else if (widget.type == FlutterAnycamType.tf) {
            // An id-only native frame: nobody here can consume it, so free it.
            final id = data["id"];
            if (id is String) {
              FlutterAnycamPlatform.instance
                  .closeTfFrame(frameId: id)
                  .catchError((_) => false);
            }
          } else if (widget.onFrame != null) {
            final frame = FlutterAnycamFrame.fromMap(data);
            widget.onFrame?.call(frame);
          }
        },
      ),
    );
  }

  bool get _needsRuntimePermission =>
      widget.camera.lensFacing != FlutterAnycamLensFacing.rtsp &&
      widget.camera.lensFacing != FlutterAnycamLensFacing.usb;

  void _handleConnected(Map<String, dynamic> data) {
    if (_disposed || _awaitingDispose) return;
    debugPrint("CM: onConnected");
    _disconnectTimer?.cancel();
    _disconnectTimer = null;
    _retryTimer?.cancel();
    _retryTimer = null;

    final width = (data["width"] as num?)?.toDouble() ?? 0;
    final height = (data["height"] as num?)?.toDouble() ?? 0;
    final textureId = (data["textureId"] as num?)?.toInt();
    if (textureId == null || width <= 0 || height <= 0) {
      debugPrint("FlutterAnycam: invalid onConnected payload $data");
      _setViewState(_ViewState.failure);
      return;
    }

    if (!mounted) return;
    // Applied right away: a post-frame callback alone does not schedule a frame,
    // so a static "disconnected" screen would hide a USB reconnection until the
    // next unrelated repaint.
    setState(() {
      _connectResult = (width: width, height: height, textureId: textureId);
      _viewState = _ViewState.connected;
    });

    if (widget.enableDebug) {
      _mesureFrames?.stopCounting();
      _mesureFrames = FlutterAnycamMesure(seconds: 1);
      _mesureFrames?.startCounting(
        callback: (counter) {
          if (!mounted) return;
          setState(() {
            if (kDebugMode) {
              print("FPS $viewId: $counter");
            }
            _framesPerSecond = counter;
          });
        },
      );
    }
  }

  void _autoRetry() {
    if (!widget.autoRetry || _disposed || _retryTimer != null) return;
    _retryTimer = Timer(widget.autoRetryDelay, () {
      _retryTimer = null;
      _refresh();
    });
  }

  static String _cameraKey(FlutterAnycamCameraSelector camera) {
    final map = camera.toMap();
    return [
      camera.id,
      camera.lensFacing.name,
      camera.sensorOrientation,
      map['forceSensorOrientation'],
      map['url'],
      map['username'],
      map['password'],
    ].join('|');
  }

  @override
  void didUpdateWidget(FlutterAnycamWidget oldWidget) {
    super.didUpdateWidget(oldWidget);
    final cameraChanged =
        _cameraKey(oldWidget.camera) != _cameraKey(widget.camera);
    final pipelineChanged = oldWidget.fps != widget.fps ||
        oldWidget.type != widget.type ||
        oldWidget.previewEnabled != widget.previewEnabled ||
        oldWidget.frameDeliveryEnabled != widget.frameDeliveryEnabled ||
        oldWidget.filter != widget.filter ||
        oldWidget.resizeFrame?.width != widget.resizeFrame?.width ||
        oldWidget.resizeFrame?.height != widget.resizeFrame?.height ||
        oldWidget.preferredSize.width != widget.preferredSize.width ||
        oldWidget.preferredSize.height != widget.preferredSize.height;
    final viewIdChanged = oldWidget.viewId != widget.viewId;
    if (viewIdChanged) {
      _factory.disposeView(oldWidget.viewId ?? hashCode).catchError((_) => false);
      _onPlatformViewCreated();
    }
    if (cameraChanged || pipelineChanged || viewIdChanged) {
      _refresh();
    }
    if (!widget.autoRetry) {
      _retryTimer?.cancel();
      _retryTimer = null;
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

  /// Disposes the native camera, waits for the platform to confirm, then
  /// recreates it. Safe to call repeatedly and after dispose.
  void _refresh() {
    if (_disposed) return;
    final generation = ++_generation;
    _cancelTimers();
    _mesureFrames?.stopCounting();
    _setViewState(_ViewState.loading);
    _awaitingDispose = true;
    _factory
        .disposeView(viewId)
        .catchError((_) => false)
        .whenComplete(() {
      if (!_isCurrent(generation)) return;
      _awaitingDispose = false;
      _recreateTimer = Timer(const Duration(milliseconds: 200), () {
        _recreateTimer = null;
        _createNativeView(generation);
      });
    });
  }

  Widget get _generateView => _connectResult == null
      ? const SizedBox.shrink()
      : AspectRatio(
          aspectRatio: getAspectRatio,
          child: SizedBox(
            key: _internalBoxKey,
            child: FlutterAnycamRotation(
              degress: widget.previewRotation ?? widget.camera.previewRotation,
              child: widget.previewScale > 1
                  ? Transform.scale(
                      scale: widget.previewScale,
                      child: Texture(
                        textureId: _connectResult!.textureId,
                      ),
                    )
                  : Texture(
                      textureId: _connectResult!.textureId,
                    ),
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
                    _needsRuntimePermission) {
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
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Icon(Icons.refresh, color: Colors.white),
                  const SizedBox(
                    width: 8,
                  ),
                  Flexible(
                    child: Text(
                      widget.texts.retry,
                      style: const TextStyle(color: Colors.white, fontSize: 20),
                    ),
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
    final result = _connectResult;
    if (result == null || result.width <= 0 || result.height <= 0) {
      return _kFallbackAspectRatio;
    }
    return result.width / result.height;
  }

  /// Outer box ratio. Unbounded parents (Column, ListView) get the preview's own
  /// ratio instead of `width / infinity`, which would break [AspectRatio].
  double _outerAspectRatio(double width, BoxConstraints constraints) {
    final requested = widget.aspectRatio;
    if (requested != null && requested.isFinite && requested > 0) {
      return requested;
    }
    if (constraints.hasBoundedHeight && constraints.maxHeight > 0) {
      final ratio = width / constraints.maxHeight;
      if (ratio.isFinite && ratio > 0) return ratio;
    }
    return getAspectRatio;
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, constraints) {
      final width = constraints.hasBoundedWidth
          ? constraints.maxWidth
          : MediaQuery.sizeOf(context).width;
      if (width <= 0) {
        return const SizedBox.shrink();
      }
      return UnconstrainedBox(
        child: Container(
          color: Colors.black,
          width: width,
          child: AspectRatio(
            aspectRatio: _outerAspectRatio(width, constraints),
            child: Stack(
              children: [
                IndexedStack(
                  index: _ViewState.connected == _viewState ? 1 : 0,
                  children: [
                    _buildStates,
                    LayoutBuilder(builder: (_, inner) {
                      return SizedBox(
                        key: _externalBoxKey,
                        width: inner.maxWidth,
                        height: inner.maxHeight,
                        child: ClipRect(
                          clipBehavior: Clip.hardEdge,
                          child: FittedBox(
                            fit: BoxFit.cover,
                            child: SizedBox(
                              width: inner.maxWidth,
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
