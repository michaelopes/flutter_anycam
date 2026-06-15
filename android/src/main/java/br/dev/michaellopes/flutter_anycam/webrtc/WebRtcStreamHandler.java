package br.dev.michaellopes.flutter_anycam.webrtc;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;

import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.JavaI420Buffer;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.VideoFrame;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import br.dev.michaellopes.flutter_anycam.utils.ContextUtil;
import io.flutter.plugin.common.MethodChannel;

public class WebRtcStreamHandler {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private final List<WebRtcStreamer> streamers = new ArrayList<>();

    private static WebRtcStreamHandler instance;

    private final PeerConnectionFactory factory;

    private WebRtcStreamHandler() {
        EglBase eglBase = EglBase.create();
        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(ContextUtil.get())
                        .createInitializationOptions()
        );
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();

        DefaultVideoEncoderFactory encoderFactory =
                new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true);

        DefaultVideoDecoderFactory decoderFactory =
                new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());

        JavaAudioDeviceModule adm = JavaAudioDeviceModule.builder(ContextUtil.get())
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule();

        factory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setAudioDeviceModule(adm)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory();
    }

    public static synchronized WebRtcStreamHandler getInstance() {
        if (instance == null) {
            instance = new WebRtcStreamHandler();
        }
        return instance;
    }

    public void pushFrame(I420Image image) {
        pushFrame(image, null);
    }

    public void pushFrame(I420Image image, String streamId) {
        executor.execute(() -> {
            synchronized (streamers) {
                if (streamId != null) {
                    WebRtcStreamer streamer = getStreamById(streamId);
                    if (streamer == null || !streamer.isVideoReady()) {
                        image.close();
                        return;
                    }
                }
            }

            @SuppressLint("UnsafeOptInUsageError")
            I420Image nImg = WebRtcImageUtil.ensureI420(image);
            JavaI420Buffer i420 = JavaI420Buffer.wrap(
                    nImg.width,
                    nImg.height,
                    nImg.dataY,
                    nImg.strideY,
                    nImg.dataU,
                    nImg.strideU,
                    nImg.dataV,
                    nImg.strideV,
                    nImg::close
            );

            VideoFrame frame = new VideoFrame(i420, image.rotation, System.nanoTime());
            synchronized (streamers) {
                if (streamId != null) {
                    WebRtcStreamer streamer = getStreamById(streamId);
                    if (streamer != null && streamer.isVideoReady()) {
                        streamer.pushFrame(frame);
                    }
                } else {
                    for (WebRtcStreamer streamer : streamers) {
                        if (streamer.isVideoReady()) {
                            streamer.pushFrame(frame);
                        }
                    }
                }
            }
            frame.release();
        });
    }

    public boolean shouldPushVideoFrames(String streamId) {
        synchronized (streamers) {
            WebRtcStreamer streamer = getStreamById(streamId);
            return streamer != null && streamer.isVideoReady();
        }
    }

    public void stopAll() {
        synchronized (streamers) {
            for (WebRtcStreamer streamer : streamers) {
                streamer.stop();
            }
            streamers.clear();
        }
    }

    public WebRtcStreamer getStreamById(String streamId) {
        synchronized (streamers) {
            for (WebRtcStreamer item : streamers) {
                if (item.id.equals(streamId)) {
                    return item;
                }
            }
            return null;
        }
    }

    public void removeStream(WebRtcStreamer streamer) {
        synchronized (streamers) {
            streamers.remove(streamer);
        }
    }

    public WebRtcStreamer newStream(MethodChannel channel) {
        synchronized (streamers) {
            WebRtcStreamer stream = new WebRtcStreamer(factory);
            stream.setCallback(new WebRtcStreamer.Callback() {
                @Override
                public void onIceCandidate(IceCandidate candidate, WebRtcStreamer st) {
                    Map<String, Object> data = new HashMap<>();
                    data.put("sdpMid", candidate.sdpMid);
                    data.put("sdpMLineIndex", candidate.sdpMLineIndex);
                    data.put("candidate", candidate.sdp);
                    data.put("streamId", st.id);
                    uiHandler.post(() -> channel.invokeMethod("onWebRtcIceCandidate", data));
                }

                @Override
                public void onDataChannelMessage(String message, WebRtcStreamer st) {
                    uiHandler.post(() -> channel.invokeMethod("onWebRtcDataChannelMessage", new HashMap<String, Object>() {{
                        put("message", message);
                        put("streamId", st.id);
                    }}));
                }

                @Override
                public void onConnected(WebRtcStreamer st) {
                    uiHandler.post(() -> channel.invokeMethod("onWebRtcConnected", new HashMap<String, Object>() {{
                        put("streamId", st.id);
                    }}));
                }

                @Override
                public void onDisconnected(WebRtcStreamer st) {
                    synchronized (streamers) {
                        try {
                            stream.stop();
                            streamers.remove(st);
                            uiHandler.post(() -> channel.invokeMethod("onWebRtcDisconnected", new HashMap<String, Object>() {{
                                put("streamId", st.id);
                            }}));
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            });
            streamers.add(stream);
            return stream;
        }
    }
}
