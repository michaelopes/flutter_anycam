package br.dev.michaellopes.flutter_anycam.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.camera.camera2.internal.Camera2CameraInfoImpl;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceRequest;
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
import java.util.concurrent.atomic.AtomicInteger;

import br.dev.michaellopes.flutter_anycam.stream.CameraStreamManager;
import br.dev.michaellopes.flutter_anycam.tensorflow.TfFrameHandler;
import br.dev.michaellopes.flutter_anycam.utils.ContextUtil;
import br.dev.michaellopes.flutter_anycam.utils.DeviceCameraUtils;
import br.dev.michaellopes.flutter_anycam.utils.FrameRateLimiterUtil;
import io.flutter.view.TextureRegistry;


public class DeviceCamera extends BaseCamera {

    private static final String TAG = "DeviceCamera";
    /** Max wait for CameraX to hand the preview surface back before Camera2 takes the texture. */
    private static final long SURFACE_HANDOFF_TIMEOUT_MS = 1000;

    private volatile boolean processing = false;
    private volatile boolean disposed = false;

    private final BlockingQueue<ImageProxy> frameQueue = new LinkedBlockingQueue<>(2);

    private final ExecutorService queueExecutor = Executors.newSingleThreadExecutor();

    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ImageAnalysis imageAnalysis;

    /** CameraX preview surfaces handed out and not yet returned through the result callback. */
    private final AtomicInteger surfacesInUse = new AtomicInteger(0);

    private Camera2CameraSession camera2Session;

    private boolean camera2Pending = false;

    private boolean resolutionStrategy = true;

    private int connectedWidth = -1;
    private int connectedHeight = -1;

    private volatile Integer sensorOrientation;

    /** FPS gate for Camera2 Image callbacks (buffer dies when callback returns). */
    private final FrameRateLimiterUtil<Object> camera2DeliveryLimiter =
            new FrameRateLimiterUtil<Object>(getFps()) {
                @Override
                protected void onFrameLimited(Object ignored) {
                }
            };

