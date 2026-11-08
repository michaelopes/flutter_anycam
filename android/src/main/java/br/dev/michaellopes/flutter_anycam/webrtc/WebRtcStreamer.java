package br.dev.michaellopes.flutter_anycam.webrtc;

import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import io.flutter.Log;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

import br.dev.michaellopes.flutter_anycam.utils.ContextUtil;

public class WebRtcStreamer {

    public final String id;

    private final org.webrtc.PeerConnectionFactory factory;
    private PeerConnection peerConnection;

    private VideoSource videoSource;
    private VideoTrack videoTrack;
    AudioTrack audioTrack;
    private org.webrtc.AudioSource audioSource;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);

    private DataChannel dataChannel;

    private List<Map<String, Object>> iceServersParam;

    public WebRtcStreamer(org.webrtc.PeerConnectionFactory factory) {
        this.factory = factory;
        this.id = UUID.randomUUID().toString();
    }

    public interface Callback {
        void onIceCandidate(IceCandidate candidate, WebRtcStreamer stream);

        void onDataChannelMessage(String message, WebRtcStreamer stream);

        void onConnected(WebRtcStreamer stream);

        void onDisconnected(WebRtcStreamer stream);
    }

    private Callback callback;

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    private static AudioDeviceInfo pickPreferredCommunicationDevice(
            List<AudioDeviceInfo> devices) {
        if (devices == null || devices.isEmpty()) {
            return null;
        }
        AudioDeviceInfo fallbackSpeaker = null;
        for (AudioDeviceInfo d : devices) {
            int type = d.getType();
            if (type == AudioDeviceInfo.TYPE_USB_HEADSET
                    || type == AudioDeviceInfo.TYPE_USB_DEVICE
                    || type == AudioDeviceInfo.TYPE_DOCK) {
                return d;
            }
            if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                fallbackSpeaker = d;
            }
        }
        return fallbackSpeaker;
    }

    private static boolean hasUsbClassAudioOutput(AudioManager audioManager) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) {
            return false;
        }
        for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int type = d.getType();
            if (type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                return true;
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
                    && type == AudioDeviceInfo.TYPE_USB_DEVICE) {
                return true;
            }
        }
        return false;
    }

    private void configureAudioRouting() {
        AudioManager audioManager =
                (AudioManager) ContextUtil.get().getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

        audioManager.stopBluetoothSco();
        audioManager.setBluetoothScoOn(false);

        boolean routedToUsb = false;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            List<AudioDeviceInfo> available = audioManager.getAvailableCommunicationDevices();
            AudioDeviceInfo preferred = pickPreferredCommunicationDevice(available);
            if (preferred != null) {
                boolean ok = audioManager.setCommunicationDevice(preferred);
                int type = preferred.getType();
                routedToUsb = type == AudioDeviceInfo.TYPE_USB_HEADSET
                        || type == AudioDeviceInfo.TYPE_USB_DEVICE
                        || type == AudioDeviceInfo.TYPE_DOCK;
                Log.d("AUDIO", "setCommunicationDevice type=" + type + " ok=" + ok);
            }
        } else if (hasUsbClassAudioOutput(audioManager)) {
            audioManager.setSpeakerphoneOn(false);
            Log.d("AUDIO", "USB output present (pre-S): speakerphoneOff");
        } else {
            audioManager.setSpeakerphoneOn(true);
        }

        int maxVoice = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVoice, 0);

        if (routedToUsb) {
            int maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0);
        }
    }

    public synchronized void start(List<Map<String, Object>> iceServersParam) {
        if (started.get()) return;
        if (iceServersParam != null) {
            this.iceServersParam = iceServersParam;
        }
        List<PeerConnection.IceServer> iceServers = buildIceServers(this.iceServersParam);
        try {
            allocate(iceServers);
            started.set(true);
        } catch (RuntimeException e) {
            // stop() only releases a started stream; free what was allocated before the failure.
            started.set(true);
            stop();
            throw e;
        }
    }

    private static List<PeerConnection.IceServer> buildIceServers(List<Map<String, Object>> params) {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        if (params != null) {
            for (Map<String, Object> item : params) {
                Object hostname = item != null ? item.get("hostname") : null;
                if (!(hostname instanceof String) || ((String) hostname).isEmpty()) continue;
                PeerConnection.IceServer.Builder iceServer =
                        PeerConnection.IceServer.builder((String) hostname);
                Object password = item.get("password");
                Object username = item.get("username");
                if (password != null || username != null) {
                    iceServer.setPassword(password != null ? password.toString() : "");
                    iceServer.setUsername(username != null ? username.toString() : "");
                }
                iceServers.add(iceServer.createIceServer());
            }
        }
        if (params == null) {
            iceServers.add(
                    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            );
            iceServers.add(
                    PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
            );
        }
        return iceServers;
    }

    private void allocate(List<PeerConnection.IceServer> iceServers) {
        configureAudioRouting();

        videoSource = factory.createVideoSource(false);
        videoTrack = factory.createVideoTrack("video", videoSource);

        MediaConstraints audioConstraints = new MediaConstraints();
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("googAutoGainControl", "true"));
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));

        audioSource = factory.createAudioSource(audioConstraints);
        audioTrack = factory.createAudioTrack("audio", audioSource);

        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(iceServers);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        peerConnection = factory.createPeerConnection(config, new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(IceCandidate candidate) {
                if (callback != null) {
                    callback.onIceCandidate(candidate, WebRtcStreamer.this);
                }
            }

            @Override
            public void onSignalingChange(PeerConnection.SignalingState newState) {
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState newState) {
                Callback cb = callback;
                boolean status = newState == PeerConnection.IceConnectionState.CONNECTED
                        || newState == PeerConnection.IceConnectionState.COMPLETED;
                if (status) {
                    if (connected.compareAndSet(false, true) && cb != null) {
                        cb.onConnected(WebRtcStreamer.this);
                    }
                } else if (newState == PeerConnection.IceConnectionState.FAILED) {
                    // A stream that never connected must still be reported, or Dart waits forever.
                    connected.set(false);
                    if (cb != null) cb.onDisconnected(WebRtcStreamer.this);
                } else if ((newState == PeerConnection.IceConnectionState.DISCONNECTED
                        || newState == PeerConnection.IceConnectionState.CLOSED) && connected.compareAndSet(true, false)) {
                    if (cb != null) cb.onDisconnected(WebRtcStreamer.this);
                }
            }

            @Override
            public void onIceConnectionReceivingChange(boolean receiving) {
            }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState newState) {
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] candidates) {
            }

            @Override
            public void onAddStream(MediaStream stream) {
            }

            @Override
            public void onRemoveStream(MediaStream stream) {
            }

            @Override
            public void onDataChannel(DataChannel dc) {
                dataChannel = dc;
                dc.registerObserver(new DataChannel.Observer() {
                    @Override
                    public void onMessage(DataChannel.Buffer buffer) {
                        ByteBuffer data = buffer.data;
                        byte[] bytes = new byte[data.remaining()];
                        data.get(bytes);
                        String msg = new String(bytes, StandardCharsets.UTF_8);
                        Callback cb = callback;
                        if (cb != null) cb.onDataChannelMessage(msg, WebRtcStreamer.this);
                    }

                    @Override
                    public void onBufferedAmountChange(long previousAmount) {
                    }

                    @Override
                    public void onStateChange() {
                    }
                });
            }

            @Override
            public void onRenegotiationNeeded() {
            }

            @Override
            public void onAddTrack(RtpReceiver receiver, MediaStream[] mediaStreams) {
            }
        });

        List<String> streamIds = new ArrayList<>();
        streamIds.add("stream1");

        if (peerConnection == null) {
            throw new IllegalStateException("createPeerConnection returned null");
        }
        peerConnection.addTrack(videoTrack, streamIds);
        peerConnection.addTrack(audioTrack, streamIds);
    }

    /** Never call from a PeerConnection observer callback (dispose blocks on that thread). */
    public synchronized void stop() {
        if (!started.compareAndSet(true, false)) return;
        connected.set(false);

        DataChannel dc = dataChannel;
        dataChannel = null;
        if (dc != null) {
            try {
                dc.close();
                dc.dispose();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        PeerConnection pc = peerConnection;
        peerConnection = null;
        if (pc != null) {
            try {
                pc.dispose();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (videoTrack != null) {
            try {
                videoTrack.dispose();
            } catch (Exception e) {
                e.printStackTrace();
            }
            videoTrack = null;
        }
        if (videoSource != null) {
            try {
                videoSource.dispose();
            } catch (Exception e) {
                e.printStackTrace();
            }
            videoSource = null;
        }
        if (audioTrack != null) {
            try {
                audioTrack.dispose();
            } catch (Exception e) {
                e.printStackTrace();
            }
            audioTrack = null;
        }
        if (audioSource != null) {
            try {
                audioSource.dispose();
            } catch (Exception e) {
                e.printStackTrace();
            }
            audioSource = null;
        }
    }

    public interface AnswerCallback {
        void onAnswer(String sdp);

        void onError(String message);
    }

    /** {@code callback} receives exactly one of answer or error. */
    public void setOffer(String offerSdp, List<Map<String, Object>> iceServers, AnswerCallback callback) {
        final AtomicBoolean replied = new AtomicBoolean(false);
        final AnswerCallback once = new AnswerCallback() {
            @Override
            public void onAnswer(String sdp) {
                if (replied.compareAndSet(false, true)) callback.onAnswer(sdp);
            }

            @Override
            public void onError(String message) {
                if (replied.compareAndSet(false, true)) callback.onError(message);
            }
        };

        final PeerConnection pc;
        try {
            synchronized (this) {
                if (!started.get()) {
                    start(iceServers);
                }
                pc = peerConnection;
            }
        } catch (RuntimeException e) {
            once.onError("start failed: " + e.getMessage());
            return;
        }
        if (pc == null || offerSdp == null) {
            once.onError(pc == null ? "PeerConnection unavailable" : "Offer SDP is null");
            return;
        }

        SessionDescription offer = new SessionDescription(SessionDescription.Type.OFFER, offerSdp);
        try {
            setRemoteOffer(pc, offer, once);
        } catch (RuntimeException e) {
            // Stream stopped concurrently: the PeerConnection is disposed.
            once.onError("setRemoteDescription failed: " + e.getMessage());
        }
    }

    private static void setRemoteOffer(PeerConnection pc, SessionDescription offer, AnswerCallback once) {
        pc.setRemoteDescription(new SdpAdapter("setRemoteDescription", once) {
            @Override
            public void onSetSuccess() {
                MediaConstraints constraints = new MediaConstraints();
                constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
                constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));
                try {
                    pc.createAnswer(new SdpAdapter("createAnswer", once) {
                        @Override
                        public void onCreateSuccess(SessionDescription answer) {
                            try {
                                pc.setLocalDescription(new SdpAdapter("setLocalDescription", once) {
                                    @Override
                                    public void onSetSuccess() {
                                        once.onAnswer(answer.description);
                                    }
                                }, answer);
                            } catch (Exception e) {
                                // Stream stopped while negotiating: the PeerConnection is disposed.
                                once.onError("setLocalDescription failed: " + e.getMessage());
                            }
                        }
                    }, constraints);
                } catch (Exception e) {
                    once.onError("createAnswer failed: " + e.getMessage());
                }
            }
        }, offer);
    }

    private static class SdpAdapter implements SdpObserver {
        private final String step;
        private final AnswerCallback callback;

        SdpAdapter(String step, AnswerCallback callback) {
            this.step = step;
            this.callback = callback;
        }

        @Override
        public void onCreateSuccess(SessionDescription sdp) {
        }

        @Override
        public void onSetSuccess() {
        }

        @Override
        public void onCreateFailure(String error) {
            callback.onError(step + " failed: " + error);
        }

        @Override
        public void onSetFailure(String error) {
            callback.onError(step + " failed: " + error);
        }
    }

    public synchronized void addIceCandidate(String sdpMid, int sdpMLineIndex, String candidate) {
        if (peerConnection != null && candidate != null) {
            IceCandidate iceCandidate = new IceCandidate(sdpMid, sdpMLineIndex, candidate);
            peerConnection.addIceCandidate(iceCandidate);
        }
    }

    public synchronized void sendDataMessage(String message) {
        if (started.get() && dataChannel != null && message != null) {
            ByteBuffer buffer = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));
            dataChannel.send(new DataChannel.Buffer(buffer, false));
            buffer.clear();
        }
    }

    public synchronized void pushFrame(VideoFrame frame) {
        if (started.get() && connected.get() && videoSource != null) {
            videoSource.getCapturerObserver().onFrameCaptured(frame);
        }
    }

    public boolean isVideoReady() {
        return started.get() && connected.get();
    }
}
