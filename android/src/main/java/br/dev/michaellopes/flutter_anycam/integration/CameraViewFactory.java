package br.dev.michaellopes.flutter_anycam.integration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import br.dev.michaellopes.flutter_anycam.camera.BaseCamera;
import br.dev.michaellopes.flutter_anycam.camera.CameraBridge;
import br.dev.michaellopes.flutter_anycam.camera.DeviceCamera;
import br.dev.michaellopes.flutter_anycam.camera.RTSPCamera;
import br.dev.michaellopes.flutter_anycam.camera.UsbCamera;
import br.dev.michaellopes.flutter_anycam.model.ViewCameraSelector;
import io.flutter.view.TextureRegistry;

public class CameraViewFactory {

    private static final CameraViewFactory instance = new CameraViewFactory();

    private TextureRegistry textureRegistry;

    private ViewCameraSelector cameraSelector;

    private List<BaseCamera> cameras = new ArrayList<>();


    public static CameraViewFactory getInstance() {

        return instance;
    }

    public void init(TextureRegistry textureRegistry) {
        this.textureRegistry = textureRegistry;
    }

    public void broadcastPermissionGranted() {
        for (BaseCamera camera: cameras) {
            if (!camera.isRtsp()) {
                camera.run();
            }
        }
    }


    public  void  disposeAll() {
        for (BaseCamera camera: cameras) {
            camera.dispose();
            cameras.remove(camera);
        }
    }


    public synchronized Long createView(HashMap<String, Object> args) {
        if (args.get("cameraSelector") != null && args.get("viewId") != null) {
            final int viewId = (int) args.get("viewId");
            Map<String, Object> map = (Map<String, Object>) args.get("cameraSelector");
            cameraSelector = ViewCameraSelector.fromMap(map);
            Object[] filter = cameras.stream().filter(item -> item.getCameraId().equals(cameraSelector.getId())).toArray();
            if (filter.length >= 1) {
                BaseCamera camera = (BaseCamera) filter[0];
                long textureId = camera.getTextureId();

                camera.addBridge(new CameraBridge(viewId));
                return textureId;
            } else {
                BaseCamera camera = createCamera(args);
                camera.addBridge(new CameraBridge(viewId));
                camera.run();

                cameras.add(camera);
                return camera.getTextureId();
            }

        }
        return null;
    }

    public void disposeView(HashMap<String, Object> args) {
        if (args.get("viewId") != null) {
            final int viewId = (int) args.get("viewId");
            Object[] filter = cameras.stream().filter(item -> item.containsBridgeByViewId(viewId)).toArray();
            if (filter.length >= 1) {
                BaseCamera camera = (BaseCamera) filter[0];
                final CameraBridge bridge = camera.getBridgeByViewId(viewId);
                camera.removeBridge(bridge);
                if (!camera.existsBridge()) {
                    camera.dispose();
                    cameras.remove(camera);
                }
            }
        }
    }

    private BaseCamera createCamera(Map<String, Object> args) {
        synchronized (instance) {
            TextureRegistry.SurfaceTextureEntry texture = textureRegistry.createSurfaceTexture();

            switch (cameraSelector.getLensFacing()) {
                case "usb":
                    return new UsbCamera(texture, args);
                case "rtsp":
                    return new RTSPCamera(texture, args);
                case "back":
                case "front":
                default:
                    return new DeviceCamera(texture, args);
            }
        }
    }

}
