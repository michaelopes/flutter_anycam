package br.dev.michaellopes.flutter_anycam.camera;

import android.net.Uri;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.alexvas.rtsp.RtspClient;
import com.alexvas.utils.NetUtils;

import br.dev.michaellopes.flutter_anycam.tensorflow.TfFrameHandler;
import br.dev.michaellopes.flutter_anycam.utils.RtspDecoderUtil;
import io.flutter.view.TextureRegistry;

import java.io.IOException;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLSocket;


public class RTSPCamera extends BaseCamera {


    private final AtomicBoolean stopped = new AtomicBoolean(true);
    private volatile boolean disposed = false;
    private final ExecutorService executor;

    CompletableFuture<Map<String, Object>> connectionFuture = new CompletableFuture<>();

    private final RtspDecoderUtil rtspDecoder = new RtspDecoderUtil(getSurface(), getSize().getWidth(), getSize().getHeight(), getFps(), new RtspDecoderUtil.RtspDecoderCallback() {
        @Override
        public void onResolutionResult(int width, int height) {
            texture.surfaceTexture().setDefaultBufferSize(width, height);
            final Map<String, Object> result = new HashMap<>();
            result.put("width", width);
            result.put("height", height);
            if (!connectionFuture.isDone()) {
                connectionFuture.complete(result);
            }
        }

        @Override
        public void onYuvFrame(RtspDecoderUtil.YuvFrame frame, RtspDecoderUtil.YuvFrame rawFrame) {
            if (disposed) {
                return;
            }
            Map<String, Object> frameMap;
            if (isStandard()) {
                frameMap = imageAnalysisUtil.rtspFrameToNV21Map(
                        frame.nv21,
                        frame.width,
                        frame.height,
                        filter,
                        getCustomRotationDegrees());
                if (rawFrame != null) {
                    Map<String, Object> rawData = imageAnalysisUtil.rtspFrameToNV21Map(
                            rawFrame.nv21,
                            rawFrame.width,
                            rawFrame.height,
                            0,
                            getCustomRotationDegrees());
                    frameMap.put("rawFrame", rawData);
                }
            } else {
                frameMap = TfFrameHandler.getInstance().addFrame(
                        frame.nv21,
                        frame.width,
                        frame.height,
                        filter,
                        getCustomRotationDegrees());
            }
            if (frameMap != null && !disposed) {
                onVideoFrameReceived(frameMap);
            }
        }
    }, e -> onFailed(e.getMessage()));

    Size getSize() {
        if(resizeFrame != null) {
            return  resizeFrame;
        }
        return preferredSize;
    }

    public RTSPCamera(TextureRegistry.SurfaceTextureEntry texture, Map<String, Object> params) {
        super(texture, params);
        executor = Executors.newSingleThreadExecutor();
    }

    private Integer getCustomRotationDegrees() {
        if (cameraSelector.isForceSensorOrientation()) {
            return cameraSelector.getSensorOrientation();
        }
        return null;
    }

    @Override
    protected void init() {

        synchronized (executor) {
            String url = cameraSelector.getCameraSelectorRTSP().url;
            String username = cameraSelector.getCameraSelectorRTSP().username;
            String password = cameraSelector.getCameraSelectorRTSP().password;
            Uri uri = Uri.parse(url);

            RtspClient.RtspClientListener listener = new RtspClient.RtspClientListener() {
                @Override
                public void onRtspConnecting() {
                }

                @Override
                public void onRtspConnected(@NonNull RtspClient.SdpInfo sdpInfo) {
                    connectionFuture.thenAccept(RTSPCamera.this::onConnected);
                    rtspDecoder.start();
                }

                @Override
                public void onRtspVideoNalUnitReceived(@NonNull byte[] bytes, int i, int i1, long l) {
                    rtspDecoder.dispatchNal(bytes, i, i1, l);
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

                }

                @Override
                public void onRtspFailedUnauthorized() {
                }

                @Override
                public void onRtspFailed(@Nullable String s) {
                    onFailed(s);
                }
            };

            executor.submit(() -> {
                Socket socket = null;
                try {
                    boolean isSecure = uri.getScheme().equalsIgnoreCase("rtsps");
                    if (isSecure) {
                        socket = NetUtils.createSslSocketAndConnect(uri.getHost(), uri.getPort(), 20000);
                    } else {
                        socket = NetUtils.createSocketAndConnect(uri.getHost(), uri.getPort(), 20000);
                    }
                    stopped.set(false);
                    RtspClient rtsp = new RtspClient.Builder(socket, uri.toString(), stopped, listener)
                            .requestVideo(true)
                            .requestAudio(false)
                            .withDebug(true)
                            .withUserAgent("RTSP-client")
                            .withCredentials(username, password)
                            .build();
                    rtsp.execute();
                } catch (Exception e) {
                    onFailed(e.getMessage());
                } finally {
                    if(socket != null) {
                        try {
                            NetUtils.closeSocket(socket);
                        } catch (IOException ignored) {}
                    }
                }
            });
        }
    }

    @Override
    public void dispose() {
        disposed = true;
        stopped.set(true);
        rtspDecoder.dispose();
        executor.shutdownNow();
        super.dispose();
    }


}
