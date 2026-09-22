package br.dev.michaellopes.flutter_anycam.stream;

import android.media.Image;
import android.util.Log;
import android.util.Size;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import br.dev.michaellopes.flutter_anycam.camera.Camera2CameraSession;
import br.dev.michaellopes.flutter_anycam.integration.FlutterEventChannel;
import br.dev.michaellopes.flutter_anycam.tensorflow.TfFrameHandler;
import br.dev.michaellopes.flutter_anycam.utils.CameraRotationUtil;
import br.dev.michaellopes.flutter_anycam.utils.FrameRateLimiterUtil;
import br.dev.michaellopes.flutter_anycam.utils.NativeUtil;

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
    private final FrameRateLimiterUtil<Map<String, Object>> limiter;

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
        this.limiter = new FrameRateLimiterUtil<Map<String, Object>>(fps) {
            @Override
            protected void onFrameLimited(Map<String, Object> data) {
                FlutterEventChannel.getInstance().send(
                        EVENT_VIEW_ID,
                        "onTfCameraStreamFrame",
                        data
                );
            }

            @Override
            protected void onFrameSkipped(Map<String, Object> data) {
                Object frameId = data.get("id");
                if (frameId instanceof String) {
                    TfFrameHandler.getInstance().closeFrame((String) frameId);
                }
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

    private void processFrame(Image image) {
        final int width;
        final int height;
        final byte[] nv21;
        try {
            width = image.getWidth();
            height = image.getHeight();
            nv21 = new byte[width * height * 3 / 2];
            NativeUtil.yuv420ToNv21(image, nv21);
        } catch (Exception e) {
            Log.e(TAG, "processFrame copy failed cameraId=" + cameraId, e);
            return;
        }

        executor.execute(() -> {
            try {
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
                if (tfMap != null) {
                    tfMap.put("cameraId", cameraId);
                    limiter.onNewFrame(tfMap);
                }
            } catch (Exception e) {
                Log.e(TAG, "processFrame failed cameraId=" + cameraId, e);
            }
        });
    }

    public void dispose() {
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
