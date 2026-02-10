package br.dev.michaellopes.flutter_anycam.stream;

import android.media.MediaCodec;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import br.dev.michaellopes.flutter_anycam.integration.FlutterEventChannel;
import br.dev.michaellopes.flutter_anycam.model.ViewCameraSelector;
import br.dev.michaellopes.flutter_anycam.utils.FrameRateLimiterUtil;

public class CameraRawStream {
  private final String cameraId;
  private final FrameEncoder frameEncoder;
  private final FrameRateLimiterUtil<LimiterParams> limiter;
  private final ExecutorService executor;

    public CameraRawStream(String cameraId, int fps) {
        this.cameraId = cameraId;
        this.frameEncoder = new FrameEncoder(1500_000, fps);
        executor = Executors.newSingleThreadExecutor();
        this.limiter   =  new FrameRateLimiterUtil<LimiterParams>(fps) {
            @Override
            protected void onFrameLimited(LimiterParams image) {
                executor.execute(() -> {
                    frameEncoder.sendFrame(image.nv21, image.width, image.height, image.rotation);
                });
            }
        };

        frameEncoder.setOnH264FrameListener(new FrameEncoder.OnH264FrameListener() {
            @Override
            public void onSpsPps(byte[] spsPps) {
            }

            @Override
            public void onH264Frame(byte[] frame, MediaCodec.BufferInfo info) {
                FlutterEventChannel.getInstance().send(-1, "onVideoH264Frame" ,new HashMap<String, Object>() {{
                    put("cameraId", cameraId);
                    put("h264", frame);
                }});
            }

            @Override
            public void onVideoInfo(ByteBuffer sps, ByteBuffer pps, ByteBuffer vps) {
            }
        });

    }

    public String getCameraId() {
        return cameraId;
    }

    public void sendFrame(byte[] nv21, int width, int height, int rotation) {
        limiter.onNewFrame(new LimiterParams(nv21, width, height, rotation));
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
