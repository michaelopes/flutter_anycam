package br.dev.michaellopes.flutter_anycam.camera;

import android.annotation.SuppressLint;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;


import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceRequest;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        synchronized (DeviceCameraUtils.getInstance()) {
            Preview.SurfaceProvider surfaceProvider = createSurfaceProvider();
            Preview preview = new Preview.Builder()
                    .build();

            preview.setSurfaceProvider(surfaceProvider);

            try {

                ImageAnalysis.Builder aBuilder = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888);

                List<Size> supportedSizes = getSupportedResolutions();

                if (!supportedSizes.isEmpty()) {
                    Size pSize =  getClosestSize(supportedSizes);
                    ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                    new ResolutionStrategy(
                                            pSize,
                                            ResolutionStrategy.FALLBACK_RULE_NONE
                                    )
                            )
                            .build();
                    aBuilder.setMaxResolution(pSize);
                    aBuilder.setResolutionSelector(resolutionSelector);
                }

                imageAnalysis = aBuilder
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

    public List<Size> getSupportedResolutions() {
        List<Size> supportedSizes = new ArrayList<>();
        try {
            Context context = ContextUtil.get();
            String cameraId = cameraSelector.getId();
            CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                Size[] outputSizes = map.getOutputSizes(ImageFormat.YUV_420_888);
                if (outputSizes != null) {
                    supportedSizes = Arrays.asList(outputSizes);
                    for (Size size : supportedSizes) {
                        Log.d("FlutterAnycamFrame", "Suportado: " + size.getWidth() + " x " + size.getHeight());
                    }
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        return supportedSizes;
    }

    private Size getClosestSize(List<Size> sizes) {
        if (sizes == null || sizes.isEmpty()) return null;

        Size closest = sizes.get(0);
        int targetWidth = preferredSize.getWidth();
        int targetHeight = preferredSize.getHeight();
        int minDiff = Math.abs(closest.getWidth() - targetWidth) + Math.abs(closest.getHeight() - targetHeight);

        for (Size s : sizes) {
            int diff = Math.abs(s.getWidth() - targetWidth) + Math.abs(s.getHeight() - targetHeight);
            if (diff < minDiff) {
                closest = s;
                minDiff = diff;
            }
        }
        return closest;
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
