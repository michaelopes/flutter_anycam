package br.dev.michaellopes.flutter_anycam.stream;

import android.media.MediaCodec;

import androidx.annotation.NonNull;

import com.pedro.common.ConnectChecker;
import com.pedro.rtspserver.server.IpType;
import com.pedro.rtspserver.server.RtspServer;

import java.nio.ByteBuffer;

import br.dev.michaellopes.flutter_anycam.utils.Nv21Rotator;
import io.flutter.Log;
import io.github.crow_misia.libyuv.RotateMode;

public class RtspStreamer implements ConnectChecker {
    private final FrameEncoder frameEncoder;
    private RtspServer rtspServer;
    private final String username;
    private final String password;

private  final Nv21Rotator rotator =  new Nv21Rotator();
    public RtspStreamer(String username, String password) {
        this.username = username;
        this.password = password;
        frameEncoder = new FrameEncoder(1500_000, 15);
        setupListener();
        setupRtspServer();
    }
    public RtspStreamer( String username, String password, int fps) {
        frameEncoder = new FrameEncoder(1500_000, fps);
        this.username = username;
        this.password = password;
        setupListener();
        setupRtspServer();
    }

    private void setupListener() {
        frameEncoder.setOnH264FrameListener(new FrameEncoder.OnH264FrameListener() {
            @Override
            public void onSpsPps(byte[] spsPps) {
                System.out.println( "Aki 1" + spsPps.length);
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
        byte[] rt = rotator.rotateNv21(nv21, width,  height, rotation);
        frameEncoder.sendFrame(rt, width, height);
    }

    private void setupRtspServer() {
        rtspServer = new RtspServer(this, 8584);
        rtspServer.setOnlyVideo(true);
        rtspServer.setAuth(username, password);
        rtspServer.forceIpType(IpType.IPv4);
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
        System.out.println("deu ruim");
    }

    @Override
    public void onAuthSuccess() {
    }
}
