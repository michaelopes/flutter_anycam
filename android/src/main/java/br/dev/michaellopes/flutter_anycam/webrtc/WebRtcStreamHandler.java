package br.dev.michaellopes.flutter_anycam.webrtc;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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

/**
 * Owns every WebRTC stream. Frames are encoded on {@link #frameExecutor}; signaling and
 * teardown run on {@link #signalingExecutor}, never inside a PeerConnection observer callback
 * (disposing a PeerConnection from its own signaling thread deadlocks).
 */
public class WebRtcStreamHandler {

    private static final String TAG = "WebRtcStreamHandler";

    private final ExecutorService frameExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AnycamWebRtcFrames");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService signalingExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AnycamWebRtcSignaling");
        t.setDaemon(true);
        return t;
    });
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

    /** The handler if WebRTC was ever used; never initializes the native stack. */
    public static synchronized WebRtcStreamHandler peekInstance() {
        return instance;
    }

    public void pushFrame(I420Image image) {
        pushFrame(image, null);
    }

    public void pushFrame(I420Image image, String streamId) {
        pushFrame(image, streamId, null);
    }

    /**
     * {@code onDone} runs exactly once when the frame has been handed to the streamers (or
     * dropped), so producers can bound how many frames wait on {@link #frameExecutor}.
     */
    public void pushFrame(I420Image image, String streamId, Runnable onDone) {
        try {
            frameExecutor.execute(() -> {
                try {
                    pushFrameNow(image, streamId);
                } catch (Throwable t) {
                    // An uncaught exception here would kill the process.
                    Log.e(TAG, "pushFrame failed: " + t.getMessage());
                    image.close();
                } finally {
                    if (onDone != null) onDone.run();
                }
            });
        } catch (RuntimeException e) {
            image.close();
            if (onDone != null) onDone.run();
        }
    }

    private void pushFrameNow(I420Image image, String streamId) {
        if (streamId != null ? !shouldPushVideoFrames(streamId) : !hasAnyVideoReadyStream()) {
            image.close();
            return;
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
        try {
            for (WebRtcStreamer streamer : snapshot()) {
                if ((streamId == null || streamer.id.equals(streamId)) && streamer.isVideoReady()) {
                    streamer.pushFrame(frame);
                }
            }
        } finally {
            frame.release();
        }
    }

    private List<WebRtcStreamer> snapshot() {
        synchronized (streamers) {
            return new ArrayList<>(streamers);
        }
    }

    public boolean shouldPushVideoFrames(String streamId) {
        WebRtcStreamer streamer = getStreamById(streamId);
        return streamer != null && streamer.isVideoReady();
    }

    public boolean hasAnyVideoReadyStream() {
        for (WebRtcStreamer streamer : snapshot()) {
            if (streamer.isVideoReady()) {
                return true;
            }
        }
        return false;
    }

    public void stopAll() {
        List<WebRtcStreamer> all;
        synchronized (streamers) {
            all = new ArrayList<>(streamers);
            streamers.clear();
        }
        runSignaling(() -> {
            for (WebRtcStreamer streamer : all) {
                safeStop(streamer);
            }
            restoreAudioIfIdle();
        });
    }

    /** Removes and stops one stream off the caller's thread. */
    public void stopStream(String streamId) {
        WebRtcStreamer streamer = getStreamById(streamId);
        if (streamer == null) return;
        removeStream(streamer);
        runSignaling(() -> {
            safeStop(streamer);
            restoreAudioIfIdle();
        });
    }

    /** Returns false when the task was rejected and will never run. */
    public boolean runSignaling(Runnable task) {
        try {
            signalingExecutor.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    Log.e(TAG, "signaling task failed: " + t.getMessage());
                }
            });
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "signaling executor rejected task: " + e.getMessage());
            return false;
        }
    }

    private static void safeStop(WebRtcStreamer streamer) {
        try {
            streamer.stop();
        } catch (Throwable t) {
            Log.e(TAG, "stop failed: " + t.getMessage());
        }
    }

    private void restoreAudioIfIdle() {
        synchronized (streamers) {
            if (!streamers.isEmpty()) return;
        }
        try {
            AudioManager audioManager =
                    (AudioManager) ContextUtil.get().getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice();
            }
            audioManager.setMode(AudioManager.MODE_NORMAL);
        } catch (Exception e) {
            Log.w(TAG, "restore audio failed: " + e.getMessage());
        }
    }

    public WebRtcStreamer getStreamById(String streamId) {
        if (streamId == null) return null;
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

    private void invoke(MethodChannel channel, String method, Map<String, Object> data) {
        uiHandler.post(() -> {
            try {
                channel.invokeMethod(method, data);
            } catch (Exception e) {
                Log.w(TAG, method + " not delivered: " + e.getMessage());
            }
        });
    }

    public WebRtcStreamer newStream(MethodChannel channel) {
        WebRtcStreamer stream = new WebRtcStreamer(factory);
        stream.setCallback(new WebRtcStreamer.Callback() {
            @Override
            public void onIceCandidate(IceCandidate candidate, WebRtcStreamer st) {
                Map<String, Object> data = new HashMap<>();
                data.put("sdpMid", candidate.sdpMid);
                data.put("sdpMLineIndex", candidate.sdpMLineIndex);
                data.put("candidate", candidate.sdp);
                data.put("streamId", st.id);
                invoke(channel, "onWebRtcIceCandidate", data);
            }

            @Override
            public void onDataChannelMessage(String message, WebRtcStreamer st) {
                Map<String, Object> data = new HashMap<>();
                data.put("message", message);
                data.put("streamId", st.id);
                invoke(channel, "onWebRtcDataChannelMessage", data);
            }

            @Override
            public void onConnected(WebRtcStreamer st) {
                Map<String, Object> data = new HashMap<>();
                data.put("streamId", st.id);
                invoke(channel, "onWebRtcConnected", data);
            }

            @Override
            public void onDisconnected(WebRtcStreamer st) {
                // Runs on the WebRTC signaling thread: hand the teardown off.
                boolean removed;
                synchronized (streamers) {
                    removed = streamers.remove(st);
                }
                if (!removed) return;
                runSignaling(() -> {
                    safeStop(st);
                    restoreAudioIfIdle();
                });
                Map<String, Object> data = new HashMap<>();
                data.put("streamId", st.id);
                invoke(channel, "onWebRtcDisconnected", data);
            }
        });
        synchronized (streamers) {
            streamers.add(stream);
        }
        return stream;
    }
}
