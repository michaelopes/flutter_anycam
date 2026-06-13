package br.dev.michaellopes.flutter_anycam.camera;

import android.annotation.SuppressLint;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opens a single camera via Camera2 API directly, bypassing CameraX lifecycle.
 * Used when multiple external cameras must run simultaneously but CameraX
 * concurrent mode is not available for the device pair.
 */
public class Camera2CameraSession {

    public interface Listener {
        void onOpened(Size resolution);

        void onFailed(String message);
    }

    private static final String TAG = "Camera2CameraSession";

    private final String cameraId;
    private final Size preferredSize;
    private final boolean previewEnabled;
    private final SurfaceTexture previewTexture;
    private final FrameCallback frameCallback;
    private final Listener listener;

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private Surface previewSurface;
    private Size selectedSize;
    private final AtomicBoolean disposed = new AtomicBoolean(false);

    public interface FrameCallback {
        void onFrame(Image image);
    }

    public Camera2CameraSession(
            String cameraId,
            Size preferredSize,
            boolean previewEnabled,
            SurfaceTexture previewTexture,
            FrameCallback frameCallback,
            Listener listener
    ) {
        this.cameraId = cameraId;
        this.preferredSize = preferredSize;
        this.previewEnabled = previewEnabled;
        this.previewTexture = previewTexture;
        this.frameCallback = frameCallback;
        this.listener = listener;
    }

    public void start() {
        startBackgroundThread();
        openCamera();
    }

    public void dispose() {
        if (!disposed.compareAndSet(false, true)) {
            return;
        }
        closeSession();
        closeDevice();
        closeReader();
        releasePreviewSurface();
        stopBackgroundThread();
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("Camera2-" + cameraId);
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            backgroundThread = null;
            backgroundHandler = null;
        }
    }

    private void openCamera() {
        try {
            CameraManager manager = br.dev.michaellopes.flutter_anycam.utils.ContextUtil.get()
                    .getSystemService(CameraManager.class);
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    if (disposed.get()) {
                        camera.close();
                        return;
                    }
                    cameraDevice = camera;
                    createSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                    notifyFailed("Camera disconnected");
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    notifyFailed("Camera error code=" + error);
                }
            }, backgroundHandler);
        } catch (CameraAccessException | SecurityException e) {
            notifyFailed(e.getMessage());
        }
    }

    private void createSession() {
        try {
            selectedSize = chooseSize(getSupportedSizes());
            if (selectedSize == null) {
                notifyFailed("No supported output size");
                return;
            }

            imageReader = ImageReader.newInstance(
                    selectedSize.getWidth(),
                    selectedSize.getHeight(),
                    ImageFormat.YUV_420_888,
                    2
            );
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = reader.acquireLatestImage();
                if (image == null || disposed.get()) {
                    if (image != null) image.close();
                    return;
                }
                try {
                    frameCallback.onFrame(image);
                } finally {
                    image.close();
                }
            }, backgroundHandler);

            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(imageReader.getSurface());

            if (previewEnabled && previewTexture != null) {
                previewTexture.setDefaultBufferSize(
                        selectedSize.getWidth(),
                        selectedSize.getHeight()
                );
                previewSurface = new Surface(previewTexture);
                surfaces.add(previewSurface);
            }

            cameraDevice.createCaptureSession(
                    surfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (disposed.get() || cameraDevice == null) {
                                session.close();
                                return;
                            }
                            captureSession = session;
                            startRepeatingRequest();
                            listener.onOpened(selectedSize);
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            notifyFailed("Capture session configure failed");
                        }
                    },
                    backgroundHandler
            );
        } catch (CameraAccessException e) {
            notifyFailed(e.getMessage());
        }
    }

    private void startRepeatingRequest() {
        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW
            );
            builder.addTarget(imageReader.getSurface());
            if (previewSurface != null) {
                builder.addTarget(previewSurface);
            }
            captureSession.setRepeatingRequest(
                    builder.build(),
                    null,
                    backgroundHandler
            );
        } catch (CameraAccessException e) {
            notifyFailed(e.getMessage());
        }
    }

    private List<Size> getSupportedSizes() {
        try {
            CameraManager manager = br.dev.michaellopes.flutter_anycam.utils.ContextUtil.get()
                    .getSystemService(CameraManager.class);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            );
            if (map == null) return Collections.emptyList();
            Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
            if (sizes == null) return Collections.emptyList();
            return Arrays.asList(sizes);
        } catch (CameraAccessException e) {
            Log.e(TAG, "getSupportedSizes failed", e);
            return Collections.emptyList();
        }
    }

    private Size chooseSize(List<Size> sizes) {
        if (sizes.isEmpty()) return null;

        Size best = sizes.get(0);
        int targetArea = preferredSize.getWidth() * preferredSize.getHeight();
        int bestDiff = Integer.MAX_VALUE;

        for (Size size : sizes) {
            int area = size.getWidth() * size.getHeight();
            int diff = Math.abs(area - targetArea);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = size;
            }
        }
        return best;
    }

    private void closeSession() {
        if (captureSession != null) {
            try {
                captureSession.stopRepeating();
            } catch (Exception ignored) {}
            captureSession.close();
            captureSession = null;
        }
    }

    private void closeDevice() {
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

    private void closeReader() {
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    private void releasePreviewSurface() {
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
    }

    private void notifyFailed(String message) {
        Log.e(TAG, "cameraId=" + cameraId + " " + message);
        listener.onFailed(message);
    }
}
