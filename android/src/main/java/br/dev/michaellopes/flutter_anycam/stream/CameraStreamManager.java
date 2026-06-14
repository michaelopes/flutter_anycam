package br.dev.michaellopes.flutter_anycam.stream;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageProxy;

import java.util.ArrayList;
import java.util.List;

public class CameraStreamManager {
    private static CameraStreamManager instance;

    private final List<CameraRawStream> streams = new ArrayList<>();

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

    public boolean add(String cameraId, int fps) {
        CameraRawStream stream = getOrCreateStream(cameraId, fps);
        stream.setDeliverToFlutter(true);
        return true;
    }

    public boolean addWebRtcFeed(
            String cameraId,
            int fps,
            String streamId,
            boolean alsoDeliverToFlutter
    ) {
        CameraRawStream stream = getOrCreateStream(cameraId, fps);
        stream.setWebRtcStreamId(streamId);
        if (alsoDeliverToFlutter) {
            stream.setDeliverToFlutter(true);
        }
        return true;
    }

    public void removeWebRtcFeed(String cameraId) {
        CameraRawStream stream = getCameraStream(cameraId);
        if (stream != null) {
            stream.setWebRtcStreamId(null);
            if (!stream.isDeliverToFlutter()) {
                streams.remove(stream);
            }
        }
    }

    public void dispose(String cameraId) {
        CameraRawStream item = getCameraStream(cameraId);
        if (item != null) {
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
