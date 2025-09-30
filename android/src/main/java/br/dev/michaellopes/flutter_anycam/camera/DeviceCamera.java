package br.dev.michaellopes.flutter_anycam.camera;

import android.annotation.SuppressLint;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.renderscript.Matrix4f;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;


import androidx.camera.camera2.internal.Camera2CameraInfoImpl;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceRequest;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import br.dev.michaellopes.flutter_anycam.utils.ContextUtil;
import br.dev.michaellopes.flutter_anycam.utils.DeviceCameraUtils;
import br.dev.michaellopes.flutter_anycam.utils.FrameRateLimiterUtil;
import io.flutter.view.TextureRegistry;


public class DeviceCamera extends BaseCamera {

    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();

    private ImageAnalysis imageAnalysis;

    private boolean resolutionStrategy = true;

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

    public DeviceCamera(TextureRegistry.SurfaceTextureEntry texture, Map<String, Object> params) {
        super(texture, params);
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

                if (!supportedSizes.isEmpty() && resolutionStrategy) {
                    Size pSize = getClosestSize(supportedSizes);
                    ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                    new ResolutionStrategy(
                                            pSize,
                                            ResolutionStrategy.FALLBACK_RULE_NONE
                                    )
                            )
                            .build();
                    aBuilder.setResolutionSelector(resolutionSelector);
                }

                imageAnalysis = aBuilder
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, limiter::onNewFrame);

                Camera2CameraInfoImpl cameraInfo = DeviceCameraUtils.getInstance().bind(cameraSelector.getId(), preview, imageAnalysis);
                Size ps = preview.getAttachedSurfaceResolution();

                int sensorOrientation = cameraInfo.getSensorRotationDegrees();

                int width = ps.getWidth();
                int height = ps.getHeight();

                if (sensorOrientation == 90 || sensorOrientation == 270) {
                    int temp = width;
                    width = height;
                    height = temp;
                }

                final Map<String, Object> result = new HashMap<>();
                result.put("width", width);
                result.put("height", height);

                onConnected(result);

            } catch (IllegalArgumentException e) {
                if (resolutionStrategy) {
                    resolutionStrategy = false;
                    init();
                } else {
                    onFailed(e.getMessage());
                    e.printStackTrace();
                }
            } catch (Exception e) {
                onFailed(e.getMessage());
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
            Size resolution = request.getResolution();
            texture.surfaceTexture().setDefaultBufferSize(resolution.getWidth(), resolution.getHeight());
            Surface flutterSurface = getSurface();
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
        super.dispose();
    }

    private Integer getCustomRotationDegrees() {
        if (cameraSelector.isForceSensorOrientation()) {
            return cameraSelector.getSensorOrientation();
        }
        return null;
    }

    public void analyze(@NonNull ImageProxy image) {
        try {
            Map<String, Object> imageData = imageAnalysisUtil.imageProxyToFlutterResult(image, getCustomRotationDegrees());
            onVideoFrameReceived(imageData);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            image.close();
        }
    }
}
