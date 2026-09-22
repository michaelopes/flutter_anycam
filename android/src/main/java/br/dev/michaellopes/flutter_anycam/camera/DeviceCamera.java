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

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import br.dev.michaellopes.flutter_anycam.stream.CameraStreamManager;
import br.dev.michaellopes.flutter_anycam.tensorflow.TfFrameHandler;
import br.dev.michaellopes.flutter_anycam.utils.ContextUtil;
import br.dev.michaellopes.flutter_anycam.utils.DeviceCameraUtils;
import br.dev.michaellopes.flutter_anycam.utils.FrameRateLimiterUtil;
import io.flutter.view.TextureRegistry;


public class DeviceCamera extends BaseCamera {

    private volatile boolean processing = false;
    private volatile boolean disposed = false;

    private final BlockingQueue<ImageProxy> frameQueue = new LinkedBlockingQueue<>(2);

    private final ExecutorService queueExecutor = Executors.newFixedThreadPool(1);

    private final ExecutorService cameraExecutor = Executors.newFixedThreadPool(3);

    /** Reused for CameraX SurfaceRequest callbacks — avoids per-frame thread allocation. */
    private final ExecutorService surfaceExecutor = Executors.newSingleThreadExecutor();

    private ImageAnalysis imageAnalysis;

    private Surface activeSurface;

    private Camera2CameraSession camera2Session;

    private boolean usingCamera2 = false;

    private boolean resolutionStrategy = true;

    /** FPS gate for Camera2 Image callbacks (buffer dies when callback returns). */
    private final FrameRateLimiterUtil<Object> camera2DeliveryLimiter =
            new FrameRateLimiterUtil<Object>(getFps()) {
                @Override
                protected void onFrameLimited(Object ignored) {
                }
            };

