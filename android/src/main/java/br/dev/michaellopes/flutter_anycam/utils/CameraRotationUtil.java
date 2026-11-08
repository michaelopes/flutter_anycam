package br.dev.michaellopes.flutter_anycam.utils;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.view.Surface;
import android.view.WindowManager;

/**
 * Resolves frame rotation for Camera2 / headless paths using the same formula
 * CameraX applies to {@code ImageProxy.getImageInfo().getRotationDegrees()}.
 */
public final class CameraRotationUtil {

    private CameraRotationUtil() {
    }

    public static int resolveFrameRotation(
            String cameraId,
            boolean forceSensorOrientation,
            int selectorSensorOrientation
    ) {
        if (forceSensorOrientation) {
            return selectorSensorOrientation;
        }
        return computeRelativeImageRotation(cameraId, selectorSensorOrientation);
    }

    private static int computeRelativeImageRotation(String cameraId, int fallbackSensorOrientation) {
        int sensorOrientation = readSensorOrientation(cameraId, fallbackSensorOrientation);
        int displayRotation = readDisplayRotationDegrees();
        if (isFrontFacingCamera(cameraId)) {
            return (sensorOrientation + displayRotation) % 360;
        }
        return (sensorOrientation - displayRotation + 360) % 360;
    }

    private static int readSensorOrientation(String cameraId, int fallbackSensorOrientation) {
        try {
            CameraManager manager = ContextUtil.get().getSystemService(CameraManager.class);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (orientation != null) {
                return orientation;
            }
        } catch (CameraAccessException ignored) {
        }
        return fallbackSensorOrientation;
    }

    private static boolean isFrontFacingCamera(String cameraId) {
        try {
            CameraManager manager = ContextUtil.get().getSystemService(CameraManager.class);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            return facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
        } catch (CameraAccessException ignored) {
            return false;
        }
    }

    private static int readDisplayRotationDegrees() {
        Context context = ContextUtil.get();
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            return 0;
        }
        switch (windowManager.getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            default:
                return 0;
        }
    }
}
