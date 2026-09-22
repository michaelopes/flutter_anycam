package br.dev.michaellopes.flutter_anycam.stream;

import android.media.Image;

import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import br.dev.michaellopes.flutter_anycam.integration.FlutterEventChannel;
import br.dev.michaellopes.flutter_anycam.utils.FrameRateLimiterUtil;
import br.dev.michaellopes.flutter_anycam.utils.ImageMapperUtil;
import br.dev.michaellopes.flutter_anycam.webrtc.I420Image;
import br.dev.michaellopes.flutter_anycam.webrtc.WebRtcImageUtil;
import br.dev.michaellopes.flutter_anycam.webrtc.WebRtcStreamHandler;

public class CameraRawStream {
    private final String cameraId;
    private final FrameRateLimiterUtil<RawStreamImageRef> flutterLimiter;
    private final FrameRateLimiterUtil<RawStreamImageRef> webRtcLimiter;
    private final ExecutorService executor;

    protected final ImageMapperUtil imageAnalysisUtil = new ImageMapperUtil();

    private boolean deliverToFlutter = false;
    private boolean webRtcFeedEnabled = false;
    private int webRtcStreamWidth = 0;
    private int webRtcStreamHeight = 0;

    public CameraRawStream(String cameraId, int fps) {
        this.cameraId = cameraId;

        executor = Executors.newSingleThreadExecutor();
        this.flutterLimiter = new FrameRateLimiterUtil<RawStreamImageRef>(fps) {
            @Override
            protected void onFrameLimited(RawStreamImageRef ref) {
                Map<String, Object> data = imageAnalysisUtil.imageProxyToI420Map(
                        ref.image,
                        ref.customRotationDegrees
                );
                executor.execute(() -> FlutterEventChannel.getInstance().send(
                        -2,
                        "onCameraRawFrame",
                        data
                ));
            }
        };
        this.webRtcLimiter = new FrameRateLimiterUtil<RawStreamImageRef>(fps) {
            @Override
            protected void onFrameLimited(RawStreamImageRef ref) {
                WebRtcImageUtil.prepareWebRtcFrame(
                                ref.image,
                                ref.customRotationDegrees,
                                webRtcStreamWidth,
                                webRtcStreamHeight
                        )
                        .ifPresent(image -> WebRtcStreamHandler.getInstance()
                                .pushFrame(image, null));
            }
        };
    }

    public String getCameraId() {
        return cameraId;
    }

    public boolean isDeliverToFlutter() {
        return deliverToFlutter;
    }

    public void setDeliverToFlutter(boolean deliverToFlutter) {
        this.deliverToFlutter = deliverToFlutter;
    }

    public boolean isWebRtcFeedEnabled() {
        return webRtcFeedEnabled;
    }

    public void setWebRtcFeedEnabled(boolean enabled) {
        this.webRtcFeedEnabled = enabled;
    }

    public void clearWebRtcFeed() {
        this.webRtcFeedEnabled = false;
    }

    public void setWebRtcStreamSize(int width, int height) {
        this.webRtcStreamWidth = width;
        this.webRtcStreamHeight = height;
    }

    public void clearWebRtcStreamSize() {
        this.webRtcStreamWidth = 0;
        this.webRtcStreamHeight = 0;
    }

    public boolean hasActiveSink() {
        return deliverToFlutter || webRtcFeedEnabled;
    }

    /** Whether a sink would accept a frame now, without consuming its FPS slot. */
    public boolean isFrameDue() {
        return (webRtcFeedEnabled
                && WebRtcStreamHandler.getInstance().hasAnyVideoReadyStream()
                && webRtcLimiter.isFrameDue())
                || (deliverToFlutter && flutterLimiter.isFrameDue());
    }

