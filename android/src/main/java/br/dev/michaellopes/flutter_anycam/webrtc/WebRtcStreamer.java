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

    public void start(List<Map<String, Object>> iceServersParam) {
        if (iceServersParam != null) {
            this.iceServersParam = iceServersParam;
        }
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

        org.webrtc.AudioSource audioSource = factory.createAudioSource(audioConstraints);
        audioTrack = factory.createAudioTrack("audio", audioSource);

        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        if (iceServersParam != null) {
            for (Map<String, Object> item : iceServersParam) {
                PeerConnection.IceServer.Builder iceServer =
                        PeerConnection.IceServer.builder((String) item.get("hostname"));
                if (item.get("password") != null || item.get("username") != null) {
                    iceServer.setPassword((String) item.get("password"));
                    iceServer.setUsername((String) item.get("username"));
                }
                iceServers.add(iceServer.createIceServer());
            }
        } else {
            iceServers.add(
                    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            );
            iceServers.add(
                    PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
            );
        }

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
                boolean status = newState == PeerConnection.IceConnectionState.CONNECTED
                        || newState == PeerConnection.IceConnectionState.COMPLETED;
                if (status) {
                    connected.set(true);
                    callback.onConnected(WebRtcStreamer.this);
                } else if ((newState == PeerConnection.IceConnectionState.DISCONNECTED
                        || newState == PeerConnection.IceConnectionState.CLOSED) && connected.get()) {
                    connected.set(false);
                    callback.onDisconnected(WebRtcStreamer.this);
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
                        callback.onDataChannelMessage(msg, WebRtcStreamer.this);
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

        peerConnection.addTrack(videoTrack, streamIds);
        peerConnection.addTrack(audioTrack, streamIds);
        started.set(true);
    }

    public void stop() {
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
    }

    public interface AnswerCallback {
        void onAnswer(String sdp);
    }

    public void setOffer(String offerSdp, List<Map<String, Object>> iceServers, AnswerCallback callback) {
        if (!started.get()) {
            start(iceServers);
        }

        SessionDescription offer = new SessionDescription(SessionDescription.Type.OFFER, offerSdp);

        peerConnection.setRemoteDescription(new SdpObserver() {
            @Override
            public void onSetSuccess() {
                MediaConstraints constraints = new MediaConstraints();
                constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
                constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));

                peerConnection.createAnswer(new SdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription answer) {
                        peerConnection.setLocalDescription(new SdpObserver() {
                            @Override
                            public void onSetSuccess() {
                                callback.onAnswer(answer.description);
                            }

                            @Override
                            public void onCreateSuccess(SessionDescription sdp) {
                            }

                            @Override
                            public void onCreateFailure(String error) {
                            }

                            @Override
                            public void onSetFailure(String error) {
                            }
                        }, answer);
                    }

                    @Override
                    public void onSetSuccess() {
                    }

                    @Override
                    public void onCreateFailure(String error) {
                    }

                    @Override
                    public void onSetFailure(String error) {
                    }
                }, constraints);
            }

            @Override
            public void onCreateSuccess(SessionDescription sdp) {
            }

            @Override
            public void onCreateFailure(String error) {
            }

            @Override
            public void onSetFailure(String error) {
            }
        }, offer);
    }

    public void addIceCandidate(String sdpMid, int sdpMLineIndex, String candidate) {
        if (peerConnection != null) {
            IceCandidate iceCandidate = new IceCandidate(sdpMid, sdpMLineIndex, candidate);
            peerConnection.addIceCandidate(iceCandidate);
        }
    }

    public void sendDataMessage(String message) {
        if (started.get() && dataChannel != null) {
            ByteBuffer buffer = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));
            dataChannel.send(new DataChannel.Buffer(buffer, false));
            buffer.clear();
        }
    }

    public void pushFrame(VideoFrame frame) {
        if (started.get() && connected.get()) {
            videoSource.getCapturerObserver().onFrameCaptured(frame);
        }
    }

    public boolean isVideoReady() {
        return started.get() && connected.get();
    }
}
