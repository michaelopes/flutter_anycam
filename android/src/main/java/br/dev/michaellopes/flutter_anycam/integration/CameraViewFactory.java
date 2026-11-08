package br.dev.michaellopes.flutter_anycam.integration;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import br.dev.michaellopes.flutter_anycam.camera.BaseCamera;
import br.dev.michaellopes.flutter_anycam.camera.CameraBridge;
import br.dev.michaellopes.flutter_anycam.camera.DeviceCamera;
import br.dev.michaellopes.flutter_anycam.camera.RTSPCamera;
import br.dev.michaellopes.flutter_anycam.camera.UsbCamera;
import br.dev.michaellopes.flutter_anycam.model.ViewCameraSelector;
import io.flutter.view.TextureRegistry;

/** Main-thread owner of every widget camera; one native camera per camera id. */
public class CameraViewFactory {

    private static final String TAG = "CameraViewFactory";

    private TextureRegistry textureRegistry;

    private final List<BaseCamera> cameras = new ArrayList<>();

    private static final CameraViewFactory instance = new CameraViewFactory();

    public static CameraViewFactory getInstance() {
        return instance;
    }

    public synchronized void init(TextureRegistry textureRegistry) {
        this.textureRegistry = textureRegistry;
    }

    public synchronized TextureRegistry getTextureRegistry() {
        return textureRegistry;
    }

    public synchronized void broadcastPermissionGranted() {
        for (BaseCamera camera : new ArrayList<>(cameras)) {
            if (!camera.isRtsp()) {
                try {
                    camera.run();
                } catch (Exception e) {
                    Log.e(TAG, "run after permission failed for " + camera.getCameraId(), e);
                    camera.onFailed(e.getMessage());
                }
            }
        }
    }

    public synchronized void disposeAll() {
        List<BaseCamera> snapshot = new ArrayList<>(cameras);
        cameras.clear();
        for (BaseCamera camera : snapshot) {
            try {
                camera.dispose();
            } catch (Exception e) {
                Log.w(TAG, "dispose failed for " + camera.getCameraId() + ": " + e.getMessage());
            }
        }
    }

    public synchronized BaseCamera getCameraById(String id) {
        return getCameraByIdUnlocked(id);
    }

    /**
     * Returns the texture id, or throws when the camera cannot be created: the caller reports
     * the error to Dart, which shows the failure state instead of an endless loading.
     */
    public synchronized Long createView(Map<String, Object> args) {
        Object viewIdArg = args.get("viewId");
        Object selectorArg = args.get("cameraSelector");
        if (!(viewIdArg instanceof Number) || !(selectorArg instanceof Map)) {
            throw new IllegalArgumentException("createView requires viewId and cameraSelector");
        }
        if (textureRegistry == null) {
            throw new IllegalStateException("Plugin is not attached to a Flutter engine");
        }
        final int viewId = ((Number) viewIdArg).intValue();
        @SuppressWarnings("unchecked")
        ViewCameraSelector cameraSelector = ViewCameraSelector.fromMap((Map<String, Object>) selectorArg);
        if (cameraSelector.getId() == null) {
            throw new IllegalArgumentException("cameraSelector.id is required");
        }

        // A view id moving to another camera must leave the previous one.
        detachViewUnlocked(viewId, cameraSelector.getId());

        BaseCamera existing = getCameraByIdUnlocked(cameraSelector.getId());
        if (existing != null) {
            existing.addBridge(new CameraBridge(viewId));
            return existing.getTextureId();
        }

        TextureRegistry.SurfaceTextureEntry texture = textureRegistry.createSurfaceTexture();
        BaseCamera camera;
        try {
            camera = createCamera(cameraSelector, texture, args);
        } catch (RuntimeException e) {
            texture.release();
            throw e;
        }
        camera.addBridge(new CameraBridge(viewId));
        cameras.add(camera);
        try {
            camera.run();
        } catch (RuntimeException e) {
            Log.e(TAG, "camera start failed for " + cameraSelector.getId(), e);
            // The bridge is registered: Dart receives onFailed and can retry.
            camera.onFailed(e.getMessage() != null ? e.getMessage() : e.toString());
        }
        return camera.getTextureId();
    }

    public synchronized void disposeView(Map<String, Object> args) {
        Object viewIdArg = args.get("viewId");
        if (!(viewIdArg instanceof Number)) return;
        detachViewUnlocked(((Number) viewIdArg).intValue(), null);
    }

    private void detachViewUnlocked(int viewId, String keepCameraId) {
        for (BaseCamera camera : new ArrayList<>(cameras)) {
            if (keepCameraId != null && keepCameraId.equals(camera.getCameraId())) continue;
            CameraBridge bridge = camera.getBridgeByViewId(viewId);
            if (bridge == null) continue;
            camera.removeBridge(bridge);
            if (!camera.existsBridge()) {
                cameras.remove(camera);
                try {
                    camera.dispose();
                } catch (Exception e) {
                    Log.w(TAG, "dispose failed for " + camera.getCameraId() + ": " + e.getMessage());
                }
            }
        }
    }

    private BaseCamera getCameraByIdUnlocked(String id) {
        if (id == null) return null;
        for (BaseCamera camera : cameras) {
            if (id.equals(camera.getCameraId())) {
                return camera;
            }
        }
        return null;
    }

    private static BaseCamera createCamera(
            ViewCameraSelector cameraSelector,
            TextureRegistry.SurfaceTextureEntry texture,
            Map<String, Object> args
    ) {
        String lensFacing = cameraSelector.getLensFacing();
        if ("usb".equals(lensFacing)) {
            return new UsbCamera(texture, args);
        }
        if ("rtsp".equals(lensFacing)) {
            return new RTSPCamera(texture, args);
        }
        return new DeviceCamera(texture, args);
    }
}