    public void sendFrame(ImageProxy image, Integer customRotationDegrees) {
        RawStreamImageRef ref = new RawStreamImageRef(image, customRotationDegrees);

        if (webRtcFeedEnabled
                && WebRtcStreamHandler.getInstance().hasAnyVideoReadyStream()) {
            webRtcLimiter.onNewFrame(ref);
        }

        if (deliverToFlutter) {
            flutterLimiter.onNewFrame(ref);
        }
    }

    /**
     * Camera2 {@link Image} variant. Plane bytes are copied synchronously because the
     * Image is closed when the capture callback returns.
     */
    public void sendFrame(Image image, int rotationDegrees, Integer customRotationDegrees) {
        if (webRtcFeedEnabled
                && WebRtcStreamHandler.getInstance().hasAnyVideoReadyStream()
                && webRtcLimiter.shouldProcessFrame()) {
            WebRtcImageUtil.prepareWebRtcFrame(
                            image,
                            rotationDegrees,
                            customRotationDegrees,
                            webRtcStreamWidth,
                            webRtcStreamHeight
                    )
                    .ifPresent(frame -> WebRtcStreamHandler.getInstance().pushFrame(frame, null));
        }

        if (deliverToFlutter && flutterLimiter.shouldProcessFrame()) {
            Map<String, Object> data = imageAnalysisUtil.imageToI420Map(
                    image,
                    rotationDegrees,
                    customRotationDegrees
            );
            executor.execute(() -> FlutterEventChannel.getInstance().send(
                    -2,
                    "onCameraRawFrame",
                    data
            ));
        }
    }

    /**
     * Tightly packed NV21 variant (RTSP GPU readback). Converted once to I420 and copied,
     * so {@code nv21} may be reused by the caller after return.
     */
    public void sendNv21Frame(
            ByteBuffer nv21,
            int width,
            int height,
            int rotationDegrees,
            Integer customRotationDegrees
    ) {
        boolean webRtcDue = webRtcFeedEnabled
                && WebRtcStreamHandler.getInstance().hasAnyVideoReadyStream()
                && webRtcLimiter.shouldProcessFrame();
        boolean flutterDue = deliverToFlutter && flutterLimiter.shouldProcessFrame();
        if (!webRtcDue && !flutterDue) {
            return;
        }

        int rotation = customRotationDegrees != null ? customRotationDegrees : rotationDegrees;
        I420Image i420 = WebRtcImageUtil.fromNv21(nv21, width, height, rotation);

        if (flutterDue) {
            Map<String, Object> data = i420ToFlutterMap(i420);
            executor.execute(() -> FlutterEventChannel.getInstance().send(
                    -2,
                    "onCameraRawFrame",
                    data
            ));
        }

        if (webRtcDue) {
            WebRtcImageUtil.scaleI420(i420, webRtcStreamWidth, webRtcStreamHeight)
                    .ifPresent(frame -> WebRtcStreamHandler.getInstance().pushFrame(frame, null));
        } else {
            i420.close();
        }
    }

    private static Map<String, Object> i420ToFlutterMap(I420Image image) {
        List<Map<String, Object>> planes = new ArrayList<>(3);
        planes.add(planeMap(image.dataY, image.strideY, 1));
        planes.add(planeMap(image.dataU, image.strideU, image.pixelStrideU));
        planes.add(planeMap(image.dataV, image.strideV, image.pixelStrideV));

        Map<String, Object> data = new HashMap<>(6);
        data.put("width", image.width);
        data.put("height", image.height);
        data.put("format", "YUV_420_888");
        data.put("rotation", image.rotation);
        data.put("planes", planes);
        return data;
    }

    private static Map<String, Object> planeMap(ByteBuffer buffer, int rowStride, int pixelStride) {
        ByteBuffer src = buffer.duplicate();
        src.rewind();
        byte[] bytes = new byte[src.remaining()];
        src.get(bytes);

        Map<String, Object> plane = new HashMap<>(3);
        plane.put("bytes", bytes);
        plane.put("rowStride", rowStride);
        plane.put("pixelStride", pixelStride);
        return plane;
    }
}