    FrameRateLimiterUtil<ImageProxy> limiter = new FrameRateLimiterUtil<ImageProxy>(getFps()) {
        @Override
        protected void onFrameLimited(ImageProxy image) {
            if (disposed) {
                image.close();
                return;
            }
            boolean added = frameQueue.offer(image);
            if (added && !processing) {
                startProcessingWorker();
            }
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
            boolean previewEnabled = true;
            if (params.get("previewEnabled") != null) {
                previewEnabled = (Boolean) params.get("previewEnabled");
            }

            Preview preview = null;
            if (previewEnabled) {
                Preview.SurfaceProvider surfaceProvider = createSurfaceProvider();
                preview = new Preview.Builder().build();
                preview.setSurfaceProvider(surfaceProvider);
            }

            try {
                ImageAnalysis.Builder aBuilder = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888);

                List<Size> supportedSizes = getSupportedResolutions();
                boolean multiCamera = DeviceCameraUtils.getInstance().getActiveBindCount() > 0;

                if (!supportedSizes.isEmpty() && resolutionStrategy) {
                    Size pSize = multiCamera
                            ? getSmallestSize(supportedSizes)
                            : getClosestSize(supportedSizes);
                    ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                    new ResolutionStrategy(
                                            pSize,
                                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER
                                    )
                            )
                            .build();
                    aBuilder.setResolutionSelector(resolutionSelector);
                }

                imageAnalysis = aBuilder.build();

                imageAnalysis.setAnalyzer(cameraExecutor, (imageProxy) -> {
                    CameraStreamManager.getInstance().sendFrame(
                            getCameraId(), imageProxy, getCustomRotationDegrees()
                    );
                    if (frameDeliveryEnabled) {
                        limiter.onNewFrame(imageProxy);
                    } else {
                        imageProxy.close();
                    }
                });

                final Preview finalPreview = preview;
                final boolean finalPreviewEnabled = previewEnabled;

                DeviceCameraUtils.getInstance().bind(
                        cameraSelector.getId(),
                        preview,
                        imageAnalysis,
                        new DeviceCameraUtils.BindCallback() {
                            @Override
                            public void onCameraXBound(Camera2CameraInfoImpl cameraInfo) {
                                completeCameraXBind(cameraInfo, finalPreview, finalPreviewEnabled);
                            }

                            @Override
                            public void onUseCamera2Direct() {
                                initCamera2Direct(finalPreviewEnabled);
                            }

                            @Override
                            public void onBindFailed(String message) {
                                onFailed(message);
                            }
                        }
                );

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

    @SuppressLint("RestrictedApi")
    private void completeCameraXBind(
            Camera2CameraInfoImpl cameraInfo,
            Preview preview,
            boolean previewEnabled
    ) {
        if (disposed) return;

        int sensorOrientation = cameraInfo.getSensorRotationDegrees();

        // Preview surface may not be attached yet when onCameraXBound fires (esp. concurrent /
        // analysis-only fallback). Never NPE on getAttachedSurfaceResolution().
        Size previewSize = null;
        if (preview != null && previewEnabled) {
            previewSize = preview.getAttachedSurfaceResolution();
        }

        Size analysisSize = null;
        if (imageAnalysis != null) {
            try {
                androidx.camera.core.ResolutionInfo info = imageAnalysis.getResolutionInfo();
                if (info != null) {
                    analysisSize = info.getResolution();
                }
            } catch (Exception ignored) {
            }
        }

        int width;
        int height;
        if (previewSize != null) {
            width = previewSize.getWidth();
            height = previewSize.getHeight();
        } else if (analysisSize != null) {
            width = analysisSize.getWidth();
            height = analysisSize.getHeight();
            Log.w("DeviceCamera", "Preview resolution null for " + getCameraId()
                    + "; using analysis " + width + "x" + height);
        } else {
            width = preferredSize.getWidth();
            height = preferredSize.getHeight();
            Log.w("DeviceCamera", "No attached resolution for " + getCameraId()
                    + "; using preferredSize " + width + "x" + height);
        }

        if (sensorOrientation == 90 || sensorOrientation == 270) {
            int temp = width;
            width = height;
            height = temp;
        }

        final Map<String, Object> result = new HashMap<>();
        result.put("width", width);
        result.put("height", height);
        onConnected(result);
    }

    private void initCamera2Direct(boolean previewEnabled) {
        if (disposed) return;
        if (camera2Session != null) return;

        usingCamera2 = true;
        Log.i("DeviceCamera", "Starting Camera2 direct session for " + getCameraId());

        camera2Session = new Camera2CameraSession(
                getCameraId(),
                preferredSize,
                previewEnabled,
                texture.surfaceTexture(),
                image -> {
                    if (disposed) return;
                    analyzeCamera2Image(image);
                },
                new Camera2CameraSession.Listener() {
                    @Override
                    public void onOpened(Size resolution) {
                        if (disposed) return;
                        int width = resolution.getWidth();
                        int height = resolution.getHeight();
                        int orientation = getSensorOrientation();
                        if (orientation == 90 || orientation == 270) {
                            int temp = width;
                            width = height;
                            height = temp;
                        }
                        Map<String, Object> result = new HashMap<>();
                        result.put("width", width);
                        result.put("height", height);
                        onConnected(result);
                    }

                    @Override
                    public void onFailed(String message) {
                        DeviceCamera.this.onFailed(message);
                    }
                }
        );
        camera2Session.start();
    }

    private int getSensorOrientation() {
        try {
            Context context = ContextUtil.get();
            CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(getCameraId());
            Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            return orientation != null ? orientation : 0;
        } catch (CameraAccessException e) {
            return cameraSelector.getSensorOrientation();
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

    private Size getSmallestSize(List<Size> sizes) {
        if (sizes == null || sizes.isEmpty()) return null;
        Size smallest = sizes.get(0);
        for (Size s : sizes) {
            if (s.getWidth() * s.getHeight() < smallest.getWidth() * smallest.getHeight()) {
                smallest = s;
            }
        }
        return smallest;
    }

    private @NonNull Preview.SurfaceProvider createSurfaceProvider() {
        return request -> {
            Size resolution = request.getResolution();
            texture.surfaceTexture().setDefaultBufferSize(resolution.getWidth(), resolution.getHeight());
            if (activeSurface != null) {
                activeSurface.release();
                activeSurface = null;
            }
            activeSurface = new Surface(texture.surfaceTexture());
            request.provideSurface(
                    activeSurface,
                    surfaceExecutor,
                    (result) -> {
                        int resultCode = result.getResultCode();
                        if (resultCode == SurfaceRequest.Result.RESULT_INVALID_SURFACE) {
                            Log.e("DeviceCamera", "Invalid surface for camera " + getCameraId());
                        }
                    });
        };
    }


    @Override
    public void dispose() {
        disposed = true;

        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer();
            imageAnalysis = null;
        }

        ImageProxy pending;
        while ((pending = frameQueue.poll()) != null) {
            pending.close();
        }

        cameraExecutor.shutdown();
        queueExecutor.shutdown();
        surfaceExecutor.shutdown();

        try {
            if (!cameraExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                cameraExecutor.shutdownNow();
            }
            if (!queueExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                queueExecutor.shutdownNow();
            }
            if (!surfaceExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                surfaceExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cameraExecutor.shutdownNow();
            queueExecutor.shutdownNow();
            surfaceExecutor.shutdownNow();
        }

        if (activeSurface != null) {
            activeSurface.release();
            activeSurface = null;
        }

        if (camera2Session != null) {
            camera2Session.dispose();
            camera2Session = null;
            DeviceCameraUtils.getInstance().removeBind(getCameraId());
        } else {
            DeviceCameraUtils.getInstance().dispose(cameraSelector);
        }
        super.dispose();
    }

    private Integer getCustomRotationDegrees() {
        if (cameraSelector.isForceSensorOrientation()) {
            return cameraSelector.getSensorOrientation();
        }
        return null;
    }

    private void startProcessingWorker() {
        if (disposed) {
            return;
        }
        processing = true;
        queueExecutor.execute(() -> {
            while (!Thread.currentThread().isInterrupted() && !disposed) {
                try {
                    ImageProxy task = frameQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (task != null) {
                        analyze(task);
                    } else {
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            processing = false;
            if (!frameQueue.isEmpty() && !disposed) {
                startProcessingWorker();
            }
        });
    }

    public void analyze(@NonNull ImageProxy image) {
        try {
            if (disposed) {
                return;
            }
            Map<String, Object> frameMap;
            if (isStandard()) {
                frameMap = imageAnalysisUtil.imageProxyToNV21Map(
                        image, resizeFrame, filter, getCustomRotationDegrees()
                );
            } else {
                frameMap = TfFrameHandler.getInstance().addFrame(
                        image, resizeFrame, filter, getCustomRotationDegrees()
                );
            }

            if (frameMap != null && !disposed) {
                onVideoFrameReceived(frameMap);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            image.close();
        }
    }

    private void analyzeCamera2Image(android.media.Image image) {
        try {
            if (disposed) return;

            // Feed raw/WebRTC sinks while Image is still valid (closed by Camera2CameraSession after callback).
            CameraStreamManager.getInstance().sendFrame(
                    getCameraId(),
                    image,
                    getSensorOrientation(),
                    getCustomRotationDegrees()
            );

            if (!frameDeliveryEnabled) {
                return;
            }

            // Skip NV21 conversion when under FPS budget — biggest win on low-end multi-cam.
            if (!camera2DeliveryLimiter.shouldProcessFrame()) {
                return;
            }

            Map<String, Object> frameMap = imageAnalysisUtil.imageToNV21Map(
                    image,
                    getSensorOrientation(),
                    resizeFrame,
                    filter,
                    getCustomRotationDegrees()
            );
            if (frameMap != null && !disposed) {
                onVideoFrameReceived(frameMap);
            }
        } catch (Exception e) {
            Log.e("DeviceCamera", "analyzeCamera2Image failed", e);
        }
    }


    @Override
    public void setZoom(float zoom) {
        DeviceCameraUtils.getInstance().setZoom(zoom, getCameraId());
    }

    @Override
    public void setExposureCompensation(int value) {
        DeviceCameraUtils.getInstance().setExposureCompensation(value, getCameraId());
    }

    private static class LimiterFrame {
        public final ImageProxy imageProxy;
        public final byte[] nv21;

        private LimiterFrame(ImageProxy imageProxy, byte[] nv21) {
            this.imageProxy = imageProxy;
            this.nv21 = nv21;
        }
    }
}
