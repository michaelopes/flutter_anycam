package br.dev.michaellopes.flutter_anycam;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import br.dev.michaellopes.flutter_anycam.camera.BaseCamera;
import br.dev.michaellopes.flutter_anycam.integration.CameraViewFactory;
import br.dev.michaellopes.flutter_anycam.integration.FlutterEventChannel;
import br.dev.michaellopes.flutter_anycam.model.TfInferenceInput;
import br.dev.michaellopes.flutter_anycam.stream.CameraStreamManager;
import br.dev.michaellopes.flutter_anycam.stream.HeadlessTfCameraManager;
import br.dev.michaellopes.flutter_anycam.tensorflow.TfFrameHandler;
import br.dev.michaellopes.flutter_anycam.tensorflow.TfModelHandler;
import br.dev.michaellopes.flutter_anycam.webrtc.I420Image;
import br.dev.michaellopes.flutter_anycam.webrtc.WebRtcStreamHandler;
import br.dev.michaellopes.flutter_anycam.webrtc.WebRtcStreamer;
import br.dev.michaellopes.flutter_anycam.utils.ByteArrayPoolUtil;
import br.dev.michaellopes.flutter_anycam.utils.CameraUtil;
import br.dev.michaellopes.flutter_anycam.utils.ContextUtil;
import br.dev.michaellopes.flutter_anycam.utils.DeviceCameraUtils;
import br.dev.michaellopes.flutter_anycam.utils.ImageConverterUtil;
import br.dev.michaellopes.flutter_anycam.utils.LivecycleUtil;
import br.dev.michaellopes.flutter_anycam.utils.CameraPermissionsUtil;
import br.dev.michaellopes.flutter_anycam.utils.NativeUtil;
import io.flutter.Log;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

/**
 * FlutterAnycamPlugin
 */
