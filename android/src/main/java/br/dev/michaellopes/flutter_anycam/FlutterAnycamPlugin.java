package br.dev.michaellopes.flutter_anycam;

import android.os.Handler;
import android.os.Looper;
import android.util.Size;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import br.dev.michaellopes.flutter_anycam.integration.CameraViewFactory;
import br.dev.michaellopes.flutter_anycam.integration.FlutterEventChannel;
import br.dev.michaellopes.flutter_anycam.stream.CameraStreamManager;
import br.dev.michaellopes.flutter_anycam.utils.CameraUtil;
import br.dev.michaellopes.flutter_anycam.utils.ContextUtil;
import br.dev.michaellopes.flutter_anycam.utils.DeviceCameraUtils;
import br.dev.michaellopes.flutter_anycam.utils.ImageConverterUtil;
import br.dev.michaellopes.flutter_anycam.utils.LivecycleUtil;
import br.dev.michaellopes.flutter_anycam.utils.CameraPermissionsUtil;
import br.dev.michaellopes.flutter_anycam.utils.YuvUtil;
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

        if (arg.get("crop") != null) {
            final Map<String, Object> cs = (Map<String, Object>) arg.get("crop");

            Integer cWidth = (Integer) cs.get("width");
            Integer cHeight = (Integer) cs.get("height");
            Integer left = (Integer) cs.get("left");
            Integer top = (Integer) cs.get("top");

            if (cWidth != null && cHeight != null && left != null && top != null) {
                if (rotation == 90 || rotation == 270) {
                    Integer sTemp = cWidth;
                    cWidth = cHeight;
                    cHeight = sTemp;

                    Integer pTemp = left;
                    left = top;
                    top = pTemp;
                }
                int srcSize = cWidth * cHeight * 3 / 2;
                byte[] out = new byte[srcSize];
                finalWidth = cWidth;
                finalHeight = cHeight;
                YuvUtil.cropNv21(bytes, width, height, out, left, top, cWidth, cHeight);
                finalBytes = out;
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

                if(rWidth == -1 && rHeight == -1) {
                    int minSize = Math.min(finalWidth, finalHeight);
                    rWidth = minSize;
                    rHeight = minSize;
                }

                int srcSize = rWidth * rHeight * 3 / 2;
                byte[] out = new byte[srcSize];

                if(rWidth > finalWidth) {
                    rWidth = finalWidth;
                }

                if(rHeight > finalHeight) {
                    rHeight = finalHeight;
                }

                YuvUtil.resizeNv21(finalBytes, finalWidth, finalHeight, out, rWidth, rHeight);
                finalWidth = rWidth;
                finalHeight = rHeight;
                finalBytes = out;

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
        channel.setMethodCallHandler(null);
    }
}
