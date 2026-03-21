package br.dev.michaellopes.flutter_anycam.stream;

import android.annotation.SuppressLint;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageProxy;

import java.util.ArrayList;
import java.util.List;

public class CameraStreamManager {
    private static CameraStreamManager instance;

    private final List<CameraRawStream> streams = new ArrayList<>();

    private CameraStreamManager() {};

    public static CameraStreamManager getInstance() {
        if(instance == null) {
            instance = new CameraStreamManager();
        }
        return instance;
    }

    private boolean existsCameraStream(String cameraId) {
        return  getCameraStream(cameraId) != null;
    }

    private CameraRawStream getCameraStream(String cameraId) {
        for (CameraRawStream item: streams) {
            if(item.getCameraId().equals(cameraId)) {
                return item;
            }
        }
        return null;
    }

    public boolean add(String cameraId, int fps) {
        if(!existsCameraStream(cameraId)) {
            streams.add(new CameraRawStream(cameraId, fps));
            return true;
        }
        return  false;
    }

    public void dispose(String cameraId) {
        CameraRawStream item = getCameraStream(cameraId);
        if(item != null) {
            streams.remove(item);
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    public synchronized void sendFrame( @NonNull String cameraId, @NonNull ImageProxy image, Integer customRotationDegrees) {
        CameraRawStream item = getCameraStream(cameraId);
        if(item != null) {
            item.sendFrame(image, customRotationDegrees);
        }
    }

}