public class FlutterAnycamPlugin implements FlutterPlugin, MethodCallHandler, ActivityAware {
    private MethodChannel channel;
    private EventChannel eventChannel;
    final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    ByteArrayPoolUtil byteArrayPool = new ByteArrayPoolUtil(6, 30);

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        CameraPermissionsUtil.getInstance().init(binding::addRequestPermissionsResultListener);
        ContextUtil.init(binding.getActivity());
        LivecycleUtil.init(binding.getLifecycle());

    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
    }

    @Override
    public void onDetachedFromActivity() {
        FlutterEventChannel.getInstance().release();
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        ContextUtil.init(flutterPluginBinding.getApplicationContext());
        CameraUtil.getInstance().init(flutterPluginBinding.getApplicationContext());

        CameraViewFactory.getInstance().init(flutterPluginBinding.getTextureRegistry());

        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "br.dev.michaellopes.flutter_anycam/channel");
        eventChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(), "br.dev.michaellopes.flutter_anycam/event");

        channel.setMethodCallHandler(this);
        eventChannel.setStreamHandler(FlutterEventChannel.getInstance());
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        switch (call.method) {
            case "createView": {
                HashMap<String, Object> args1 = (HashMap<String, Object>) call.arguments;
                Long id = CameraViewFactory.getInstance().createView(args1);
                uiHandler.post(() -> {
                    result.success(id);
                });
                break;
            }
            case "disposeView": {
                HashMap<String, Object> args2 = (HashMap<String, Object>) call.arguments;
                CameraViewFactory.getInstance().disposeView(args2);
                uiHandler.post(() -> {
                    result.success(true);
                });
                break;
            }
            case "availableCameras": {
                CameraUtil.getInstance().availableCameras(result::success);
                break;
            }
            case "broadcastPermissionGranted": {
                CameraViewFactory.getInstance().broadcastPermissionGranted();
                uiHandler.post(() -> {
                    result.success(true);
                });
                break;
            }
            case "requestPermission": {
                CameraPermissionsUtil.getInstance().requestPermissions((String errCode, String errDesc) -> {
                    if (errCode == null) {
                        result.success(true);
                    } else {
                        result.success(false);
                    }
                });
                break;
            }
            case "convertNv21ToJpeg": {
                convertNv21ToJpeg(call, result);
                break;
            }
            case "registerRawStream": {
                HashMap<?, ?> p1 = (HashMap<?, ?>) call.arguments;
                String cameraId = (String) p1.get("cameraId");
                int fps = (int) p1.get("fps");
                boolean res = CameraStreamManager.getInstance().add(cameraId, fps);
                uiHandler.post(() -> {
                    result.success(res);
                });
                break;
            }
            case "disposeRawStream": {
                HashMap<?, ?> p2 = (HashMap<?, ?>) call.arguments;
                String cId = (String) p2.get("cameraId");
                CameraStreamManager.getInstance().dispose(cId);
                uiHandler.post(() -> {
                    result.success(true);
                });
                break;
            }
            case "newWebRtcStream": {
                List<Map<String, Object>> iceServers = call.argument("iceServers");
                WebRtcStreamer streamer = WebRtcStreamHandler.getInstance().newStream(channel);
                result.success(streamer.id);
                streamer.start(iceServers);
                break;
            }
            case "stopWebRtcStream": {
                String streamId = call.argument("streamId");
                WebRtcStreamer streamer = WebRtcStreamHandler.getInstance().getStreamById(streamId);
                if (streamer != null) {
                    streamer.stop();
                    WebRtcStreamHandler.getInstance().removeStream(streamer);
                }
                result.success(true);
                break;
            }
            case "createWebRtcAnswer": {
                String streamId = call.argument("streamId");
                String offerSdp = call.argument("sdp");
                List<Map<String, Object>> iceServers = call.argument("iceServers");
                WebRtcStreamer streamer = WebRtcStreamHandler.getInstance().getStreamById(streamId);
                if (streamer != null) {
                    streamer.setOffer(offerSdp, iceServers, result::success);
                } else {
                    result.error("WebRtcStreamNotFound", "Stream not found: " + streamId, null);
                }
                break;
            }
            case "addWebRtcCandidate": {
                String streamId = call.argument("streamId");
                String sdpMid = call.argument("sdpMid");
                int sdpMLineIndex = call.argument("sdpMLineIndex");
                String candidate = call.argument("candidate");
                WebRtcStreamer streamer = WebRtcStreamHandler.getInstance().getStreamById(streamId);
                if (streamer != null) {
                    streamer.addIceCandidate(sdpMid, sdpMLineIndex, candidate);
                }
                result.success(true);
                break;
            }
            case "sendWebRtcDataMessage": {
                String streamId = call.argument("streamId");
                String message = call.argument("message");
                WebRtcStreamer streamer = WebRtcStreamHandler.getInstance().getStreamById(streamId);
                if (streamer != null) {
                    streamer.sendDataMessage(message);
                }
                result.success(true);
                break;
            }
            case "pushWebRtcFrame": {
                Integer width = call.argument("width");
                Integer height = call.argument("height");
                Integer rotation = call.argument("rotation");
                Integer strideY = call.argument("strideY");
                Integer strideU = call.argument("strideU");
                Integer strideV = call.argument("strideV");
                Integer pixelStrideU = call.argument("pixelStrideU");
                Integer pixelStrideV = call.argument("pixelStrideV");
                String streamId = call.argument("streamId");

                byte[] dataY = call.argument("dataY");
                byte[] dataU = call.argument("dataU");
                byte[] dataV = call.argument("dataV");

                I420Image image = new I420Image(
                        width,
                        height,
                        rotation,
                        dataY,
                        strideY,
                        dataU,
                        strideU,
                        dataV,
                        strideV,
                        pixelStrideU,
                        pixelStrideV
                );

                WebRtcStreamHandler.getInstance().pushFrame(image, streamId);
                result.success(true);
                break;
            }
            case "registerWebRtcCameraFeed": {
                HashMap<?, ?> args = (HashMap<?, ?>) call.arguments;
                String cameraId = (String) args.get("cameraId");
                int fps = (int) args.get("fps");
                String streamId = (String) args.get("streamId");
                boolean alsoDeliverToFlutter = args.get("alsoDeliverToFlutter") != null
                        && (boolean) args.get("alsoDeliverToFlutter");
                boolean res = CameraStreamManager.getInstance().addWebRtcFeed(
                        cameraId,
                        fps,
                        streamId,
                        alsoDeliverToFlutter
                );
                uiHandler.post(() -> result.success(res));
                break;
            }
            case "disposeWebRtcCameraFeed": {
                HashMap<?, ?> args = (HashMap<?, ?>) call.arguments;
                String cameraId = (String) args.get("cameraId");
                CameraStreamManager.getInstance().removeWebRtcFeed(cameraId);
                uiHandler.post(() -> result.success(true));
                break;
            }
            case "registerTfCameraStream": {
                HashMap<String, Object> args = (HashMap<String, Object>) call.arguments;
                boolean res = HeadlessTfCameraManager.getInstance().register(args);
                uiHandler.post(() -> result.success(res));
                break;
            }
            case "disposeTfCameraStream": {
                HashMap<?, ?> args = (HashMap<?, ?>) call.arguments;
                String cameraId = (String) args.get("cameraId");
                HeadlessTfCameraManager.getInstance().dispose(cameraId);
                uiHandler.post(() -> result.success(true));
                break;
            }
            case "setFlash": {
                HashMap<?, ?> args3 = (HashMap<?, ?>) call.arguments;
                boolean value = (boolean) args3.get("value");
                DeviceCameraUtils.getInstance().setFlash(value);
                uiHandler.post(() -> {
                    result.success(true);
                });
                break;
            }
            case "setExposureCompensation": {
                HashMap<?, ?> args4 = (HashMap<?, ?>) call.arguments;
                Number value = (Number) args4.get("value");
                int val = value.intValue();
                String caId = (String) args4.get("cameraId");

                BaseCamera camera = CameraViewFactory.getInstance().getCameraById(caId);
                if (camera != null) {
                    camera.setExposureCompensation(val);
                }

                uiHandler.post(() -> {
                    result.success(true);
                });
                break;
            }
            case "setZoom": {
                HashMap<?, ?> args4 = (HashMap<?, ?>) call.arguments;
                Number zoomNumber = (Number) args4.get("zoom");
                float zoom = zoomNumber.floatValue();
                String caId = (String) args4.get("cameraId");

                BaseCamera camera = CameraViewFactory.getInstance().getCameraById(caId);
                if (camera != null) {
                    camera.setZoom(zoom);
                }

                uiHandler.post(() -> {
                    result.success(true);
                });
                break;
            }
            case "loadTfModel": {
                Map<String, Object> ldata = (Map<String, Object>) call.arguments;
                String assetPath = (String) ldata.get("assetPath");
                String key = (String) ldata.get("key");
                String delegate = (String) ldata.get("delegate");
                Integer threads = (Integer) ldata.get("threads");
                TfModelHandler.getInstance().loadModel(assetPath, key, delegate, threads);
                uiHandler.post(() -> {
                    result.success(true);
                });
                break;
            }
            case "runTfInference": {
                Map<String, Object> idata = (Map<String, Object>) call.arguments;
                TfInferenceInput input = new TfInferenceInput(idata, (data, modelKey) -> {
                    CompletableFuture<List<Map<String, Object>>> future = new CompletableFuture<>();
                    uiHandler.post(() -> {
                        channel.invokeMethod("processTfOutput", new HashMap<String, Object>() {{
                            put("modelKey", modelKey);
                            put("output", data);
                        }}, new Result() {
                            @Override
                            public void success(@Nullable Object result1) {
                                List<Map<String, Object>> res1 = (List<Map<String, Object>>) result1;
                                future.complete(res1);
                            }

                            @Override
                            public void error(@NonNull String errorCode, @Nullable String errorMessage, @Nullable Object errorDetails) {
                                future.completeExceptionally(new Throwable(errorMessage));
                            }

                            @Override
                            public void notImplemented() {
                                future.completeExceptionally(new Throwable("notImplemented"));
                            }
                        });
                    });
                    return future.get();
                });
                TfModelHandler.getInstance().runInference(input, new TfModelHandler.InferenceCallback() {
                    @Override
                    public void success(List<Map<String, Object>> data) {
                        uiHandler.post(() -> {
                            result.success(data);
                        });
                    }

                    @Override
                    public void error(String e) {
                        uiHandler.post(() -> {
                            result.error("TfModelHandlerError", e, null);
                        });
                    }
                });
                break;
            }
            case "disposeTfModel": {
                Map<String, Object> ddata = (Map<String, Object>) call.arguments;
                String dkey = (String) ddata.get("key");
                executor.execute(() -> {
                    TfModelHandler.getInstance().disposeModelByKey(dkey);
                    uiHandler.post(() -> result.success(true));
                });
                break;
            }
            case "getInferenceResultScaledCroppedFrame": {
                Map<String, Object> ifdata = (Map<String, Object>) call.arguments;
                String rid = (String) ifdata.get("id");
               TfFrameHandler.TfFrame frame = TfModelHandler.getInstance().getScaledCroppedFrame(rid);
                uiHandler.post(() -> {
                    if(frame != null) {
                        result.success(frame.toMap());
                    } else {
                        result.success(null);
                    }
                });
                break;
            }
            case "getInferenceResultCroppedFrame": {
                Map<String, Object> ifdata = (Map<String, Object>) call.arguments;
                String rid = (String) ifdata.get("id");
                TfFrameHandler.TfFrame frame = TfModelHandler.getInstance().getCroppedFrame(rid);
                uiHandler.post(() -> {
                    if(frame != null) {
                        result.success(frame.toMap());
                    } else {
                        result.success(null);
                    }
                });
                break;
            }
            case "getInferenceResultInferenceFrame": {
                Map<String, Object> ifdata = (Map<String, Object>) call.arguments;
                String rid = (String) ifdata.get("id");
                TfFrameHandler.TfFrame frame = TfModelHandler.getInstance().getInferenceFrame(rid);
                uiHandler.post(() -> {
                    if(frame != null) {
                        result.success(frame.toMap());
                    } else {
                        result.success(null);
                    }
                });
                break;
            }
            case "getInferenceResultRawFrame": {
                Map<String, Object> ifdata = (Map<String, Object>) call.arguments;
                String rid = (String) ifdata.get("id");
                TfFrameHandler.TfFrame frame = TfModelHandler.getInstance().getRawFrame(rid);
                uiHandler.post(() -> {
                    if(frame != null) {
                        result.success(frame.toMap());
                    } else {
                        result.success(null);
                    }
                });
                break;
            }
            case "closeTfFrame": {
                Map<String, Object> fdata = (Map<String, Object>) call.arguments;
                String fid = (String) fdata.get("id");
                TfFrameHandler.getInstance().closeFrame(fid);
                uiHandler.post(() -> {
                    result.success(true);
                });
                break;
            }
            case "registerTfFrameFromJpeg": {
                Map<String, Object> data = (Map<String, Object>) call.arguments;
                byte[] jpegBytes = (byte[]) data.get("jpegBytes");
                try {
                    Map<String, Object> frameMap =
                            TfFrameHandler.getInstance().registerFrameFromJpeg(jpegBytes);
                    uiHandler.post(() -> {
                        result.success(frameMap);
                    });
                } catch (ExecutionException | InterruptedException e) {
                    uiHandler.post(() -> {
                        result.error("registerTfFrameFromJpeg", e.getMessage(), null);
                    });
                }
                break;
            }
            case "registerTfFrameCopy": {
                Map<String, Object> data = (Map<String, Object>) call.arguments;
                String frameId = (String) data.get("frameId");
                try {
                    Map<String, Object> frameMap =
                            TfFrameHandler.getInstance().registerFrameCopy(frameId);
                    uiHandler.post(() -> {
                        result.success(frameMap);
                    });
                } catch (ExecutionException | InterruptedException e) {
                    uiHandler.post(() -> {
                        result.error("registerTfFrameCopy", e.getMessage(), null);
                    });
                }
                break;
            }
            case "registerTfFrameCrop": {
                Map<String, Object> data = (Map<String, Object>) call.arguments;
                String frameId = (String) data.get("frameId");
                int x = ((Number) data.get("x")).intValue();
                int y = ((Number) data.get("y")).intValue();
                int width = ((Number) data.get("width")).intValue();
                int height = ((Number) data.get("height")).intValue();
                Integer resizeWidth = null;
                Integer resizeHeight = null;
                Object resizeTo = data.get("resizeTo");
                if (resizeTo instanceof Map) {
                    resizeWidth = (Integer) ((Map<?, ?>) resizeTo).get("width");
                    resizeHeight = (Integer) ((Map<?, ?>) resizeTo).get("height");
                }
                try {
                    Map<String, Object> frameMap = TfFrameHandler.getInstance().registerFrameCrop(
                            frameId, x, y, width, height, resizeWidth, resizeHeight
                    );
                    uiHandler.post(() -> {
                        result.success(frameMap);
                    });
                } catch (ExecutionException | InterruptedException e) {
                    uiHandler.post(() -> {
                        result.error("registerTfFrameCrop", e.getMessage(), null);
                    });
                }
                break;
            }
            case "getTfFrameJpeg": {
                Map<String, Object> fdata = (Map<String, Object>) call.arguments;
                String fid = (String) fdata.get("id");
                byte[] bytes = TfFrameHandler.getInstance().getFrameJpeg(fid);
                uiHandler.post(() -> {
                    result.success(new HashMap<String, Object>() {{
                        put("bytes", bytes);
                    }});
                });
                break;
            }
            case "getTfFrameBlurScore": {
                Map<String, Object> fdata = (Map<String, Object>) call.arguments;
                String fid = (String) fdata.get("id");
                Integer sampleStep = fdata.get("sampleStep") != null
                        ? ((Number) fdata.get("sampleStep")).intValue()
                        : 2;
                double xMin = -1;
                double yMin = -1;
                double xMax = -1;
                double yMax = -1;
                if (fdata.get("roi") instanceof Map) {
                    Map<?, ?> roi = (Map<?, ?>) fdata.get("roi");
                    if (roi.get("xMin") != null) xMin = ((Number) roi.get("xMin")).doubleValue();
                    if (roi.get("yMin") != null) yMin = ((Number) roi.get("yMin")).doubleValue();
                    if (roi.get("xMax") != null) xMax = ((Number) roi.get("xMax")).doubleValue();
                    if (roi.get("yMax") != null) yMax = ((Number) roi.get("yMax")).doubleValue();
                }
                Double score = TfFrameHandler.getInstance().computeBlurScore(
                        fid, xMin, yMin, xMax, yMax, sampleStep
                );
                uiHandler.post(() -> result.success(score));
                break;
            }
            case "getTfFrameIlluminationScore": {
                Map<String, Object> fdata = (Map<String, Object>) call.arguments;
                String fid = (String) fdata.get("id");
                Integer sampleStep = fdata.get("sampleStep") != null
                        ? ((Number) fdata.get("sampleStep")).intValue()
                        : 2;
                double xMin = -1;
                double yMin = -1;
                double xMax = -1;
                double yMax = -1;
                if (fdata.get("roi") instanceof Map) {
                    Map<?, ?> roi = (Map<?, ?>) fdata.get("roi");
                    if (roi.get("xMin") != null) xMin = ((Number) roi.get("xMin")).doubleValue();
                    if (roi.get("yMin") != null) yMin = ((Number) roi.get("yMin")).doubleValue();
                    if (roi.get("xMax") != null) xMax = ((Number) roi.get("xMax")).doubleValue();
                    if (roi.get("yMax") != null) yMax = ((Number) roi.get("yMax")).doubleValue();
                }
                Map<String, Double> stats = TfFrameHandler.getInstance().computeIlluminationScore(
                        fid, xMin, yMin, xMax, yMax, sampleStep
                );
                uiHandler.post(() -> result.success(stats));
                break;
            }
            case "closeTfInferenceResult": {
                Map<String, Object> data = (Map<String, Object>) call.arguments;
                String id = (String) data.get("id");
                TfModelHandler.getInstance().closeTfInferenceResult(id);
                uiHandler.post(() -> {
                    result.success(true);
                });
                break;
            }
            default:
                result.notImplemented();
                break;
        }
    }


    private void convertNv21ToJpeg(final MethodCall call, final MethodChannel.Result result) {
        @SuppressWarnings("unchecked") final Map<String, Object> arg = (Map<String, Object>) call.arguments;
        long start = System.currentTimeMillis();
        final byte[] bytes = arg != null ? (byte[]) arg.get("bytes") : null;
        final Integer width = arg != null ? (Integer) arg.get("width") : null;
        final Integer height = arg != null ? (Integer) arg.get("height") : null;
        final Integer quality = arg != null && arg.get("quality") != null ? (Integer) arg.get("quality") : 100;
        final Integer filter = arg != null && arg.get("filter") != null ? (Integer) arg.get("filter") : 0;
        final Float frotation = arg != null && arg.get("rotation") != null
                ? ((Number) arg.get("rotation")).floatValue()
                : 0f;
        if (bytes == null || width == null || height == null) {
            result.error("Null argument", "bytes, width, height must not be null", null);
            return;
        }

        final int rotation = Math.round(frotation);

        byte[] finalBytes = bytes;
        int finalWidth = width;
        int finalHeight = height;

        final AtomicReference<ByteArrayPoolUtil.PoolItem> resizeEntry = new AtomicReference<>(null);
        final AtomicReference<ByteArrayPoolUtil.PoolItem> cropEntry = new AtomicReference<>(null);


        if (arg.get("crop") != null) {
            final Map<String, Object> cs = (Map<String, Object>) arg.get("crop");

            Integer cWidth = (Integer) cs.get("width");
            Integer cHeight = (Integer) cs.get("height");
            Integer left = (Integer) cs.get("left");
            Integer top = (Integer) cs.get("top");

            if (cWidth != null && cHeight != null && left != null && top != null) {

                if (rotation == 90) {
                    int rawLeft = top;
                    int rawTop = height - (left + cWidth);

                    int rawWidth = cHeight;
                    int rawHeight = cWidth;

                    left = rawLeft;
                    top = rawTop;
                    cWidth = rawWidth;
                    cHeight = rawHeight;
                } else if (rotation == 180) {
                    int rawLeft = width - (left + cWidth);
                    int rawTop = height - (top + cHeight);

                    left = rawLeft;
                    top = rawTop;
                } else if (rotation == 270) {
                    int rawLeft = width - (top + cHeight);
                    int rawTop = left;

                    int rawWidth = cHeight;
                    int rawHeight = cWidth;

                    left = rawLeft;
                    top = rawTop;
                    cWidth = rawWidth;
                    cHeight = rawHeight;
                }

                cWidth = Math.min(cWidth, width - left);
                cHeight = Math.min(cHeight, height - top);

                int srcSize = cWidth * cHeight * 3 / 2;
                cropEntry.set(byteArrayPool.acquire(srcSize));

                NativeUtil.cropNv21(bytes, width, height, cropEntry.get().data, left, top, cWidth, cHeight);

                finalWidth = cWidth;
                finalHeight = cHeight;
                finalBytes = cropEntry.get().data;
            }

            final Map<String, Object> resize = (Map<String, Object>) cs.get("resize");
            if (resize != null) {
                int rWidth = (int) resize.get("width");
                int rHeight = (int) resize.get("height");
                if (rotation == 90 || rotation == 270) {
                    Integer sTemp = rWidth;
                    rWidth = rHeight;
                    rHeight = sTemp;
                }

                if (rWidth == -1 && rHeight == -1) {
                    int minSize = Math.min(finalWidth, finalHeight);
                    rWidth = minSize;
                    rHeight = minSize;
                }

                int srcSize = rWidth * rHeight * 3 / 2;
                resizeEntry.set(byteArrayPool.acquire(srcSize));

                if (rWidth > finalWidth) {
                    rWidth = finalWidth;
                }

                if (rHeight > finalHeight) {
                    rHeight = finalHeight;
                }

                NativeUtil.resizeNv21(finalBytes, finalWidth, finalHeight, resizeEntry.get().data, rWidth, rHeight);
                finalWidth = rWidth;
                finalHeight = rHeight;
                finalBytes = resizeEntry.get().data;

            }
        }

        NativeUtil.applyFilter(finalBytes, finalWidth, finalHeight, filter);
        final byte[] pFinalBytes = finalBytes;
        final int pFinalWidth = finalWidth;
        final int pFinalHeight = finalHeight;

        executor.execute(() -> {
            try {
                long timeMs = System.currentTimeMillis() - start;
                Log.d("JpegConversion_PERF", "time=" + timeMs + "ms");
                byte[] bs = ImageConverterUtil.nv21ToJpeg(pFinalBytes, pFinalWidth, pFinalHeight, quality, rotation);
                result.success(bs);
            } catch (final Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> result.error("Processing error", e.getMessage(), null));
            } finally {
                byteArrayPool.release(cropEntry.get());
                byteArrayPool.release(resizeEntry.get());
                cropEntry.set(null);
                resizeEntry.set(null);
            }
        });

//        new Thread(() -> {
//
        //       }).start();
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        CameraViewFactory.getInstance().disposeAll();
        TfModelHandler.getInstance().disposeAllModels();
        WebRtcStreamHandler.getInstance().stopAll();
        CameraUtil.getInstance().reset();
        FlutterEventChannel.getInstance().release();
        ImageConverterUtil.shutdown();
        byteArrayPool.shutdown();
        channel.setMethodCallHandler(null);
    }
}
