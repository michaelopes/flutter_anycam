package br.dev.michaellopes.flutter_anycam.camera;

import android.annotation.SuppressLint;

import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;


import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceRequest;
import androidx.camera.core.ImageAnalysis;

import java.util.HashMap;
import java.util.Map;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import br.dev.michaellopes.flutter_anycam.integration.FlutterEventChannel;
import br.dev.michaellopes.flutter_anycam.utils.DeviceCameraUtils;
import br.dev.michaellopes.flutter_anycam.utils.FrameRateLimiterUtil;


public class DeviceCamera extends BaseCamera {

    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();

    private ImageAnalysis imageAnalysis;

    FrameRateLimiterUtil<ImageProxy> limiter = new FrameRateLimiterUtil<ImageProxy>(getFps()) {
        @Override
        protected void onFrameLimited(ImageProxy image) {
            DeviceCamera.this.analyze(image);
            image.close();
        }

        @Override
        protected void onFrameSkipped(ImageProxy image) {
            image.close();
        }
    };

    public DeviceCamera(int viewId, Map<String, Object> params) {
        super(viewId, params);
    }


    @Override
    @SuppressLint("RestrictedApi")
    public void init() {
       synchronized (DeviceCameraUtils.getInstance()) {
           Preview.SurfaceProvider surfaceProvider = createSurfaceProvider();
           Preview preview = new Preview.Builder()
                   .build();

           preview.setSurfaceProvider(surfaceProvider);

           try {

               imageAnalysis = new ImageAnalysis.Builder()
                       .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                       .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                       .build();

               imageAnalysis.setAnalyzer(cameraExecutor, limiter::onNewFrame);
               DeviceCameraUtils.getInstance().bind(cameraSelector.getId(), preview, imageAnalysis);
               Size ps = preview.getAttachedSurfaceResolution();

               final Map<String, Object> result = new HashMap<>();
               result.put("width", 0);
               result.put("height", 0);
               result.put("isPortrait", 0);
               result.put("rotation", 0);

               FlutterEventChannel.getInstance().send(viewId, "onConnected", result);

           } catch (Exception e) {
               FlutterEventChannel.getInstance().send(viewId, "onFailed", new HashMap() {{
                   put("message", e.getMessage());
               }});
               e.printStackTrace();
           }
       }

    }

    private @NonNull Preview.SurfaceProvider createSurfaceProvider() {
        return request -> {
            Surface flutterSurface = surfaceHolder.getSurface();
            request.provideSurface(
                    flutterSurface,
                    Executors.newSingleThreadExecutor(),
                    (result) -> {
                        //   flutterSurface.release();
                        int resultCode = result.getResultCode();
                        switch (resultCode) {
                            case SurfaceRequest.Result.RESULT_REQUEST_CANCELLED:
                            case SurfaceRequest.Result.RESULT_WILL_NOT_PROVIDE_SURFACE:
                            case SurfaceRequest.Result.RESULT_SURFACE_ALREADY_PROVIDED:
                            case SurfaceRequest.Result.RESULT_SURFACE_USED_SUCCESSFULLY:
                                break;
                            case SurfaceRequest.Result.RESULT_INVALID_SURFACE:
                            default:
                                throw new RuntimeException("Create Surface Provider error");
                        }
                    });
        };
    }


    @Override
    public void dispose() {
        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer();
            cameraExecutor.shutdown();
        }
        DeviceCameraUtils.getInstance().dispose(cameraSelector);
    }

    private Integer getCustomRotationDegrees() {
        if (cameraSelector.isForceSensorOrientation()) {
            return cameraSelector.getSensorOrientation();
        }
        return null;
    }

    public void analyze(@NonNull ImageProxy image) {
        try {
            //  Log.i("DeviceCamera", "Process onVideoFrameReceived");

            Map<String, Object> imageData = imageAnalysisUtil.imageProxyToFlutterResult(image, getCustomRotationDegrees());
            FlutterEventChannel.getInstance().send(viewId, "onVideoFrameReceived", imageData);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            image.close();
        }
    }
}
