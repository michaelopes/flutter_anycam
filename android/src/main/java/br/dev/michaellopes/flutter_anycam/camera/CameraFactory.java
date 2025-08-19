package br.dev.michaellopes.flutter_anycam.camera;

import java.util.Map;

import br.dev.michaellopes.flutter_anycam.model.ViewCameraSelector;

public class CameraFactory {

    private static final CameraFactory instance = new CameraFactory();

    private CameraFactory() {}

    public static CameraFactory getInstance() {
        return instance;
    }

    public BaseCamera getCamera(int vId, Map<String, Object> params) {
        synchronized (instance) {
            Map<String, Object> map = (Map<String, Object>) params.get("cameraSelector");
            ViewCameraSelector cameraSelector = ViewCameraSelector.fromMap(map);
            switch (cameraSelector.getLensFacing()) {
                case "usb":
                    return new UsbCamera(vId, params);
                case "rtsp":
                    return new RTSPCamera(vId, params);
                case "back":
                case "front":
                default:
                    return new DeviceCamera(vId, params);
            }
        }
    }
}
