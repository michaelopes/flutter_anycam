package br.dev.michaellopes.flutter_anycam.stream;

import android.media.Image;

import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import br.dev.michaellopes.flutter_anycam.integration.FlutterEventChannel;
import br.dev.michaellopes.flutter_anycam.utils.FrameDeliveryGate;
import br.dev.michaellopes.flutter_anycam.utils.FrameRateLimiterUtil;
import br.dev.michaellopes.flutter_anycam.utils.ImageMapperUtil;
import br.dev.michaellopes.flutter_anycam.webrtc.I420Image;
import br.dev.michaellopes.flutter_anycam.webrtc.WebRtcImageUtil;
import br.dev.michaellopes.flutter_anycam.webrtc.WebRtcStreamHandler;

/**
 * Raw Flutter / WebRTC sinks for one camera. Called from camera analysis threads outside the
 * {@link CameraStreamManager} lock; configuration flags are written from the main thread.
 *
 * <p>Each sink keeps at most one converted frame in flight ({@link FrameDeliveryGate}), and
 * readiness is checked before any conversion, so a slow main thread or encoder can neither
 * grow a queue nor make producers convert frames that will be dropped.</p>
 */
public class CameraRawStream {
    private final String cameraId;
    private final FrameRateLimiterUtil<Object> flutterLimiter;
    private final FrameRateLimiterUtil<Object> webRtcLimiter;
    private final FrameDeliveryGate flutterDelivery = new FrameDeliveryGate();
    private final FrameDeliveryGate webRtcDelivery = new FrameDeliveryGate();

    protected final ImageMapperUtil imageAnalysisUtil = new ImageMapperUtil();

    private volatile boolean deliverToFlutter = false;
    private volatile boolean webRtcFeedEnabled = false;
    private volatile int webRtcStreamWidth = 0;
    private volatile int webRtcStreamHeight = 0;

    public CameraRawStream(String cameraId, int fps) {
        this.cameraId = cameraId;
        int safeFps = Math.max(1, fps);
        this.flutterLimiter = new FrameRateLimiterUtil<Object>(safeFps) {
            @Override
            protected void onFrameLimited(Object ignored) {
            }
        };
        this.webRtcLimiter = new FrameRateLimiterUtil<Object>(safeFps) {
            @Override
            protected void onFrameLimited(Object ignored) {
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
        return (webRtcReady() && webRtcLimiter.isFrameDue())
                || (flutterReady() && flutterLimiter.isFrameDue());
    }

    private boolean webRtcReady() {
        return webRtcFeedEnabled
                && webRtcDelivery.isIdle()
                && WebRtcStreamHandler.getInstance().hasAnyVideoReadyStream();
    }

    private boolean flutterReady() {
        return deliverToFlutter && flutterDelivery.isIdle();
    }

    /** CameraX path; {@code image} is only read synchronously. */
    public void sendFrame(ImageProxy image, Integer customRotationDegrees) {
        if (webRtcReady() && webRtcLimiter.shouldProcessFrame()) {
            pushWebRtc(WebRtcImageUtil.prepareWebRtcFrame(
                    image,
                    customRotationDegrees,
                    webRtcStreamWidth,
                    webRtcStreamHeight
            ));
        }

        if (flutterReady() && flutterLimiter.shouldProcessFrame()) {
            sendToFlutter(imageAnalysisUtil.imageProxyToI420Map(image, customRotationDegrees));
        }
    }

    /**
     * Camera2 {@link Image} variant. Plane bytes are copied synchronously because the
     * Image is closed when the capture callback returns.
     */
    public void sendFrame(Image image, int rotationDegrees, Integer customRotationDegrees) {
        if (webRtcReady() && webRtcLimiter.shouldProcessFrame()) {
            pushWebRtc(WebRtcImageUtil.prepareWebRtcFrame(
                    image,
                    rotationDegrees,
                    customRotationDegrees,
                    webRtcStreamWidth,
                    webRtcStreamHeight
            ));
        }

        if (flutterReady() && flutterLimiter.shouldProcessFrame()) {
            sendToFlutter(imageAnalysisUtil.imageToI420Map(image, rotationDegrees, customRotationDegrees));
        }
    }

    /**
     * Tightly packed NV21 variant (RTSP GPU readback, USB). Converted once to I420 and copied,
     * so {@code nv21} may be reused by the caller after return.
     */
    public void sendNv21Frame(
            ByteBuffer nv21,
            int width,
            int height,
            int rotationDegrees,
            Integer customRotationDegrees
    ) {
        boolean webRtcDue = webRtcReady() && webRtcLimiter.shouldProcessFrame();
        boolean flutterDue = flutterReady() && flutterLimiter.shouldProcessFrame();
        if (!webRtcDue && !flutterDue) {
            return;
        }

        int rotation = customRotationDegrees != null ? customRotationDegrees : rotationDegrees;
        I420Image i420 = WebRtcImageUtil.fromNv21(nv21, width, height, rotation);

        if (flutterDue) {
            sendToFlutter(i420ToFlutterMap(i420));
        }

        if (webRtcDue) {
            pushWebRtc(WebRtcImageUtil.scaleI420(i420, webRtcStreamWidth, webRtcStreamHeight));
        } else {
            i420.close();
        }
    }

    private void sendToFlutter(Map<String, Object> data) {
        FrameDeliveryGate.Delivery delivery = flutterDelivery.tryBegin(1, null);
        if (delivery == null) {
            return;
        }
        FlutterEventChannel.getInstance().send(-2, "onCameraRawFrame", data, delivery::complete);
    }

    private void pushWebRtc(Optional<I420Image> frame) {
        if (!frame.isPresent()) {
            return;
        }
        I420Image image = frame.get();
        FrameDeliveryGate.Delivery delivery = webRtcDelivery.tryBegin(1, null);
        if (delivery == null) {
            image.close();
            return;
        }
        WebRtcStreamHandler.getInstance().pushFrame(image, null, () -> delivery.complete(true));
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
