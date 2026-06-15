package br.dev.michaellopes.flutter_anycam.stream;

import androidx.camera.core.ImageProxy;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import br.dev.michaellopes.flutter_anycam.integration.FlutterEventChannel;
import br.dev.michaellopes.flutter_anycam.utils.FrameRateLimiterUtil;
import br.dev.michaellopes.flutter_anycam.utils.ImageMapperUtil;
import br.dev.michaellopes.flutter_anycam.webrtc.WebRtcImageUtil;
import br.dev.michaellopes.flutter_anycam.webrtc.WebRtcStreamHandler;

public class CameraRawStream {
    private final String cameraId;
    private final FrameRateLimiterUtil<RawStreamImageRef> flutterLimiter;
    private final FrameRateLimiterUtil<RawStreamImageRef> webRtcLimiter;
    private final ExecutorService executor;

    protected final ImageMapperUtil imageAnalysisUtil = new ImageMapperUtil();

    private boolean deliverToFlutter = false;
    private String webRtcStreamId = null;
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
                                .pushFrame(image, webRtcStreamId));
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

    public void setWebRtcStreamId(String webRtcStreamId) {
        this.webRtcStreamId = webRtcStreamId;
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
        return deliverToFlutter || webRtcStreamId != null;
    }

    public void sendFrame(ImageProxy image, Integer customRotationDegrees) {
        RawStreamImageRef ref = new RawStreamImageRef(image, customRotationDegrees);

        if (webRtcStreamId != null
                && WebRtcStreamHandler.getInstance().shouldPushVideoFrames(webRtcStreamId)) {
            webRtcLimiter.onNewFrame(ref);
        }

        if (deliverToFlutter) {
            flutterLimiter.onNewFrame(ref);
        }
    }
}
