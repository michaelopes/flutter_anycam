package br.dev.michaellopes.flutter_anycam;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import br.dev.michaellopes.flutter_anycam.camera.BaseCamera;
import br.dev.michaellopes.flutter_anycam.integration.CameraViewFactory;
import br.dev.michaellopes.flutter_anycam.integration.FlutterEventChannel;
import br.dev.michaellopes.flutter_anycam.model.TfInferenceInput;
import br.dev.michaellopes.flutter_anycam.stream.CameraStreamManager;
import br.dev.michaellopes.flutter_anycam.stream.HeadlessTfCameraManager;
import br.dev.michaellopes.flutter_anycam.tensorflow.TfFrameHandler;
import br.dev.michaellopes.flutter_anycam.tensorflow.TfModelHandler;
import br.dev.michaellopes.flutter_anycam.utils.ByteArrayPoolUtil;
import br.dev.michaellopes.flutter_anycam.utils.CameraPermissionsUtil;
import br.dev.michaellopes.flutter_anycam.utils.CameraUtil;
import br.dev.michaellopes.flutter_anycam.utils.ContextUtil;
import br.dev.michaellopes.flutter_anycam.utils.DeviceCameraUtils;
import br.dev.michaellopes.flutter_anycam.utils.ImageConverterUtil;
import br.dev.michaellopes.flutter_anycam.utils.LivecycleUtil;
import br.dev.michaellopes.flutter_anycam.utils.NativeUtil;
import br.dev.michaellopes.flutter_anycam.webrtc.I420Image;
import br.dev.michaellopes.flutter_anycam.webrtc.WebRtcStreamHandler;
import br.dev.michaellopes.flutter_anycam.webrtc.WebRtcStreamer;
import io.flutter.Log;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.view.TextureRegistry;

/**
 * FlutterAnycamPlugin
 *
 * <p>Threading: every handler replies exactly once, on the main thread. Anything that converts
 * pixels, runs TFLite or waits on the TF frame thread runs on {@link #executor}, so the main
 * thread (and Flutter's raster pipeline) is never blocked by frame work.
 *
 * <p>Engines: camera, TF and WebRTC state is process-wide. Widgets render into the texture
 * registry of the engine attached to the Activity; process-wide teardown happens only when the
 * last engine detaches, so a background engine coming and going never kills the UI cameras.
 */
public class FlutterAnycamPlugin implements FlutterPlugin, MethodCallHandler, ActivityAware {

    private static final String TAG = "FlutterAnycamPlugin";
    /** Dart must answer processTfOutput within this time, or the inference returns no result. */
    private static final long TF_OUTPUT_TIMEOUT_SECONDS = 10;

    private static final List<FlutterAnycamPlugin> attachedPlugins = new ArrayList<>();

    private MethodChannel channel;
    private EventChannel eventChannel;
    private EventChannel.EventSink eventSink;
    private TextureRegistry textureRegistry;

