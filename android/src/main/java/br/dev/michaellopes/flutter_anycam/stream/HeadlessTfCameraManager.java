package br.dev.michaellopes.flutter_anycam.stream;

import android.util.Size;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import br.dev.michaellopes.flutter_anycam.utils.CameraPermissionsUtil;

public class HeadlessTfCameraManager {

    private static HeadlessTfCameraManager instance;

    private final List<HeadlessTfCameraSession> sessions = new ArrayList<>();

    private HeadlessTfCameraManager() {
    }

    public static synchronized HeadlessTfCameraManager getInstance() {
        if (instance == null) {
            instance = new HeadlessTfCameraManager();
        }
        return instance;
    }

    public synchronized boolean register(Map<String, Object> args) {
        if (!CameraPermissionsUtil.getInstance().hasCameraPermission()) {
            return false;
        }

        String cameraId = (String) args.get("cameraId");
        if (cameraId == null || exists(cameraId)) {
            return false;
        }

        int fps = args.get("fps") != null ? (int) args.get("fps") : 5;
        int filter = args.get("filter") != null ? (int) args.get("filter") : 0;

        Size preferredSize = new Size(640, 480);
        if (args.get("preferredSize") != null) {
            Map<String, Object> sizeMap = (Map<String, Object>) args.get("preferredSize");
            preferredSize = new Size(
                    (int) sizeMap.get("width"),
                    (int) sizeMap.get("height")
            );
        }

        int selectorSensorOrientation = args.get("sensorOrientation") != null
                ? (int) args.get("sensorOrientation")
                : 0;
        boolean forceSensorOrientation = args.get("forceSensorOrientation") != null
                && (boolean) args.get("forceSensorOrientation");

        HeadlessTfCameraSession session = new HeadlessTfCameraSession(
                cameraId,
                fps,
                preferredSize,
                filter,
                selectorSensorOrientation,
                forceSensorOrientation
        );
        sessions.add(session);
        session.start();
        return true;
    }

    public synchronized void dispose(String cameraId) {
        HeadlessTfCameraSession session = get(cameraId);
        if (session != null) {
            session.dispose();
            sessions.remove(session);
        }
    }

    private boolean exists(String cameraId) {
        return get(cameraId) != null;
    }

    private HeadlessTfCameraSession get(String cameraId) {
        for (HeadlessTfCameraSession session : sessions) {
            if (session.getCameraId().equals(cameraId)) {
                return session;
            }
        }
        return null;
    }
}
