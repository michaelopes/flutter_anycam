package br.dev.michaellopes.flutter_anycam;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.util.List;
import java.util.Map;

import br.dev.michaellopes.flutter_anycam.integration.FlutterEventChannel;
import br.dev.michaellopes.flutter_anycam.integration.FlutterViewFactory;
import br.dev.michaellopes.flutter_anycam.utils.CameraUtil;
import br.dev.michaellopes.flutter_anycam.utils.ContextUtil;
import br.dev.michaellopes.flutter_anycam.utils.ImageConverterUtil;
import br.dev.michaellopes.flutter_anycam.utils.LivecycleUtil;
import br.dev.michaellopes.flutter_anycam.utils.CameraPermissionsUtil;
import br.dev.michaellopes.flutter_anycam.utils.SurfaceTextureUtil;
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

    private final FlutterViewFactory viewFactory = new FlutterViewFactory();

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        CameraPermissionsUtil.getINSTANCE().init(binding::addRequestPermissionsResultListener);
        CameraUtil.init(binding.getActivity());
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
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        SurfaceTextureUtil.init(flutterPluginBinding.getTextureRegistry());


        flutterPluginBinding.getPlatformViewRegistry().registerViewFactory("br.dev.michaellopes.flutter_anycam/view", viewFactory);
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "br.dev.michaellopes.flutter_anycam/channel");
        eventChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(), "br.dev.michaellopes.flutter_anycam/event");

        channel.setMethodCallHandler(this);
        eventChannel.setStreamHandler(FlutterEventChannel.getINSTANCE());
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        if (call.method.equals("availableCameras")) {
            List<Map<String, Object>> cameras = CameraUtil.availableCameras();
            result.success(cameras);
        } else if (call.method.equals("convertNv21ToJpeg")) {
            convertNv21ToJpeg(call, result);
        } else {
            result.notImplemented();
        }
    }


    private void convertNv21ToJpeg(final MethodCall call, final MethodChannel.Result result) {
        @SuppressWarnings("unchecked") final Map<String, Object> arg = (Map<String, Object>) call.arguments;

        final byte[] bytes = arg != null ? (byte[]) arg.get("bytes") : null;
        final Integer width = arg != null ? (Integer) arg.get("width") : null;
        final Integer height = arg != null ? (Integer) arg.get("height") : null;
        final Integer quality = arg != null && arg.get("quality") != null ? (Integer) arg.get("quality") : 100;
        final Float rotation = arg != null && arg.get("rotation") != null
                ? ((Number) arg.get("rotation")).floatValue()
                : 0f;

        if (bytes == null || width == null || height == null) {
            result.error("Null argument", "bytes, width, height must not be null", null);
            return;
        }

        new Thread(() -> {
            try {
                byte[] bs = ImageConverterUtil.nv21ToJpeg(bytes, width, height, quality, rotation);
                result.success(bs);
            } catch (final Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> result.error("Processing error", e.getMessage(), null)
                );
            }
        }).start();
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
    }
}
