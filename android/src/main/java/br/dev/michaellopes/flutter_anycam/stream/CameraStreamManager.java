package br.dev.michaellopes.flutter_anycam.stream;

import android.annotation.SuppressLint;
import android.media.Image;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageProxy;

import java.util.ArrayList;
import java.util.List;

import br.dev.michaellopes.flutter_anycam.integration.FlutterEventChannel;
import br.dev.michaellopes.flutter_anycam.utils.YuvUtil;

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
    public synchronized byte[] sendFrame( @NonNull String cameraId, @NonNull ImageProxy image) {
        CameraRawStream item = getCameraStream(cameraId);
        if(item != null) {
            try {
                Image img = image.getImage();
                byte[] nv21 = YuvUtil.yuv420ToNv21(img).get();
                item.sendFrame(nv21, image.getWidth(), image.getHeight(), image.getImageInfo().getRotationDegrees());
                return nv21;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return  null;
    }

}