    private final FrameRateLimiterUtil<ImageProxy> limiter = new FrameRateLimiterUtil<ImageProxy>(getFps()) {
        @Override
        protected void onFrameLimited(ImageProxy image) {
            if (disposed || !frameQueue.offer(image)) {
                image.close();
                return;
            }
            // dispose() may have drained the queue between the check and the offer.
            if (disposed) {
                drainIfDisposed();
            } else if (!processing) {
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
            if (params.get("previewEnabled") instanceof Boolean) {
                previewEnabled = (Boolean) params.get("previewEnabled");
            }

            Preview preview = null;
            if (previewEnabled) {
                preview = new Preview.Builder().build();
                preview.setSurfaceProvider(createSurfaceProvider());
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
                    if (disposed) {
                        imageProxy.close();
                        return;
                    }
                    CameraStreamManager.getInstance().sendFrame(
                            getCameraId(), imageProxy, getCustomRotationDegrees()
                    );
                    if (canDeliverFrame()) {
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
                                requestCamera2Direct(finalPreviewEnabled);
                            }

                            @Override
                            public void onBindFailed(String message) {
                                if (!disposed) onFailed(message);
                            }
                        }
                );

            } catch (IllegalArgumentException e) {
                if (resolutionStrategy) {
                    resolutionStrategy = false;
                    init();
                } else {
                    Log.e(TAG, "init failed", e);
                    onFailed(e.getMessage());
                }
            } catch (Exception e) {
                Log.e(TAG, "init failed", e);
                onFailed(e.getMessage());
            }
        }
    }

    @SuppressLint("RestrictedApi")
    private void completeCameraXBind(
            Camera2CameraInfoImpl cameraInfo,
            Preview preview,
            boolean previewEnabled
    ) {
        if (disposed || camera2Session != null || camera2Pending) return;

        int orientation = cameraInfo.getSensorRotationDegrees();

        // Preview surface may not be attached yet when onCameraXBound fires.
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
        } else {
            width = preferredSize.getWidth();
            height = preferredSize.getHeight();
        }

        if (orientation == 90 || orientation == 270) {
            int temp = width;
            width = height;
            height = temp;
        }
        emitConnected(width, height);
    }

    /** Rebinds re-fire the bind callback; only a new resolution is worth a Dart rebuild. */
    private void emitConnected(int width, int height) {
        if (disposed || (width == connectedWidth && height == connectedHeight)) return;
        connectedWidth = width;
        connectedHeight = height;
        final Map<String, Object> result = new HashMap<>();
        result.put("width", width);
        result.put("height", height);
        onConnected(result);
    }

    /**
     * Camera2 must not attach to the texture while CameraX still owns a producer connection to
     * it, or session configuration fails and the preview stays black.
     */
    private void requestCamera2Direct(boolean previewEnabled) {
        if (disposed || camera2Session != null || camera2Pending) return;
        camera2Pending = true;
        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer();
        }
        if (surfacesInUse.get() == 0) {
            startCamera2Direct(previewEnabled);
            return;
        }
        final Runnable start = () -> startCamera2Direct(previewEnabled);
        pendingCamera2Start = start;
        mainHandler.postDelayed(start, SURFACE_HANDOFF_TIMEOUT_MS);
    }

    private Runnable pendingCamera2Start;

    private void onSurfaceReleased() {
        if (surfacesInUse.decrementAndGet() > 0) return;
        mainHandler.post(() -> {
            Runnable start = pendingCamera2Start;
            if (start != null) {
                mainHandler.removeCallbacks(start);
                start.run();
            }
        });
    }

    private void startCamera2Direct(boolean previewEnabled) {
        pendingCamera2Start = null;
        if (disposed || camera2Session != null) return;

        Log.i(TAG, "Starting Camera2 direct session for " + getCameraId());
        connectedWidth = -1;
        connectedHeight = -1;

        camera2Session = new Camera2CameraSession(
                getCameraId(),
                preferredSize,
                previewEnabled,
                texture.surfaceTexture(),
                this::analyzeCamera2Image,
                new Camera2CameraSession.Listener() {
                    @Override
                    public void onOpened(Size resolution) {
                        int width = resolution.getWidth();
                        int height = resolution.getHeight();
                        int orientation = getSensorOrientation();
                        if (orientation == 90 || orientation == 270) {
                            int temp = width;
                            width = height;
                            height = temp;
                        }
                        final int w = width;
                        final int h = height;
                        mainHandler.post(() -> emitConnected(w, h));
                    }

                    @Override
                    public void onFailed(String message) {
                        mainHandler.post(() -> {
                            if (!disposed) DeviceCamera.this.onFailed(message);
                        });
                    }
                }
        );
        camera2Session.start();
    }

    private int getSensorOrientation() {
        Integer cached = sensorOrientation;
        if (cached != null) return cached;
        int value = cameraSelector.getSensorOrientation();
        try {
            Context context = ContextUtil.get();
            CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            Integer orientation = cameraManager.getCameraCharacteristics(getCameraId())
                    .get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (orientation != null) value = orientation;
        } catch (Exception ignored) {
        }
        sensorOrientation = value;
        return value;
    }

    public List<Size> getSupportedResolutions() {
        try {
            Context context = ContextUtil.get();
            CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraSelector.getId());
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                Size[] outputSizes = map.getOutputSizes(ImageFormat.YUV_420_888);
                if (outputSizes != null) {
                    return Arrays.asList(outputSizes);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "getSupportedResolutions failed: " + e.getMessage());
        }
        return new ArrayList<>();
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
            if (disposed) {
                request.willNotProvideSurface();
                return;
            }
            Size resolution = request.getResolution();
            texture.surfaceTexture().setDefaultBufferSize(resolution.getWidth(), resolution.getHeight());
            // Each surface lives until CameraX returns it: releasing it earlier (e.g. on the
            // next request) kills a stream that is still rendering and blacks out the preview.
            final Surface surface = new Surface(texture.surfaceTexture());
            surfacesInUse.incrementAndGet();
            request.provideSurface(surface, Runnable::run, (result) -> {
                if (result.getResultCode() == SurfaceRequest.Result.RESULT_INVALID_SURFACE) {
                    Log.e(TAG, "Invalid surface for camera " + getCameraId());
                }
                surface.release();
                onSurfaceReleased();
            });
        };
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;

        if (pendingCamera2Start != null) {
            mainHandler.removeCallbacks(pendingCamera2Start);
            pendingCamera2Start = null;
        }

        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer();
        }

        ImageProxy pending;
        while ((pending = frameQueue.poll()) != null) {
            pending.close();
        }

        // Workers check `disposed` and exit on their own; never block the main thread on them.
        cameraExecutor.shutdown();
        queueExecutor.shutdown();

        DeviceCameraUtils.getInstance().removeBind(getCameraId());
        imageAnalysis = null;

        final Camera2CameraSession session = camera2Session;
        camera2Session = null;
        if (session != null) {
            clearBridges();
            session.dispose(super::dispose);
        } else {
            super.dispose();
        }
    }

    private Integer getCustomRotationDegrees() {
        if (cameraSelector.isForceSensorOrientation()) {
            return cameraSelector.getSensorOrientation();
        }
        return null;
    }

    private void startProcessingWorker() {
        if (disposed) {
            drainIfDisposed();
            return;
        }
        processing = true;
        try {
            queueExecutor.execute(() -> {
                while (!Thread.currentThread().isInterrupted() && !disposed) {
                    try {
                        ImageProxy task = frameQueue.poll(100, TimeUnit.MILLISECONDS);
                        if (task == null) break;
                        analyze(task);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        Log.e(TAG, "frame worker failed", e);
                    }
                }
                processing = false;
                drainIfDisposed();
                if (!frameQueue.isEmpty() && !disposed) {
                    startProcessingWorker();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            processing = false;
            drainIfDisposed();
        }
    }

    private void drainIfDisposed() {
        if (!disposed) return;
        ImageProxy pending;
        while ((pending = frameQueue.poll()) != null) {
            pending.close();
        }
    }

    public void analyze(@NonNull ImageProxy image) {
        try {
            // Frames queued before the previous delivery started must not overwrite its buffers.
            if (disposed || !canDeliverFrame()) {
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
            deliver(frameMap);
        } catch (Exception e) {
            Log.e(TAG, "analyze failed", e);
        } finally {
            image.close();
        }
    }

    private void analyzeCamera2Image(android.media.Image image) {
        if (disposed) return;
        try {
            // Feed raw/WebRTC sinks while Image is still valid (closed by Camera2CameraSession after callback).
            CameraStreamManager.getInstance().sendFrame(
                    getCameraId(),
                    image,
                    getSensorOrientation(),
                    getCustomRotationDegrees()
            );

            if (!canDeliverFrame() || !camera2DeliveryLimiter.shouldProcessFrame()) {
                return;
            }

            Map<String, Object> frameMap;
            if (isStandard()) {
                frameMap = imageAnalysisUtil.imageToNV21Map(
                        image,
                        getSensorOrientation(),
                        resizeFrame,
                        filter,
                        getCustomRotationDegrees()
                );
            } else {
                Integer custom = getCustomRotationDegrees();
                frameMap = TfFrameHandler.getInstance().addFrame(
                        image, resizeFrame, filter, custom != null ? custom : getSensorOrientation()
                );
            }
            deliver(frameMap);
        } catch (Exception e) {
            Log.e(TAG, "analyzeCamera2Image failed", e);
        }
    }

    /** A TF frame created after dispose has no Dart owner and must be closed here. */
    private void deliver(Map<String, Object> frameMap) {
        if (frameMap == null) return;
        if (disposed) {
            Object id = frameMap.get("id");
            if (!isStandard() && id instanceof String) {
                TfFrameHandler.getInstance().closeFrame((String) id);
            }
            return;
        }
        onVideoFrameReceived(frameMap);
    }

    @Override
    public void setZoom(float zoom) {
        DeviceCameraUtils.getInstance().setZoom(zoom, getCameraId());
    }

    @Override
    public void setExposureCompensation(int value) {
        DeviceCameraUtils.getInstance().setExposureCompensation(value, getCameraId());
    }
}
