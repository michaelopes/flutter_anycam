package br.dev.michaellopes.flutter_anycam.stream;

import android.media.Image;
import android.util.Log;
import android.util.Size;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import br.dev.michaellopes.flutter_anycam.camera.Camera2CameraSession;
import br.dev.michaellopes.flutter_anycam.integration.FlutterEventChannel;
import br.dev.michaellopes.flutter_anycam.tensorflow.TfFrameHandler;
import br.dev.michaellopes.flutter_anycam.utils.CameraRotationUtil;
import br.dev.michaellopes.flutter_anycam.utils.FrameDeliveryGate;
import br.dev.michaellopes.flutter_anycam.utils.FrameRateLimiterUtil;
import br.dev.michaellopes.flutter_anycam.utils.NativeUtil;

/**
 * Headless Camera2 → TF frame stream. The FPS and in-flight decisions are made on the
 * capture thread before copying, so frames beyond {@code fps} cost nothing, at most one frame
 * is converting and at most one TF frame waits for Dart. A TF frame that reaches no listener
 * is closed here, since Dart would never close it.
 */
public class HeadlessTfCameraSession {

    public static final int EVENT_VIEW_ID = -3;

    private static final String TAG = "HeadlessTfCameraSession";

    private final String cameraId;
    private final int fps;
    private final int filter;
    private final int selectorSensorOrientation;
    private final boolean forceSensorOrientation;
    private final Size preferredSize;

    private Camera2CameraSession camera2Session;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final FrameRateLimiterUtil<Object> limiter;
    private final FrameDeliveryGate delivery = new FrameDeliveryGate();
    private final AtomicBoolean converting = new AtomicBoolean(false);
    private volatile boolean disposed = false;

    /** Reused: only touched while {@link #converting} is held. */
    private byte[] nv21Scratch;

    public HeadlessTfCameraSession(
            String cameraId,
            int fps,
            Size preferredSize,
            int filter,
            int selectorSensorOrientation,
            boolean forceSensorOrientation
    ) {
        this.cameraId = cameraId;
        this.fps = fps;
        this.preferredSize = preferredSize;
        this.filter = filter;
        this.selectorSensorOrientation = selectorSensorOrientation;
        this.forceSensorOrientation = forceSensorOrientation;
        this.limiter = new FrameRateLimiterUtil<Object>(Math.max(1, fps)) {
            @Override
            protected void onFrameLimited(Object ignored) {
            }
        };
    }

    public void start() {
        camera2Session = new Camera2CameraSession(
                cameraId,
                preferredSize,
                false,
                null,
                this::processFrame,
                new Camera2CameraSession.Listener() {
                    @Override
                    public void onOpened(Size resolution) {
                        Map<String, Object> data = new HashMap<>();
                        data.put("cameraId", cameraId);
                        data.put("width", resolution.getWidth());
                        data.put("height", resolution.getHeight());
                        FlutterEventChannel.getInstance().send(
                                EVENT_VIEW_ID,
                                "onTfCameraStreamConnected",
                                data
                        );
                        Log.i(TAG, "Opened cameraId=" + cameraId
                                + " " + resolution.getWidth() + "x" + resolution.getHeight());
                    }

                    @Override
                    public void onFailed(String message) {
                        Map<String, Object> data = new HashMap<>();
                        data.put("cameraId", cameraId);
                        data.put("message", message);
                        FlutterEventChannel.getInstance().send(
                                EVENT_VIEW_ID,
                                "onTfCameraStreamFailed",
                                data
                        );
                        Log.e(TAG, "Failed cameraId=" + cameraId + " " + message);
                    }
                }
        );
        camera2Session.start();
    }

    /** Camera2 capture thread; {@code image} is closed when this returns. */
    private void processFrame(Image image) {
        if (disposed || converting.get() || !delivery.isIdle()) return;
        if (!limiter.shouldProcessFrame()) return;
        if (!converting.compareAndSet(false, true)) return;

        final int width;
        final int height;
        final byte[] nv21;
        try {
            width = image.getWidth();
            height = image.getHeight();
            int size = width * height * 3 / 2;
            if (nv21Scratch == null || nv21Scratch.length != size) {
                nv21Scratch = new byte[size];
            }
            nv21 = nv21Scratch;
            NativeUtil.yuv420ToNv21(image, nv21);
        } catch (Exception e) {
            converting.set(false);
            Log.e(TAG, "processFrame copy failed cameraId=" + cameraId, e);
            return;
        }

        try {
            executor.execute(() -> {
                try {
                    convertAndSend(nv21, width, height);
                } finally {
                    converting.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            converting.set(false);
        }
    }

    private void convertAndSend(byte[] nv21, int width, int height) {
        try {
            if (disposed) return;
            final int rotation = CameraRotationUtil.resolveFrameRotation(
                    cameraId,
                    forceSensorOrientation,
                    selectorSensorOrientation
            );
            Map<String, Object> tfMap = TfFrameHandler.getInstance().addFrame(
                    nv21,
                    width,
                    height,
                    filter,
                    rotation
            );
            if (tfMap == null) return;

            Object id = tfMap.get("id");
            Runnable closeFrame = id instanceof String
                    ? () -> TfFrameHandler.getInstance().closeFrame((String) id)
                    : null;
            if (disposed) {
                if (closeFrame != null) closeFrame.run();
                return;
            }

            tfMap.put("cameraId", cameraId);
            FrameDeliveryGate.Delivery d = delivery.tryBegin(1, closeFrame);
            if (d == null) {
                if (closeFrame != null) closeFrame.run();
                return;
            }
            FlutterEventChannel.getInstance().send(
                    EVENT_VIEW_ID,
                    "onTfCameraStreamFrame",
                    tfMap,
                    d::complete
            );
        } catch (Exception e) {
            Log.e(TAG, "processFrame failed cameraId=" + cameraId, e);
        }
    }

    public void dispose() {
        disposed = true;
        if (camera2Session != null) {
            camera2Session.dispose();
            camera2Session = null;
        }
        executor.shutdown();
    }

    public String getCameraId() {
        return cameraId;
    }
}
