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

    private TextureRegistry textureRegistry;

    private ViewCameraSelector cameraSelector;

    private final List<BaseCamera> cameras = new ArrayList<>();

    private static final CameraViewFactory instance = new CameraViewFactory();

    public static CameraViewFactory getInstance() {
        return instance;
    }

    public void init(TextureRegistry textureRegistry) {
        this.textureRegistry = textureRegistry;
    }

    public synchronized void broadcastPermissionGranted() {
        for (BaseCamera camera : cameras) {
            if (!camera.isRtsp()) {
                camera.run();
            }
        }
    }

    public synchronized void disposeAll() {
        List<BaseCamera> snapshot = new ArrayList<>(cameras);
        cameras.clear();
        for (BaseCamera camera : snapshot) {
            try {
                camera.dispose();
            } catch (Exception ignored) {
            }
        }
    }

    public synchronized BaseCamera getCameraById(String id) {
        if (id == null) {
            return null;
        }
        for (BaseCamera camera : cameras) {
            if (id.equals(camera.getCameraId())) {
                return camera;
            }
        }
        return null;
    }

    public synchronized Long createView(HashMap<String, Object> args) {
        if (args.get("cameraSelector") != null && args.get("viewId") != null) {
            final int viewId = (int) args.get("viewId");
            Map<String, Object> map = (Map<String, Object>) args.get("cameraSelector");
            cameraSelector = ViewCameraSelector.fromMap(map);
            BaseCamera existing = getCameraByIdUnlocked(cameraSelector.getId());
            if (existing != null) {
                long textureId = existing.getTextureId();
                existing.addBridge(new CameraBridge(viewId));
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

    public synchronized void disposeView(HashMap<String, Object> args) {
        if (args.get("viewId") != null) {
            final int viewId = (int) args.get("viewId");
            BaseCamera camera = null;
            for (BaseCamera item : cameras) {
                if (item.containsBridgeByViewId(viewId)) {
                    camera = item;
                    break;
                }
            }
            if (camera != null) {
                final CameraBridge bridge = camera.getBridgeByViewId(viewId);
                camera.removeBridge(bridge);
                if (!camera.existsBridge()) {
                    camera.dispose();
                    cameras.remove(camera);
                }
            }
        }
    }

    private BaseCamera getCameraByIdUnlocked(String id) {
        for (BaseCamera camera : cameras) {
            if (camera.getCameraId().equals(id)) {
                return camera;
            }
        }
        return null;
    }

    private BaseCamera createCamera(Map<String, Object> args) {
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
