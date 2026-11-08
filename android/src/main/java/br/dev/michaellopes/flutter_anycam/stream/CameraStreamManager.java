package br.dev.michaellopes.flutter_anycam.stream;

import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CameraStreamManager {
    private static CameraStreamManager instance;

    private final List<CameraRawStream> streams = new ArrayList<>();
    private final List<SinkChangeListener> sinkListeners = new ArrayList<>();
    @Nullable
    private String activeWebRtcFeedCameraId = null;

    private CameraStreamManager() {
    }

    public static synchronized CameraStreamManager getInstance() {
        if (instance == null) {
            instance = new CameraStreamManager();
        }
        return instance;
    }

    /**
     * Notified when raw Flutter / WebRTC sink registration changes for a camera.
     * Used by USB to enable/disable the expensive NV21 frame callback dynamically.
     */
    public interface SinkChangeListener {
        void onSinkChanged(String cameraId);
    }

    public synchronized void addSinkChangeListener(SinkChangeListener listener) {
        if (listener != null && !sinkListeners.contains(listener)) {
            sinkListeners.add(listener);
        }
    }

    public synchronized void removeSinkChangeListener(SinkChangeListener listener) {
        sinkListeners.remove(listener);
    }

    private void notifySinkChanged(String cameraId) {
        // Snapshot under lock; callers already hold the monitor via synchronized methods.
        List<SinkChangeListener> copy = new ArrayList<>(sinkListeners);
        for (SinkChangeListener listener : copy) {
            try {
                listener.onSinkChanged(cameraId);
            } catch (Throwable ignored) {
            }
        }
    }

    private CameraRawStream getCameraStream(String cameraId) {
        for (CameraRawStream item : streams) {
            if (item.getCameraId().equals(cameraId)) {
                return item;
            }
        }
        return null;
    }

    private CameraRawStream getOrCreateStream(String cameraId, int fps) {
        CameraRawStream existing = getCameraStream(cameraId);
        if (existing != null) {
            return existing;
        }
        CameraRawStream stream = new CameraRawStream(cameraId, fps);
        streams.add(stream);
        return stream;
    }

    @Nullable
    public static Size parseStreamSizeFromArgs(Map<?, ?> args) {
        Object streamSize = args.get("streamSize");
        if (!(streamSize instanceof Map)) {
            return null;
        }
        Map<?, ?> sizeMap = (Map<?, ?>) streamSize;
        Object width = sizeMap.get("width");
        Object height = sizeMap.get("height");
        if (!(width instanceof Integer) || !(height instanceof Integer)) {
            return null;
        }
        int w = (Integer) width;
        int h = (Integer) height;
        if (w <= 0 || h <= 0) {
            return null;
        }
        return new Size(w, h);
    }

    public synchronized boolean add(String cameraId, int fps) {
        CameraRawStream stream = getOrCreateStream(cameraId, fps);
        stream.setDeliverToFlutter(true);
        notifySinkChanged(cameraId);
        return true;
    }

    /** True when raw Flutter and/or WebRTC sinks are registered for this camera. */
    public synchronized boolean hasActiveSink(String cameraId) {
        CameraRawStream item = getCameraStream(cameraId);
        return item != null && item.hasActiveSink();
    }

    /** True when a registered sink would accept a frame now (does not consume its FPS slot). */
    public boolean isFrameDue(String cameraId) {
        CameraRawStream item = activeStream(cameraId);
        return item != null && item.isFrameDue();
    }

    public synchronized boolean addWebRtcFeed(
            String cameraId,
            int fps,
            boolean alsoDeliverToFlutter,
            @Nullable Size streamSize
    ) {
        if (activeWebRtcFeedCameraId != null) {
            return false;
        }

        CameraRawStream stream = getOrCreateStream(cameraId, fps);
        stream.setWebRtcFeedEnabled(true);
        activeWebRtcFeedCameraId = cameraId;

        if (streamSize != null) {
            stream.setWebRtcStreamSize(streamSize.getWidth(), streamSize.getHeight());
        } else {
            stream.clearWebRtcStreamSize();
        }
        if (alsoDeliverToFlutter) {
            stream.setDeliverToFlutter(true);
        }
        notifySinkChanged(cameraId);
        return true;
    }

    public synchronized void removeWebRtcFeed(String cameraId) {
        CameraRawStream stream = getCameraStream(cameraId);
        if (stream != null) {
            stream.clearWebRtcFeed();
            stream.clearWebRtcStreamSize();
            if (!stream.isDeliverToFlutter()) {
                streams.remove(stream);
            }
        }
        if (cameraId.equals(activeWebRtcFeedCameraId)) {
            activeWebRtcFeedCameraId = null;
        }
        notifySinkChanged(cameraId);
    }

    public synchronized void dispose(String cameraId) {
        CameraRawStream item = getCameraStream(cameraId);
        if (item != null) {
            if (item.isWebRtcFeedEnabled()) {
                activeWebRtcFeedCameraId = null;
            }
            streams.remove(item);
            notifySinkChanged(cameraId);
        }
    }

    /**
     * Frame paths only look the stream up under the lock; conversion runs on the caller's
     * thread without it, so one camera's conversion never stalls another camera's capture
     * thread or the main thread registering sinks. A stream removed meanwhile finishes at
     * most the frame it already started.
     */
    @Nullable
    private synchronized CameraRawStream activeStream(String cameraId) {
        CameraRawStream item = getCameraStream(cameraId);
        return item != null && item.hasActiveSink() ? item : null;
    }

    public void sendFrame(
            @NonNull String cameraId,
            @NonNull ImageProxy image,
            Integer customRotationDegrees
    ) {
        CameraRawStream item = activeStream(cameraId);
        if (item != null) {
            item.sendFrame(image, customRotationDegrees);
        }
    }

    /**
     * Camera2 path: {@link android.media.Image} is closed when the capture callback returns,
     * so sinks must copy plane bytes synchronously inside this call.
     */
    public void sendFrame(
            @NonNull String cameraId,
            @NonNull android.media.Image image,
            int rotationDegrees,
            Integer customRotationDegrees
    ) {
        CameraRawStream item = activeStream(cameraId);
        if (item != null) {
            item.sendFrame(image, rotationDegrees, customRotationDegrees);
        }
    }

    /**
     * Tightly packed NV21 path (RTSP GPU readback, USB). Sinks copy what they need
     * synchronously, so the caller may reuse {@code nv21} after this returns.
     */
    public void sendNv21Frame(
            @NonNull String cameraId,
            @NonNull ByteBuffer nv21,
            int width,
            int height,
            int rotationDegrees,
            Integer customRotationDegrees
    ) {
        CameraRawStream item = activeStream(cameraId);
        if (item != null) {
            item.sendNv21Frame(nv21, width, height, rotationDegrees, customRotationDegrees);
        }
    }
}
