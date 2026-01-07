package br.dev.michaellopes.flutter_anycam.camera;

import io.flutter.view.TextureRegistry;
import java.util.Map;


public class RTSPCamera extends BaseCamera {
    public RTSPCamera(TextureRegistry.SurfaceTextureEntry texture, Map<String, Object> params) {
        super(texture, params);
    }

    @Override
    protected void init() {
        throw new UnsupportedOperationException("RTSP camera support has been discontinued in this package because it does not comply with the new 16 KB paging requirement mandated by the Play Store.");
    }

//    private Rtsp rtsp;
//
//    CompletableFuture<Map<String, Object>> connectionFuture = new CompletableFuture<>();
//
//    private final RtspDecoderUtil rtspDecoder = new RtspDecoderUtil(getSurface(), (width, height) -> {
//        frameWidth = width;
//        frameHeight = height;
//
//        texture.surfaceTexture().setDefaultBufferSize(width, height);
//
//        final Map<String, Object> result = new HashMap<>();
//        result.put("width", width);
//        result.put("height", height);
//        if (!connectionFuture.isDone()) {
//            connectionFuture.complete(result);
//        }
//
//    }, e -> onFailed(e.getMessage()));
//
//    private int frameWidth;
//    private int frameHeight;
//
//    FrameRateLimiterUtil<YuvFrame> limiter = new FrameRateLimiterUtil<YuvFrame>(getFps()) {
//        @Override
//        protected void onFrameLimited(YuvFrame yuvFrame) {
//            Map<String, Object> imageData = imageAnalysisUtil.rtspFrameToFlutterResult(yuvFrame.getData(), frameWidth, frameHeight, getCustomRotationDegrees());
//            onVideoFrameReceived(imageData);
//        }
//    };
//
//    public RTSPCamera(TextureRegistry.SurfaceTextureEntry texture, Map<String, Object> params) {
//        super(texture, params);
//    }
//
//    private Integer getCustomRotationDegrees() {
//        if (cameraSelector.isForceSensorOrientation()) {
//            return cameraSelector.getSensorOrientation();
//        }
//        return null;
//    }
//
//    @Override
//    protected void init() {
//        try {
//
//            rtsp = new Rtsp();
//            String url = cameraSelector.getCameraSelectorRTSP().url;
//            String username = cameraSelector.getCameraSelectorRTSP().username;
//            String password = cameraSelector.getCameraSelectorRTSP().password;
//
//
//            rtsp.init(url, username, password, null, 3000);
//
//            rtsp.setFrameListener(new RtspFrameListener() {
//                @Override
//                public void onVideoNalUnitReceived(@Nullable Frame frame) {
//                    rtspDecoder.decode(frame.getData(), frame.getOffset(), frame.getLength(), frame.getTimestamp());
//                }
//
//                @Override
//                public void onVideoFrameReceived(int width, int height, @Nullable Image image, @Nullable YuvFrame yuvFrame, @Nullable Bitmap b) {
//                    limiter.onNewFrame(yuvFrame);
//                }
//
//                @Override
//                public void onAudioSampleReceived(@Nullable Frame frame) {
//                }
//
//            });
//
//            rtsp.setStatusListener(new RtspStatusListener() {
//                @Override
//                public void onConnecting() {
//                }
//
//                @Override
//                public void onConnected(@NonNull SdpInfo sdpInfo) {
//                    connectionFuture.thenAccept(RTSPCamera.this::onConnected);
//                }
//
//                @Override
//                public void onFirstFrameRendered() {
//
//                }
//
//                @Override
//                public void onDisconnecting() {
//                }
//
//                @Override
//                public void onDisconnected() {
//                    RTSPCamera.this.onDisconnected();
//                }
//
//                @Override
//                public void onUnauthorized() {
//                    RTSPCamera.this.onUnauthorized();
//                }
//
//                @Override
//                public void onFailed(@Nullable String s) {
//                    RTSPCamera.this.onFailed(s);
//                }
//            });
//
//            rtsp.setRequestYuv(true);
//            rtsp.setRequestMediaImage(false);
//            rtsp.start(true, false);
//
//        } catch (Exception e) {
//            onFailed(e.getMessage());
//        }
//    }
//
//    @Override
//    public void dispose() {
//        if (rtsp != null) {
//            rtsp.stop();
//            rtsp.stop();
//            rtsp = null;
//        }
//        rtspDecoder.release();
//        super.dispose();
//    }

}
