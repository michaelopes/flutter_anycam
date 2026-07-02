package br.dev.michaellopes.flutter_anycam.stream;

import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.ImageProxy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CameraStreamManager {
    private static CameraStreamManager instance;

    private final List<CameraRawStream> streams = new ArrayList<>();
    @Nullable
    private String activeWebRtcFeedCameraId = null;

    private CameraStreamManager() {
    }

    public static CameraStreamManager getInstance() {
        if (instance == null) {
            instance = new CameraStreamManager();
        }
        return instance;
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

    public boolean add(String cameraId, int fps) {
        CameraRawStream stream = getOrCreateStream(cameraId, fps);
        stream.setDeliverToFlutter(true);
        return true;
    }

    public boolean addWebRtcFeed(
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
        return true;
    }

    public void removeWebRtcFeed(String cameraId) {
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
    }

    public void dispose(String cameraId) {
        CameraRawStream item = getCameraStream(cameraId);
        if (item != null) {
            if (item.isWebRtcFeedEnabled()) {
                activeWebRtcFeedCameraId = null;
            }
            streams.remove(item);
        }
    }

    public synchronized void sendFrame(
            @NonNull String cameraId,
            @NonNull ImageProxy image,
            Integer customRotationDegrees
    ) {
        CameraRawStream item = getCameraStream(cameraId);
        if (item != null && item.hasActiveSink()) {
            item.sendFrame(image, customRotationDegrees);
        }
    }
}