    private final AtomicInteger threadCount = new AtomicInteger();
    private final ExecutorService executor = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "AnycamPlugin-" + threadCount.incrementAndGet());
        t.setDaemon(true);
        return t;
    });
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ByteArrayPoolUtil byteArrayPool = new ByteArrayPoolUtil(6, 30);

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        attachActivity(binding);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        // Keep EventChannel alive across config changes; only drop the old Activity.
        ContextUtil.clearActivity();
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        attachActivity(binding);
    }

    @Override
    public void onDetachedFromActivity() {
        ContextUtil.clearActivity();
    }

    private void attachActivity(ActivityPluginBinding binding) {
        CameraPermissionsUtil.getInstance().init(binding::addRequestPermissionsResultListener);
        ContextUtil.setActivity(binding.getActivity());
        LivecycleUtil.init(binding.getLifecycle());
        CameraViewFactory factory = CameraViewFactory.getInstance();
        if (textureRegistry != null && factory.getTextureRegistry() != textureRegistry) {
            // Cameras of another engine render into textures this UI cannot show.
            factory.disposeAll();
            factory.init(textureRegistry);
        }
        DeviceCameraUtils.getInstance().onActivityReady();
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        ContextUtil.initApp(flutterPluginBinding.getApplicationContext());
        CameraUtil.getInstance().init(flutterPluginBinding.getApplicationContext());

        textureRegistry = flutterPluginBinding.getTextureRegistry();
        synchronized (attachedPlugins) {
            attachedPlugins.add(this);
        }
        if (CameraViewFactory.getInstance().getTextureRegistry() == null) {
            CameraViewFactory.getInstance().init(textureRegistry);
        }

        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "br.dev.michaellopes.flutter_anycam/channel");
        eventChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(), "br.dev.michaellopes.flutter_anycam/event");

        channel.setMethodCallHandler(this);
        eventChannel.setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object arguments, EventChannel.EventSink events) {
                eventSink = events;
                FlutterEventChannel.getInstance().attachSink(FlutterAnycamPlugin.this, events);
            }

            @Override
            public void onCancel(Object arguments) {
                FlutterEventChannel.getInstance().detachSink(FlutterAnycamPlugin.this);
                eventSink = null;
            }
        });
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
        eventChannel.setStreamHandler(null);
        FlutterEventChannel.getInstance().detachSink(this);
        eventSink = null;

        FlutterAnycamPlugin next = null;
        boolean last;
        synchronized (attachedPlugins) {
            attachedPlugins.remove(this);
            last = attachedPlugins.isEmpty();
            if (!last) next = attachedPlugins.get(attachedPlugins.size() - 1);
        }

        CameraViewFactory factory = CameraViewFactory.getInstance();
        if (factory.getTextureRegistry() == textureRegistry) {
            // Textures die with this engine: their cameras cannot keep running.
            factory.disposeAll();
            factory.init(next != null ? next.textureRegistry : null);
        }

        if (last) {
            HeadlessTfCameraManager.getInstance().disposeAll();
            TfModelHandler.getInstance().disposeAllModels();
            TfFrameHandler.getInstance().closeAll();
            WebRtcStreamHandler webRtc = WebRtcStreamHandler.peekInstance();
            if (webRtc != null) webRtc.stopAll();
            CameraUtil.getInstance().reset();
            FlutterEventChannel.getInstance().release();
            ImageConverterUtil.shutdown();
        }
        byteArrayPool.shutdown();
        executor.shutdown();
        textureRegistry = null;
    }

    // ---------------------------------------------------------------------------------------
    // Replies
    // ---------------------------------------------------------------------------------------

    /** Replies at most once, always on the main thread; late replies after detach are dropped. */
    private final class Reply implements Result {
        private final Result delegate;
        private final AtomicBoolean done = new AtomicBoolean(false);

        Reply(Result delegate) {
            this.delegate = delegate;
        }

        private void post(Runnable r) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                r.run();
            } else {
                uiHandler.post(r);
            }
        }

        @Override
        public void success(@Nullable Object value) {
            if (done.compareAndSet(false, true)) post(() -> safe(() -> delegate.success(value)));
        }

        @Override
        public void error(@NonNull String code, @Nullable String message, @Nullable Object details) {
            if (done.compareAndSet(false, true)) post(() -> safe(() -> delegate.error(code, message, details)));
        }

        @Override
        public void notImplemented() {
            if (done.compareAndSet(false, true)) post(() -> safe(delegate::notImplemented));
        }

        private void safe(Runnable r) {
            try {
                r.run();
            } catch (Exception e) {
                Log.w(TAG, "reply dropped: " + e.getMessage());
            }
        }
    }

    private void background(Reply reply, String code, Callable<Object> task) {
        try {
            executor.execute(() -> {
                try {
                    reply.success(task.call());
                } catch (Throwable t) {
                    Log.e(TAG, code + " failed: " + t);
                    reply.error(code, t.getMessage() != null ? t.getMessage() : t.toString(), null);
                }
            });
        } catch (RejectedExecutionException e) {
            reply.error(code, "Plugin detached", null);
        }
    }

    private static Map<?, ?> argsMap(MethodCall call) {
        return call.arguments instanceof Map ? (Map<?, ?>) call.arguments : new HashMap<>();
    }

    private static String str(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v instanceof String ? (String) v : null;
    }

    private static Integer integer(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v instanceof Number ? ((Number) v).intValue() : null;
    }

    private static int intOr(Map<?, ?> map, String key, int fallback) {
        Integer v = integer(map, key);
        return v != null ? v : fallback;
    }

    private static double doubleOr(Map<?, ?> map, String key, double fallback) {
        Object v = map.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : fallback;
    }

    private static boolean bool(Map<?, ?> map, String key) {
        return Boolean.TRUE.equals(map.get(key));
    }

    private static double[] roi(Map<?, ?> args) {
        double[] r = {-1, -1, -1, -1};
        if (args.get("roi") instanceof Map) {
            Map<?, ?> roi = (Map<?, ?>) args.get("roi");
            r[0] = doubleOr(roi, "xMin", -1);
            r[1] = doubleOr(roi, "yMin", -1);
            r[2] = doubleOr(roi, "xMax", -1);
            r[3] = doubleOr(roi, "yMax", -1);
        }
        return r;
    }

    private static Object frameMap(TfFrameHandler.TfFrame frame) {
        return frame != null ? frame.toMap() : null;
    }

    // ---------------------------------------------------------------------------------------
    // Method calls
    // ---------------------------------------------------------------------------------------

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result rawResult) {
        final Reply result = new Reply(rawResult);
        try {
            handle(call, result);
        } catch (Throwable t) {
            // Malformed arguments must surface as a Dart error, never as a crash.
            Log.e(TAG, call.method + " failed: " + t);
            result.error(call.method, t.getMessage() != null ? t.getMessage() : t.toString(), null);
        }
    }

    @SuppressWarnings("unchecked")
    private void handle(MethodCall call, Reply result) {
        final Map<?, ?> args = argsMap(call);
        switch (call.method) {
            case "createView": {
                Object selector = args.get("cameraSelector");
                String cameraId = selector instanceof Map ? str((Map<?, ?>) selector, "id") : null;
                // Opening it would silently kill the headless stream that owns the device.
                if (cameraId != null && HeadlessTfCameraManager.getInstance().isActive(cameraId)) {
                    result.error("createView", "Camera " + cameraId + " is in use by a TF camera stream", null);
                    break;
                }
                Integer viewId = integer(args, "viewId");
                if (viewId != null) FlutterEventChannel.getInstance().bindView(viewId, this);
                Long id = CameraViewFactory.getInstance().createView((Map<String, Object>) args);
                result.success(id);
                break;
            }
            case "disposeView": {
                CameraViewFactory.getInstance().disposeView((Map<String, Object>) args);
                result.success(true);
                break;
            }
            case "availableCameras": {
                CameraUtil.getInstance().availableCameras(result::success);
                break;
            }
            case "broadcastPermissionGranted": {
                CameraViewFactory.getInstance().broadcastPermissionGranted();
                result.success(true);
                break;
            }
            case "requestPermission": {
                CameraPermissionsUtil.getInstance().requestPermissions(
                        (String errCode, String errDesc) -> result.success(errCode == null));
                break;
            }
            case "convertNv21ToJpeg": {
                convertNv21ToJpeg(args, result);
                break;
            }
            case "registerRawStream": {
                result.success(CameraStreamManager.getInstance().add(str(args, "cameraId"), intOr(args, "fps", 5)));
                break;
            }
            case "disposeRawStream": {
                CameraStreamManager.getInstance().dispose(str(args, "cameraId"));
                result.success(true);
                break;
            }
            case "newWebRtcStream": {
                List<Map<String, Object>> iceServers = (List<Map<String, Object>>) args.get("iceServers");
                WebRtcStreamHandler handler = WebRtcStreamHandler.getInstance();
                WebRtcStreamer streamer = handler.newStream(channel);
                result.success(streamer.id);
                handler.runSignaling(() -> streamer.start(iceServers));
                break;
            }
            case "stopWebRtcStream": {
                WebRtcStreamHandler handler = WebRtcStreamHandler.peekInstance();
                if (handler != null) handler.stopStream(str(args, "streamId"));
                result.success(true);
                break;
            }
            case "createWebRtcAnswer": {
                String streamId = str(args, "streamId");
                String offerSdp = str(args, "sdp");
                List<Map<String, Object>> iceServers = (List<Map<String, Object>>) args.get("iceServers");
                WebRtcStreamHandler handler = WebRtcStreamHandler.getInstance();
                WebRtcStreamer streamer = handler.getStreamById(streamId);
                if (streamer == null) {
                    result.error("WebRtcStreamNotFound", "Stream not found: " + streamId, null);
                    break;
                }
                boolean queued = handler.runSignaling(() -> {
                    try {
                        streamer.setOffer(offerSdp, iceServers, new WebRtcStreamer.AnswerCallback() {
                            @Override
                            public void onAnswer(String sdp) {
                                result.success(sdp);
                            }

                            @Override
                            public void onError(String message) {
                                result.error("WebRtcAnswerFailed", message, null);
                            }
                        });
                    } catch (Throwable t) {
                        result.error("WebRtcAnswerFailed", String.valueOf(t.getMessage()), null);
                    }
                });
                if (!queued) {
                    result.error("WebRtcAnswerFailed", "WebRTC signaling is shut down", null);
                }
                break;
            }
            case "addWebRtcCandidate": {
                WebRtcStreamHandler handler = WebRtcStreamHandler.peekInstance();
                WebRtcStreamer streamer = handler != null ? handler.getStreamById(str(args, "streamId")) : null;
                if (streamer != null) {
                    String sdpMid = str(args, "sdpMid");
                    int sdpMLineIndex = intOr(args, "sdpMLineIndex", 0);
                    String candidate = str(args, "candidate");
                    handler.runSignaling(() -> streamer.addIceCandidate(sdpMid, sdpMLineIndex, candidate));
                }
                result.success(true);
                break;
            }
            case "sendWebRtcDataMessage": {
                WebRtcStreamHandler handler = WebRtcStreamHandler.peekInstance();
                WebRtcStreamer streamer = handler != null ? handler.getStreamById(str(args, "streamId")) : null;
                if (streamer != null) {
                    String message = str(args, "message");
                    handler.runSignaling(() -> streamer.sendDataMessage(message));
                }
                result.success(true);
                break;
            }
            case "pushWebRtcFrame": {
                I420Image image = new I420Image(
                        intOr(args, "width", 0),
                        intOr(args, "height", 0),
                        intOr(args, "rotation", 0),
                        (byte[]) args.get("dataY"),
                        intOr(args, "strideY", 0),
                        (byte[]) args.get("dataU"),
                        intOr(args, "strideU", 0),
                        (byte[]) args.get("dataV"),
                        intOr(args, "strideV", 0),
                        intOr(args, "pixelStrideU", 1),
                        intOr(args, "pixelStrideV", 1)
                );
                WebRtcStreamHandler.getInstance().pushFrame(image, str(args, "streamId"));
                result.success(true);
                break;
            }
            case "registerWebRtcCameraFeed": {
                android.util.Size streamSize = CameraStreamManager.parseStreamSizeFromArgs(args);
                boolean res = CameraStreamManager.getInstance().addWebRtcFeed(
                        str(args, "cameraId"),
                        intOr(args, "fps", 12),
                        bool(args, "alsoDeliverToFlutter"),
                        streamSize
                );
                result.success(res);
                break;
            }
            case "disposeWebRtcCameraFeed": {
                CameraStreamManager.getInstance().removeWebRtcFeed(str(args, "cameraId"));
                result.success(true);
                break;
            }
            case "registerTfCameraStream": {
                result.success(HeadlessTfCameraManager.getInstance().register((Map<String, Object>) args));
                break;
            }
            case "disposeTfCameraStream": {
                HeadlessTfCameraManager.getInstance().dispose(str(args, "cameraId"));
                result.success(true);
                break;
            }
            case "setFlash": {
                DeviceCameraUtils.getInstance().setFlash(bool(args, "value"));
                result.success(true);
                break;
            }
            case "setExposureCompensation": {
                BaseCamera camera = CameraViewFactory.getInstance().getCameraById(str(args, "cameraId"));
                if (camera != null) {
                    camera.setExposureCompensation(intOr(args, "value", 0));
                }
                result.success(true);
                break;
            }
            case "setZoom": {
                BaseCamera camera = CameraViewFactory.getInstance().getCameraById(str(args, "cameraId"));
                if (camera != null) {
                    camera.setZoom((float) doubleOr(args, "zoom", 1.0));
                }
                result.success(true);
                break;
            }
            case "loadTfModel": {
                String assetPath = str(args, "assetPath");
                String key = str(args, "key");
                String delegate = str(args, "delegate");
                Integer threads = integer(args, "threads");
                background(result, "loadTfModel", () -> {
                    TfModelHandler.getInstance().loadModel(assetPath, key, delegate, threads);
                    return true;
                });
                break;
            }
            case "runTfInference": {
                runTfInference((Map<String, Object>) args, result);
                break;
            }
            case "disposeTfModel": {
                String key = str(args, "key");
                background(result, "disposeTfModel", () -> {
                    TfModelHandler.getInstance().disposeModelByKey(key);
                    return true;
                });
                break;
            }
            case "getInferenceResultScaledCroppedFrame": {
                String id = str(args, "id");
                background(result, call.method, () -> frameMap(TfModelHandler.getInstance().getScaledCroppedFrame(id)));
                break;
            }
            case "getInferenceResultCroppedFrame": {
                String id = str(args, "id");
                background(result, call.method, () -> frameMap(TfModelHandler.getInstance().getCroppedFrame(id)));
                break;
            }
            case "getInferenceResultInferenceFrame": {
                String id = str(args, "id");
                result.success(frameMap(TfModelHandler.getInstance().getInferenceFrame(id)));
                break;
            }
            case "getInferenceResultRawFrame": {
                String id = str(args, "id");
                result.success(frameMap(TfModelHandler.getInstance().getRawFrame(id)));
                break;
            }
            case "closeTfFrame": {
                TfFrameHandler.getInstance().closeFrame(str(args, "id"));
                result.success(true);
                break;
            }
            case "registerTfFrameFromJpeg": {
                byte[] jpegBytes = (byte[]) args.get("jpegBytes");
                background(result, call.method, () -> TfFrameHandler.getInstance().registerFrameFromJpeg(jpegBytes));
                break;
            }
            case "registerTfFrameCopy": {
                String frameId = str(args, "frameId");
                background(result, call.method, () -> TfFrameHandler.getInstance().registerFrameCopy(frameId));
                break;
            }
            case "registerTfFrameCrop": {
                String frameId = str(args, "frameId");
                int x = intOr(args, "x", 0);
                int y = intOr(args, "y", 0);
                int width = intOr(args, "width", 0);
                int height = intOr(args, "height", 0);
                Integer resizeWidth = null;
                Integer resizeHeight = null;
                if (args.get("resizeTo") instanceof Map) {
                    resizeWidth = integer((Map<?, ?>) args.get("resizeTo"), "width");
                    resizeHeight = integer((Map<?, ?>) args.get("resizeTo"), "height");
                }
                final Integer rw = resizeWidth;
                final Integer rh = resizeHeight;
                background(result, call.method, () -> TfFrameHandler.getInstance()
                        .registerFrameCrop(frameId, x, y, width, height, rw, rh));
                break;
            }
            case "getTfFrameJpeg": {
                String id = str(args, "id");
                background(result, call.method, () -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("bytes", TfFrameHandler.getInstance().getFrameJpeg(id));
                    return map;
                });
                break;
            }
            case "getTfFrameBlurScore": {
                String id = str(args, "id");
                int sampleStep = intOr(args, "sampleStep", 2);
                double[] r = roi(args);
                background(result, call.method, () -> TfFrameHandler.getInstance()
                        .computeBlurScore(id, r[0], r[1], r[2], r[3], sampleStep));
                break;
            }
            case "getTfFrameIlluminationScore": {
                String id = str(args, "id");
                int sampleStep = intOr(args, "sampleStep", 2);
                double[] r = roi(args);
                background(result, call.method, () -> TfFrameHandler.getInstance()
                        .computeIlluminationScore(id, r[0], r[1], r[2], r[3], sampleStep));
                break;
            }
            case "closeTfInferenceResult": {
                TfModelHandler.getInstance().closeTfInferenceResult(str(args, "id"));
                result.success(true);
                break;
            }
            default:
                result.notImplemented();
                break;
        }
    }

    private void runTfInference(Map<String, Object> args, Reply result) {
        final MethodChannel ch = channel;
        TfInferenceInput input = new TfInferenceInput(args, (data, modelKey) -> {
            CompletableFuture<List<Map<String, Object>>> future = new CompletableFuture<>();
            uiHandler.post(() -> {
                Map<String, Object> payload = new HashMap<>();
                payload.put("modelKey", modelKey);
                payload.put("output", data);
                try {
                    ch.invokeMethod("processTfOutput", payload, new Result() {
                        @Override
                        @SuppressWarnings("unchecked")
                        public void success(@Nullable Object value) {
                            future.complete(value instanceof List
                                    ? (List<Map<String, Object>>) value
                                    : new ArrayList<>());
                        }

                        @Override
                        public void error(@NonNull String errorCode, @Nullable String errorMessage, @Nullable Object errorDetails) {
                            future.completeExceptionally(new IllegalStateException(errorMessage));
                        }

                        @Override
                        public void notImplemented() {
                            future.completeExceptionally(new IllegalStateException("processTfOutput not implemented"));
                        }
                    });
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
            try {
                // Bounded: a detached engine never answers, and the single inference worker
                // must not stay blocked forever.
                return future.get(TF_OUTPUT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                throw new java.util.concurrent.ExecutionException("processTfOutput timed out", e);
            }
        });
        TfModelHandler.getInstance().runInference(input, new TfModelHandler.InferenceCallback() {
            @Override
            public void success(List<Map<String, Object>> data) {
                result.success(data);
            }

            @Override
            public void error(String e) {
                result.error("TfModelHandlerError", e, null);
            }
        });
    }

    private void convertNv21ToJpeg(Map<?, ?> arg, Reply result) {
        final byte[] bytes = arg.get("bytes") instanceof byte[] ? (byte[]) arg.get("bytes") : null;
        final Integer width = integer(arg, "width");
        final Integer height = integer(arg, "height");
        if (bytes == null || width == null || height == null || width <= 0 || height <= 0
                || bytes.length < width * height * 3 / 2) {
            result.error("Null argument", "bytes, width, height must be a valid NV21 frame", null);
            return;
        }
        final int quality = Math.max(1, Math.min(100, intOr(arg, "quality", 100)));
        final int filter = intOr(arg, "filter", 0);
        final int rotation = (int) Math.round(doubleOr(arg, "rotation", 0));
        final Map<?, ?> crop = arg.get("crop") instanceof Map ? (Map<?, ?>) arg.get("crop") : null;

        background(result, "convertNv21ToJpeg", () -> {
            ByteArrayPoolUtil.PoolItem cropEntry = null;
            ByteArrayPoolUtil.PoolItem resizeEntry = null;
            try {
                byte[] finalBytes = bytes;
                int finalWidth = width;
                int finalHeight = height;

                if (crop != null) {
                    Integer cWidth = integer(crop, "width");
                    Integer cHeight = integer(crop, "height");
                    Integer left = integer(crop, "left");
                    Integer top = integer(crop, "top");

                    if (cWidth != null && cHeight != null && left != null && top != null) {
                        int[] raw = rawCropRect(left, top, cWidth, cHeight, width, height, rotation);
                        if (raw != null) {
                            cropEntry = byteArrayPool.acquire(raw[2] * raw[3] * 3 / 2);
                            NativeUtil.cropNv21(bytes, width, height, cropEntry.data, raw[0], raw[1], raw[2], raw[3]);
                            finalWidth = raw[2];
                            finalHeight = raw[3];
                            finalBytes = cropEntry.data;
                        }
                    }

                    Map<?, ?> resize = crop.get("resize") instanceof Map ? (Map<?, ?>) crop.get("resize") : null;
                    if (resize != null) {
                        int rWidth = intOr(resize, "width", -1);
                        int rHeight = intOr(resize, "height", -1);
                        if (rotation == 90 || rotation == 270) {
                            int tmp = rWidth;
                            rWidth = rHeight;
                            rHeight = tmp;
                        }
                        if (rWidth == -1 && rHeight == -1) {
                            int minSize = Math.min(finalWidth, finalHeight);
                            rWidth = minSize;
                            rHeight = minSize;
                        }
                        rWidth = evenClamp(rWidth, finalWidth);
                        rHeight = evenClamp(rHeight, finalHeight);
                        if (rWidth > 0 && rHeight > 0) {
                            resizeEntry = byteArrayPool.acquire(rWidth * rHeight * 3 / 2);
                            NativeUtil.resizeNv21(finalBytes, finalWidth, finalHeight, resizeEntry.data, rWidth, rHeight);
                            finalWidth = rWidth;
                            finalHeight = rHeight;
                            finalBytes = resizeEntry.data;
                        }
                    }
                }

                NativeUtil.applyFilter(finalBytes, finalWidth, finalHeight, filter);
                return ImageConverterUtil.nv21ToJpeg(finalBytes, finalWidth, finalHeight, quality, rotation);
            } finally {
                byteArrayPool.release(cropEntry);
                byteArrayPool.release(resizeEntry);
            }
        });
    }

    /** Clamps to [2, max] and rounds down to even (NV21 chroma is 2x2 subsampled). */
    static int evenClamp(int value, int max) {
        int v = Math.min(value, max);
        v &= ~1;
        return Math.max(v > 0 ? 2 : 0, v);
    }

    /**
     * Maps a crop expressed in the rotated (displayed) frame to raw sensor coordinates and
     * clamps it to the frame, with even offsets/sizes. Returns {left, top, width, height}, or
     * null when the crop does not intersect the frame.
     */
    static int[] rawCropRect(int left, int top, int cWidth, int cHeight, int width, int height, int rotation) {
        int rawLeft = left;
        int rawTop = top;
        int rawWidth = cWidth;
        int rawHeight = cHeight;
        if (rotation == 90) {
            rawLeft = top;
            rawTop = height - (left + cWidth);
            rawWidth = cHeight;
            rawHeight = cWidth;
        } else if (rotation == 180) {
            rawLeft = width - (left + cWidth);
            rawTop = height - (top + cHeight);
        } else if (rotation == 270) {
            rawLeft = width - (top + cHeight);
            rawTop = left;
            rawWidth = cHeight;
            rawHeight = cWidth;
        }
        int x0 = Math.max(0, rawLeft) & ~1;
        int y0 = Math.max(0, rawTop) & ~1;
        int x1 = Math.min(width, rawLeft + rawWidth);
        int y1 = Math.min(height, rawTop + rawHeight);
        int w = (x1 - x0) & ~1;
        int h = (y1 - y0) & ~1;
        if (w < 2 || h < 2) return null;
        return new int[]{x0, y0, w, h};
    }
}
