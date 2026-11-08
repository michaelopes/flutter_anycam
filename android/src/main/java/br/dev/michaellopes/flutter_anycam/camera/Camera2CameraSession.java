package br.dev.michaellopes.flutter_anycam.camera;

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
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
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import br.dev.michaellopes.flutter_anycam.utils.ContextUtil;

/**
 * Opens a single camera via Camera2 API directly, bypassing CameraX lifecycle.
 * Used when multiple cameras must run simultaneously but CameraX concurrent mode is not
 * available for the device pair.
 *
 * <p>Every camera object (device, session, reader, surface) is created and closed on the
 * session's own background thread, so {@link #dispose} never races the camera callbacks and
 * never blocks the caller.
 */
public class Camera2CameraSession {

    public interface Listener {
        void onOpened(Size resolution);

        void onFailed(String message);
    }

    public interface FrameCallback {
        void onFrame(Image image);
    }

    private static final String TAG = "Camera2CameraSession";
    private static final long CLOSE_TIMEOUT_MS = 1500;

    private final String cameraId;
    private final Size preferredSize;
    private final boolean previewEnabled;
    private final SurfaceTexture previewTexture;
    private final FrameCallback frameCallback;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final HandlerThread backgroundThread;
    private final Handler backgroundHandler;

    // Background-thread confined.
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private Surface previewSurface;
    private Size selectedSize;

    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private final AtomicBoolean failed = new AtomicBoolean(false);

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
        backgroundThread = new HandlerThread("Camera2-" + cameraId);
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    public void start() {
        backgroundHandler.post(this::openCamera);
    }

    /**
     * Closes the camera asynchronously. {@code onClosed} runs on the main thread once the
     * device no longer produces into the preview texture (or after a timeout), so the caller
     * can safely release the texture there.
     */
    public void dispose(Runnable onClosed) {
        if (!disposed.compareAndSet(false, true)) {
            if (onClosed != null) mainHandler.post(onClosed);
            return;
        }
        final AtomicBoolean done = new AtomicBoolean(false);
        final Runnable finish = () -> {
            if (done.compareAndSet(false, true) && onClosed != null) {
                onClosed.run();
            }
        };
        mainHandler.postDelayed(finish, CLOSE_TIMEOUT_MS);
        boolean posted = backgroundHandler.post(() -> {
            closeAll();
            mainHandler.post(finish);
            backgroundThread.quitSafely();
        });
        if (!posted) {
            mainHandler.post(finish);
        }
    }

    private void closeAll() {
        if (captureSession != null) {
            try {
                captureSession.stopRepeating();
            } catch (Exception ignored) {
            }
            try {
                captureSession.close();
            } catch (Exception ignored) {
            }
            captureSession = null;
        }
        if (cameraDevice != null) {
            try {
                cameraDevice.close();
            } catch (Exception ignored) {
            }
            cameraDevice = null;
        }
        if (imageReader != null) {
            try {
                imageReader.close();
            } catch (Exception ignored) {
            }
            imageReader = null;
        }
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
    }

    private void openCamera() {
        if (disposed.get()) return;
        try {
            CameraManager manager = ContextUtil.get().getSystemService(CameraManager.class);
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
                    if (cameraDevice == camera) cameraDevice = null;
                    notifyFailed("Camera disconnected");
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    if (cameraDevice == camera) cameraDevice = null;
                    notifyFailed("Camera error code=" + error);
                }
            }, backgroundHandler);
        } catch (Exception e) {
            notifyFailed("openCamera failed: " + e.getMessage());
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
            imageReader.setOnImageAvailableListener(this::onImageAvailable, backgroundHandler);

            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(imageReader.getSurface());

            if (previewEnabled && previewTexture != null) {
                previewTexture.setDefaultBufferSize(selectedSize.getWidth(), selectedSize.getHeight());
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
                            if (startRepeatingRequest()) {
                                listener.onOpened(selectedSize);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            notifyFailed("Capture session configure failed");
                        }
                    },
                    backgroundHandler
            );
        } catch (Exception e) {
            // IllegalStateException when the device was closed underneath us,
            // IllegalArgumentException for an abandoned preview surface.
            notifyFailed("createSession failed: " + e.getMessage());
        }
    }

    private void onImageAvailable(ImageReader reader) {
        Image image;
        try {
            image = reader.acquireLatestImage();
        } catch (Exception e) {
            return;
        }
        if (image == null) return;
        try {
            if (!disposed.get()) {
                frameCallback.onFrame(image);
            }
        } catch (Exception e) {
            Log.e(TAG, "frame callback failed", e);
        } finally {
            image.close();
        }
    }

    private boolean startRepeatingRequest() {
        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(imageReader.getSurface());
            if (previewSurface != null) {
                builder.addTarget(previewSurface);
            }
            captureSession.setRepeatingRequest(builder.build(), null, backgroundHandler);
            return true;
        } catch (Exception e) {
            notifyFailed("setRepeatingRequest failed: " + e.getMessage());
            return false;
        }
    }

    private List<Size> getSupportedSizes() {
        try {
            CameraManager manager = ContextUtil.get().getSystemService(CameraManager.class);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return Collections.emptyList();
            Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
            if (sizes == null) return Collections.emptyList();
            return Arrays.asList(sizes);
        } catch (Exception e) {
            Log.e(TAG, "getSupportedSizes failed", e);
            return Collections.emptyList();
        }
    }

    static Size chooseSize(List<Size> sizes, Size preferredSize) {
        if (sizes.isEmpty()) return null;
        Size best = sizes.get(0);
        long targetArea = (long) preferredSize.getWidth() * preferredSize.getHeight();
        long bestDiff = Long.MAX_VALUE;
        for (Size size : sizes) {
            long diff = Math.abs((long) size.getWidth() * size.getHeight() - targetArea);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = size;
            }
        }
        return best;
    }

    private Size chooseSize(List<Size> sizes) {
        return chooseSize(sizes, preferredSize);
    }

    /** Reports at most one failure per session, and none after dispose. */
    private void notifyFailed(String message) {
        Log.e(TAG, "cameraId=" + cameraId + " " + message);
        if (disposed.get() || !failed.compareAndSet(false, true)) return;
        listener.onFailed(message);
    }
}
