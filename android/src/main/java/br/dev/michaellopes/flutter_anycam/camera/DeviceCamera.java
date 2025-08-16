package br.dev.michaellopes.flutter_anycam.camera;

import android.annotation.SuppressLint;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.camera.camera2.internal.Camera2CameraInfoImpl;

import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceRequest;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.core.ImageAnalysis;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.HashMap;
import java.util.Map;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import br.dev.michaellopes.flutter_anycam.integration.FlutterEventChannel;
import br.dev.michaellopes.flutter_anycam.utils.ContextUtil;
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

        Preview.SurfaceProvider surfaceProvider = createSurfaceProvider();
        Preview preview = new Preview.Builder()
                .build();

        preview.setSurfaceProvider(surfaceProvider);

        ListenableFuture<ProcessCameraProvider>  cameraProviderFuture = DeviceCameraUtils.getInstance().getCameraProviderFuture();
        cameraProviderFuture.addListener(() -> {

            try {

                Camera2CameraInfoImpl finalCameraInfo =  DeviceCameraUtils.getInstance().getCameraInfoById(cameraSelector.getId());
                if (finalCameraInfo != null) {

                    imageAnalysis = new ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                            .build();

                    imageAnalysis.setAnalyzer(cameraExecutor, limiter::onNewFrame);

                    DeviceCameraUtils.getInstance().bind(finalCameraInfo, preview, imageAnalysis);

                    Size ps = preview.getAttachedSurfaceResolution();

                    final Map<String, Object> result = new HashMap<>();
                    result.put("width", ps.getWidth());
                    result.put("height", ps.getHeight());
                    result.put("isPortrait", 0);
                    result.put("rotation", 0);

                    FlutterEventChannel.getINSTANCE().send(viewId, "onConnected", result);
                }

            } catch (Exception e) {
                FlutterEventChannel.getINSTANCE().send(viewId, "onFailed", new HashMap() {{
                    put("message", e.getMessage());
                }});
                e.printStackTrace();
            }


        }, getExecutor());
    }


    private Executor getExecutor() {
        return ContextCompat.getMainExecutor(ContextUtil.get());
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
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }

        DeviceCameraUtils.getInstance().dispose(cameraSelector);
     /*   if (cameraProviderFuture != null) {
            cameraProviderFuture.cancel(true);
        }*/
    }

    public void analyze(@NonNull ImageProxy image) {
        try {
            //  Log.i("DeviceCamera", "Process onVideoFrameReceived");
            Map<String, Object> imageData = imageAnalysisUtil.imageProxyToFlutterResult(image);
            FlutterEventChannel.getINSTANCE().send(viewId, "onVideoFrameReceived", imageData);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            image.close();
        }
    }
}
