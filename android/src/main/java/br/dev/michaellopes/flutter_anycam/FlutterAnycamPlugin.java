package br.dev.michaellopes.flutter_anycam;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import br.dev.michaellopes.flutter_anycam.camera.BaseCamera;
import br.dev.michaellopes.flutter_anycam.integration.CameraViewFactory;
import br.dev.michaellopes.flutter_anycam.integration.FlutterEventChannel;
import br.dev.michaellopes.flutter_anycam.stream.CameraStreamManager;
import br.dev.michaellopes.flutter_anycam.utils.ByteArrayPoolUtil;
import br.dev.michaellopes.flutter_anycam.utils.CameraUtil;
import br.dev.michaellopes.flutter_anycam.utils.ContextUtil;
import br.dev.michaellopes.flutter_anycam.utils.DeviceCameraUtils;
import br.dev.michaellopes.flutter_anycam.utils.ImageConverterUtil;
import br.dev.michaellopes.flutter_anycam.utils.LivecycleUtil;
import br.dev.michaellopes.flutter_anycam.utils.CameraPermissionsUtil;
import br.dev.michaellopes.flutter_anycam.utils.YuvUtil;
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

    ByteArrayPoolUtil byteArrayPool = new ByteArrayPoolUtil(
            new ByteArrayPoolUtil.Config()
                    .maxIdle(6)
                    .idleTimeout(2, TimeUnit.MINUTES)    // expira idle após 2 min
                    .evictionInterval(30, TimeUnit.SECONDS) // varre a cada 30 s
                    .onEviction(entry -> Log.d("Pool", "Evictado: " + entry))
    );

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        CameraPermissionsUtil.getInstance().init(binding::addRequestPermissionsResultListener);
        CameraUtil.getInstance().init(binding.getActivity());
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

        CameraViewFactory.getInstance().init(flutterPluginBinding.getTextureRegistry());

        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "br.dev.michaellopes.flutter_anycam/channel");
        eventChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(), "br.dev.michaellopes.flutter_anycam/event");

        channel.setMethodCallHandler(this);
        eventChannel.setStreamHandler(FlutterEventChannel.getInstance());
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        switch (call.method) {
            case "createView":
                HashMap<String, Object> args1 = (HashMap<String, Object>) call.arguments;
                Long id = CameraViewFactory.getInstance().createView(args1);
                result.success(id);
                break;
            case "disposeView":
                HashMap<String, Object> args2 = (HashMap<String, Object>) call.arguments;
                CameraViewFactory.getInstance().disposeView(args2);
                result.success(true);
                break;
            case "availableCameras":
                CameraUtil.getInstance().availableCameras(result::success);
                break;
            case "broadcastPermissionGranted":
                CameraViewFactory.getInstance().broadcastPermissionGranted();
                result.success(true);
                break;
            case "requestPermission":
                CameraPermissionsUtil.getInstance().requestPermissions((String errCode, String errDesc) -> {
                    if (errCode == null) {
                        result.success(true);
                    } else {
                        result.success(false);
                    }
                });
                break;
            case "convertNv21ToJpeg":
                convertNv21ToJpeg(call, result);
                break;
            case "registerRawStream":
                HashMap<?, ?> p1 = (HashMap<?, ?>) call.arguments;
                String cameraId = (String) p1.get("cameraId");
                int fps = (int) p1.get("fps");
                boolean res = CameraStreamManager.getInstance().add(cameraId, fps);
                result.success(res);
                break;
            case "disposeRawStream":
                HashMap<?, ?> p2 = (HashMap<?, ?>) call.arguments;
                String cId = (String) p2.get("cameraId");
                CameraStreamManager.getInstance().dispose(cId);
                result.success(true);
                break;
            case "setFlash":
                HashMap<?, ?> args3 = (HashMap<?, ?>) call.arguments;
                boolean value = (boolean) args3.get("value");
                DeviceCameraUtils.getInstance().setFlash(value);
                result.success(true);
                break;
            case "setZoom":
                HashMap<?, ?> args4 = (HashMap<?, ?>) call.arguments;
                Number zoomNumber = (Number) args4.get("zoom");
                float zoom = zoomNumber.floatValue();
                String caId = (String) args4.get("cameraId");

                BaseCamera camera = CameraViewFactory.getInstance().getCameraById(caId);
                if (camera != null) {
                    camera.setZoom(zoom);
                }
                //  DeviceCameraUtils.getInstance().setZoom(zoom, caId);
                result.success(true);
                break;
            default:
                result.notImplemented();
                break;
        }
    }


    private void convertNv21ToJpeg(final MethodCall call, final MethodChannel.Result result) {
        @SuppressWarnings("unchecked") final Map<String, Object> arg = (Map<String, Object>) call.arguments;

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

        final AtomicReference<ByteArrayPoolUtil.Entry> resizeEntry = new AtomicReference<>(null);
        final AtomicReference<ByteArrayPoolUtil.Entry> cropEntry = new AtomicReference<>(null);


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
                // YuvUtil.cropNv21(bytes, width, height, out, left, top, cWidth, cHeight);


                // YuvUtil.cropNV21(bytes, width, height, outEntry.data, new Rect(left, top, left + cWidth, top + cHeight));


                YuvUtil.cropNv21(bytes, width, height, cropEntry.get().data, left, top, cWidth, cHeight);

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

                YuvUtil.resizeNv21(finalBytes, finalWidth, finalHeight, resizeEntry.get().data, rWidth, rHeight);
                finalWidth = rWidth;
                finalHeight = rHeight;
                finalBytes = resizeEntry.get().data;

            }
        }

        YuvUtil.applyFilter(finalBytes, finalWidth, finalHeight, filter);
        final byte[] pFinalBytes = finalBytes;
        final int pFinalWidth = finalWidth;
        final int pFinalHeight = finalHeight;

        executor.execute(() -> {
            try {
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
        FlutterEventChannel.getInstance().release();
        ImageConverterUtil.shutdown();
        byteArrayPool.shutdown();
        byteArrayPool.clear();
        channel.setMethodCallHandler(null);
    }
}
