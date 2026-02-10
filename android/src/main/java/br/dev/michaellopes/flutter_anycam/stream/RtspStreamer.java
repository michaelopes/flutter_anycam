package br.dev.michaellopes.flutter_anycam.stream;

import android.media.MediaCodec;

import androidx.annotation.NonNull;

import com.pedro.common.ConnectChecker;
import com.pedro.rtspserver.server.ClientListener;
import com.pedro.rtspserver.server.IpType;
import com.pedro.rtspserver.server.RtspServer;
import com.pedro.rtspserver.server.ServerClient;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import br.dev.michaellopes.flutter_anycam.utils.FrameRateLimiterUtil;
import io.flutter.Log;

public class RtspStreamer implements ConnectChecker {
    private final FrameEncoder frameEncoder;
    private RtspServer rtspServer;
    private final String username;
    private final String password;

    private final ExecutorService executor;
    private final FrameRateLimiterUtil<LimiterParams> limiter;

    public RtspStreamer(String username, String password) {
        this.username = username;
        this.password = password;
        frameEncoder = new FrameEncoder(1500_000, 15);
        executor = Executors.newSingleThreadExecutor();
        limiter =  new FrameRateLimiterUtil<LimiterParams>(15) {
            @Override
            protected void onFrameLimited(LimiterParams image) {
                executor.execute(() -> {
                    frameEncoder.sendFrame(image.nv21, image.width, image.height, image.rotation);
                });
            }
        };
        setupListener();
        setupRtspServer();
    }

    public RtspStreamer(String username, String password, int fps) {
        this.username = username;
        this.password = password;
        frameEncoder = new FrameEncoder(1500_000, fps);
        executor = Executors.newSingleThreadExecutor();
        limiter =  new FrameRateLimiterUtil<LimiterParams>(fps) {
            @Override
            protected void onFrameLimited(LimiterParams image) {
                executor.execute(() -> {
                    frameEncoder.sendFrame(image.nv21, image.width, image.height, image.rotation);
                });
            }
        };
        setupListener();
        setupRtspServer();
    }

    private void setupListener() {
        frameEncoder.setOnH264FrameListener(new FrameEncoder.OnH264FrameListener() {
            @Override
            public void onSpsPps(byte[] spsPps) {
                System.out.println("Aki 1" + spsPps.length);
            }

            @Override
            public void onH264Frame(byte[] frame, MediaCodec.BufferInfo info) {
                ByteBuffer buffer = ByteBuffer.wrap(frame);
                rtspServer.sendVideo(buffer, info);
            }

            @Override
            public void onVideoInfo(ByteBuffer sps, ByteBuffer pps, ByteBuffer vps) {
                rtspServer.setVideoInfo(sps, pps, vps);
            }
        });
    }


    public synchronized void sendFrame(@NonNull byte[] nv21, int width, int height, int rotation) {
        limiter.onNewFrame(new LimiterParams(nv21, width, height, rotation));
    }

    private void setupRtspServer() {
        rtspServer = new RtspServer(this, 8584);
        rtspServer.setOnlyVideo(true);
        rtspServer.setAuth(username, password);
        rtspServer.forceIpType(IpType.IPv4);



        rtspServer.setClientListener(new ClientListener() {
            @Override
            public void onClientConnected(@NonNull ServerClient serverClient) {

            }

            @Override
            public void onClientDisconnected(@NonNull ServerClient serverClient) {

            }

            @Override
            public void onClientNewBitrate(long l, @NonNull ServerClient serverClient) {

            }
        });

        rtspServer.startServer();

        Log.d("getServerIp", rtspServer.getServerIp());
    }

    @Override
    public void onConnectionStarted(@NonNull String s) {
    }

    @Override
    public void onConnectionSuccess() {
    }

    @Override
    public void onConnectionFailed(@NonNull String s) {
        System.out.println("deu ruim");
    }

    @Override
    public void onDisconnect() {
    }

    @Override
    public void onAuthError() {
        System.out.println("onAuthError");
    }

    @Override
    public void onAuthSuccess() {
        System.out.println("onAuthSuccess");
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
