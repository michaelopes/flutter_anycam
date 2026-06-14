package br.dev.michaellopes.flutter_anycam.stream;

import androidx.camera.core.ImageProxy;

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
    private final FrameRateLimiterUtil<Map<String, Object>> flutterLimiter;
    private final FrameRateLimiterUtil<I420Image> webRtcLimiter;
    private final ExecutorService executor;

    protected final ImageMapperUtil imageAnalysisUtil = new ImageMapperUtil();

    private boolean deliverToFlutter = false;
    private String webRtcStreamId = null;

    public CameraRawStream(String cameraId, int fps) {
        this.cameraId = cameraId;

        executor = Executors.newSingleThreadExecutor();
        this.flutterLimiter = new FrameRateLimiterUtil<Map<String, Object>>(fps) {
            @Override
            protected void onFrameLimited(Map<String, Object> data) {
                executor.execute(() -> FlutterEventChannel.getInstance().send(
                        -2,
                        "onCameraRawFrame",
                        data
                ));
            }
        };
        this.webRtcLimiter = new FrameRateLimiterUtil<I420Image>(fps) {
            @Override
            protected void onFrameLimited(I420Image data) {
                WebRtcStreamHandler.getInstance().pushFrame(data, webRtcStreamId);
            }

            @Override
            protected void onFrameSkipped(I420Image data) {
                data.close();
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

    public boolean hasActiveSink() {
        return deliverToFlutter || webRtcStreamId != null;
    }

    public void sendFrame(ImageProxy image, Integer customRotationDegrees) {
        if (webRtcStreamId != null) {
            I420Image i420 = WebRtcImageUtil.fromImageProxy(image, customRotationDegrees);
            if (i420 != null) {
                webRtcLimiter.onNewFrame(i420);
            }
        }
        if (deliverToFlutter) {
            Map<String, Object> data = imageAnalysisUtil.imageProxyToI420Map(image, customRotationDegrees);
            flutterLimiter.onNewFrame(data);
        }
    }
}
