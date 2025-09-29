package br.dev.michaellopes.flutter_anycam.camera;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import br.dev.michaellopes.flutter_anycam.utils.ContextUtil;
import br.dev.michaellopes.flutter_anycam.utils.FrameRateLimiterUtil;
import io.flutter.Log;
import io.flutter.view.TextureRegistry;
import ir.am3n.rtsp.client.Rtsp;
import ir.am3n.rtsp.client.data.SdpInfo;
import ir.am3n.rtsp.client.data.VideoTrack;
import ir.am3n.rtsp.client.data.YuvFrame;
import ir.am3n.rtsp.client.interfaces.Frame;
import ir.am3n.rtsp.client.interfaces.RtspFrameListener;
import ir.am3n.rtsp.client.interfaces.RtspStatusListener;
import ir.am3n.rtsp.client.widget.RtspSurfaceView;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class RTSPCamera extends BaseCamera {

    private Rtsp rtsp;
    private RSTPVideoDecoder vd;

    FrameRateLimiterUtil<FrameItem> limiter = new FrameRateLimiterUtil<FrameItem>(getFps()) {
        @Override
        protected void onFrameLimited(FrameItem frameItem) {
            int w = frameItem.image.getWidth();
            int h = frameItem.image.getHeight();
            //   Log.i("RTSPCamera", "Process onVideoFrameReceived");
            Map<String, Object> imageData = imageAnalysisUtil.rtspFrameToFlutterResult(frameItem.frame.getData(), w, h, getCustomRotationDegrees());
            onVideoFrameReceived(imageData);
        }
    };

    public RTSPCamera(TextureRegistry.SurfaceTextureEntry texture, Map<String, Object> params) {
        super(texture, params);
    }

    private Integer getCustomRotationDegrees() {
        if (cameraSelector.isForceSensorOrientation()) {
            return cameraSelector.getSensorOrientation();
        }
        return null;
    }

    @Override
    protected void init() {
        try {

            rtsp = new Rtsp();
            String url = cameraSelector.getCameraSelectorRTSP().url;
            String username = cameraSelector.getCameraSelectorRTSP().username;
            String password = cameraSelector.getCameraSelectorRTSP().password;

            vd = null;
            Surface surface = getSurface();

            rtsp.init(url, username, password, null, 3000);

            rtsp.setFrameListener(new RtspFrameListener() {
                @Override
                public void onVideoNalUnitReceived(@Nullable Frame frame) {
                    if (vd != null) {
                        vd.decode(frame.getData());
                    }
                }

                @Override
                public void onVideoFrameReceived(int width, int height, @Nullable Image image, @Nullable YuvFrame yuvFrame, @Nullable Bitmap b) {
                    limiter.onNewFrame(new FrameItem(yuvFrame, image));
                }

                @Override
                public void onAudioSampleReceived(@Nullable Frame frame) {
                }

            });

            rtsp.setStatusListener(new RtspStatusListener() {
                @Override
                public void onConnecting() {
                }

                @Override
                public void onConnected(@NonNull SdpInfo sdpInfo) {
                    try {
                        int width = sdpInfo.getVideoTrack().getFrameWidth();
                        int height = sdpInfo.getVideoTrack().getFrameHeight();

                       vd = new RSTPVideoDecoder(surface, width, height);

                        final Map<String, Object> result = new HashMap<>();
                        result.put("width", width);
                        result.put("height", height);

                        RTSPCamera.this.onConnected(result);


                    } catch (Exception e) {
                        onFailed(e.getMessage());
                    }

                }

                @Override
                public void onFirstFrameRendered() {

                }

                @Override
                public void onDisconnecting() {
                }

                @Override
                public void onDisconnected() {
                    if (vd != null) {
                        vd.dispose();
                    }
                    RTSPCamera.this.onDisconnected();
                }

                @Override
                public void onUnauthorized() {
                    if (vd != null) {
                        vd.dispose();
                    }
                    RTSPCamera.this.onUnauthorized();
                }

                @Override
                public void onFailed(@Nullable String s) {
                    if (vd != null) {
                        vd.dispose();
                    }
                    RTSPCamera.this.onFailed(s);
                }
            });


            rtsp.setRequestYuv(true);
            rtsp.setRequestMediaImage(true);
            rtsp.start(true, false);

        } catch (Exception e) {
            onFailed(e.getMessage());
        }
    }

    @Override
    public void dispose() {
        if (rtsp != null) {
            rtsp.stop();
            rtsp.stop();
            rtsp = null;
        }
        if (vd != null) {
            vd.dispose();
            vd = null;
        }
        super.dispose();
    }


    public static class RSTPVideoDecoder {
        private MediaCodec mediaCodec;

        public RSTPVideoDecoder(Surface surface, int width, int height) throws Exception {
            mediaCodec = MediaCodec.createDecoderByType("video/avc");
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
            mediaCodec.configure(format, surface, null, 0);
            mediaCodec.start();
        }

        public void decode(byte[] nalUnit) {
            int inputBufferIndex = mediaCodec.dequeueInputBuffer(10000);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = mediaCodec.getInputBuffer(inputBufferIndex);

                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(nalUnit);
                    mediaCodec.queueInputBuffer(inputBufferIndex, 0, nalUnit.length, System.nanoTime(), 0);
                }
            }

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000);

            if (outputBufferIndex >= 0) {
                mediaCodec.releaseOutputBuffer(outputBufferIndex, true);
            }
        }

        public void dispose() {
            if (mediaCodec != null) {
                mediaCodec.stop();
                mediaCodec.release();
                mediaCodec = null;
            }
        }
    }

    private static class FrameItem {
        public final YuvFrame frame;
        public final Image image;

        private FrameItem(YuvFrame frame, Image image) {
            this.frame = frame;
            this.image = image;
        }
    }

}
