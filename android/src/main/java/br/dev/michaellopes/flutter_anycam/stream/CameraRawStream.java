package br.dev.michaellopes.flutter_anycam.stream;

import androidx.camera.core.ImageProxy;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import br.dev.michaellopes.flutter_anycam.integration.FlutterEventChannel;

import br.dev.michaellopes.flutter_anycam.utils.FrameRateLimiterUtil;
import br.dev.michaellopes.flutter_anycam.utils.ImageAnalysisUtil;

public class CameraRawStream {
  private final String cameraId;
  private final FrameRateLimiterUtil<Map<String, Object>> limiter;
  private final ExecutorService executor;

    protected final ImageAnalysisUtil imageAnalysisUtil = new ImageAnalysisUtil();

    public CameraRawStream(String cameraId, int fps) {
        this.cameraId = cameraId;

        executor = Executors.newSingleThreadExecutor();
        this.limiter   =  new FrameRateLimiterUtil<Map<String, Object>>(fps) {
            @Override
            protected void onFrameLimited(Map<String, Object> data) {
                executor.execute(() -> {
                    FlutterEventChannel.getInstance().send(
                            -2,
                            "onCameraRawFrame",
                            data
                    );
                });
            }
        };
    }

    public String getCameraId() {
        return cameraId;
    }

    public void sendFrame(ImageProxy image, Integer customRotationDegrees) {
      Map<String, Object> data = imageAnalysisUtil.imageProxyToI420Map(image, customRotationDegrees);
      limiter.onNewFrame(data);
    }

    private static class LimiterParams {
        public final byte[] nv21;
        public final int width;
        public final int height;
        public final int rotation;

        private LimiterParams(byte[] nv21, int width, int height, int rotation) {
            this.nv21 = nv21;
            this.width = width;
            this.height = height;
            this.rotation = rotation;
        }
    }
}
