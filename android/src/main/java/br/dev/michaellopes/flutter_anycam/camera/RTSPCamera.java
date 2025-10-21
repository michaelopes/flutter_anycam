package br.dev.michaellopes.flutter_anycam.camera;


import android.net.Uri;
import android.view.PixelCopy;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.alexvas.rtsp.RtspClient;
import com.alexvas.utils.NetUtils;

import br.dev.michaellopes.flutter_anycam.utils.FrameRateLimiterUtil;
import br.dev.michaellopes.flutter_anycam.utils.RtspDecoderUtil;
import io.flutter.view.TextureRegistry;

import java.io.IOException;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class RTSPCamera extends BaseCamera {
    private final static int DEFAULT_RTSP_PORT = 554;

    private final AtomicBoolean rtspStopped = new AtomicBoolean(true);

    private CompletableFuture<Map<String, Object>> connectionFuture;

    private int frameWidth = 640;
    private int frameHeight = 480;


    FrameRateLimiterUtil<byte[]> limiter = new FrameRateLimiterUtil<byte[]>(getFps()) {
        @Override
        protected void onFrameLimited(byte[] yuvFrame) {
            Map<String, Object> imageData = imageAnalysisUtil.rtspFrameToFlutterResult(yuvFrame, frameWidth, frameHeight, getCustomRotationDegrees());
            onVideoFrameReceived(imageData);
        }
    };

/*
* (width, height) -> {


    }
* */

    private final RtspDecoderUtil rtspDecoder = new RtspDecoderUtil(getSurface(), new RtspDecoderUtil.RtspDecoderCallback() {
        @Override
        public void onResolutionResult(int width, int height) {
            frameWidth = width;
            frameHeight = height;

            texture.surfaceTexture().setDefaultBufferSize(width, height);

            final Map<String, Object> result = new HashMap<>();
            result.put("width", width);
            result.put("height", height);
            if (connectionFuture != null && !connectionFuture.isDone()) {
                connectionFuture.complete(result);
            }
        }

        @Override
        public void onFrameDecoded(byte[] yuv, int width, int height) {
          //  limiter.onNewFrame(yuv);

        }
    }, e -> onFailed(e.getMessage()));




    private final RtspClient.RtspClientListener rtspClientListener = new RtspClient.RtspClientListener() {

        @Override
        public void onRtspConnecting() {
            System.out.println("ssss");
        }

        @Override
        public void onRtspConnected(@NonNull RtspClient.SdpInfo sdpInfo) {
            connectionFuture.thenAccept(RTSPCamera.this::onConnected);
        }

        @Override
        public void onRtspVideoNalUnitReceived(@NonNull byte[] bytes, int i, int i1, long l) {
            rtspDecoder.decode(bytes, i, i1, l);
        }

        @Override
        public void onRtspAudioSampleReceived(@NonNull byte[] bytes, int i, int i1, long l) {
        }

        @Override
        public void onRtspApplicationDataReceived(@NonNull byte[] bytes, int i, int i1, long l) {
        }

        @Override
        public void onRtspDisconnecting() {
        }

        @Override
        public void onRtspDisconnected() {
            RTSPCamera.this.onDisconnected();
        }

        @Override
        public void onRtspFailedUnauthorized() {
            RTSPCamera.this.onUnauthorized();
        }

        @Override
        public void onRtspFailed(@Nullable String s) {
            System.out.println(s);
            RTSPCamera.this.onFailed(s);
        }
    };

    public RTSPCamera(TextureRegistry.SurfaceTextureEntry texture, Map<String, Object> params) {
        super(texture, params);
    }


    private final Runnable threadRunnable = () -> {
        String url = cameraSelector.getCameraSelectorRTSP().url;
        String username = cameraSelector.getCameraSelectorRTSP().username;
        String password = cameraSelector.getCameraSelectorRTSP().password;

        Socket socket = null;
        try {
            Uri uri = Uri.parse(url);
            int port = (uri.getPort() == -1) ? DEFAULT_RTSP_PORT : uri.getPort();
            socket = NetUtils.createSocketAndConnect(uri.getHost(), port, 30000);

            RtspClient.Builder rtspClientBuilder = new RtspClient.Builder(
                    socket,
                    uri.toString(),
                    rtspStopped,
                    rtspClientListener
            );


            rtspClientBuilder.requestVideo(true)
                    .requestAudio(false)
                    .requestApplication(true)
                    .withDebug(true)
                    .withUserAgent("rtsp-client-android")
                    .withCredentials(
                            username,
                            password
                    );

            RtspClient rtspClient = rtspClientBuilder.build();
            rtspClient.execute();

        } catch (Exception e) {
            e.printStackTrace();
            rtspClientListener.onRtspFailed(e.getMessage());
        } finally {
            try {
                NetUtils.closeSocket(socket);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    };

    private Integer getCustomRotationDegrees() {
        if (cameraSelector.isForceSensorOrientation()) {
            return cameraSelector.getSensorOrientation();
        }
        return null;
    }


    @Override
    protected synchronized void init() {
        connectionFuture = new CompletableFuture<>();
        if (rtspStopped.get()) {
            rtspStopped.set(false);
            Thread rtspThread = new Thread(threadRunnable);
            rtspThread.setName("RTSP-Thread");
            rtspThread.start();
        }
    }

   /* private Rtsp rtsp;

    CompletableFuture<Map<String, Object>> connectionFuture = new CompletableFuture<>();

    private final RtspDecoderUtil rtspDecoder = new RtspDecoderUtil(getSurface(), (width, height) -> {
        frameWidth = width;
        frameHeight = height;

        texture.surfaceTexture().setDefaultBufferSize(width, height);

        final Map<String, Object> result = new HashMap<>();
        result.put("width", width);
        result.put("height", height);
        if (!connectionFuture.isDone()) {
            connectionFuture.complete(result);
        }

    }, e -> onFailed(e.getMessage()));

    private int frameWidth;
    private int frameHeight;

    FrameRateLimiterUtil<YuvFrame> limiter = new FrameRateLimiterUtil<YuvFrame>(getFps()) {
        @Override
        protected void onFrameLimited(YuvFrame yuvFrame) {
            Map<String, Object> imageData = imageAnalysisUtil.rtspFrameToFlutterResult(yuvFrame.getData(), frameWidth, frameHeight, getCustomRotationDegrees());
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


            rtsp.init(url, username, password, null, 3000);

            rtsp.setFrameListener(new RtspFrameListener() {
                @Override
                public void onVideoNalUnitReceived(@Nullable Frame frame) {
                    rtspDecoder.decode(frame.getData(), frame.getOffset(), frame.getLength(), frame.getTimestamp());
                }

                @Override
                public void onVideoFrameReceived(int width, int height, @Nullable Image image, @Nullable YuvFrame yuvFrame, @Nullable Bitmap b) {
                    limiter.onNewFrame(yuvFrame);
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
                    connectionFuture.thenAccept(RTSPCamera.this::onConnected);
                }

                @Override
                public void onFirstFrameRendered() {

                }

                @Override
                public void onDisconnecting() {
                }

                @Override
                public void onDisconnected() {
                    RTSPCamera.this.onDisconnected();
                }

                @Override
                public void onUnauthorized() {
                    RTSPCamera.this.onUnauthorized();
                }

                @Override
                public void onFailed(@Nullable String s) {
                    RTSPCamera.this.onFailed(s);
                }
            });

            rtsp.setRequestYuv(true);
            rtsp.setRequestMediaImage(false);
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
        rtspDecoder.release();
        super.dispose();
    }*/

}
