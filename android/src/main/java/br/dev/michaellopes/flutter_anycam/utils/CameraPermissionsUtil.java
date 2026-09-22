package br.dev.michaellopes.flutter_anycam.utils;

import android.Manifest;
import android.Manifest.permission;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.VisibleForTesting;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Runtime CAMERA permission only.
 * USB device access must use {@code UsbManager.requestPermission} (handled in UsbCamera/USBMonitor),
 * not {@code ActivityCompat.requestPermissions} — {@code USB_PERMISSION} is not a normal runtime permission.
 */
public final class CameraPermissionsUtil {
    private static CameraPermissionsUtil instance;

    public interface PermissionsRegistry {
        @SuppressWarnings("deprecation")
        void addListener(
                io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener handler);
    }

    public interface ResultCallback {
        void onResult(String errorCode, String errorDescription);
    }

    private static final String CAMERA_PERMISSIONS_REQUEST_ONGOING =
            "CameraPermissionsRequestOngoing";

    private static final String CAMERA_PERMISSIONS_REQUEST_ONGOING_MESSAGE =
            "Another request is ongoing and multiple requests cannot be handled at once.";
    private static final String CAMERA_ACCESS_DENIED = "CameraAccessDenied";
    private static final String CAMERA_ACCESS_DENIED_MESSAGE = "Camera access permission was denied.";
    private static final String CAMERA_ACTIVITY_MISSING = "CameraActivityMissing";
    private static final String CAMERA_ACTIVITY_MISSING_MESSAGE =
            "No Activity attached; cannot request camera permission.";

    private static final int CAMERA_REQUEST_ID = 9796;
    @VisibleForTesting
    boolean ongoing = false;
    private PermissionsRegistry permissionsRegistry;

    CameraPermissionsUtil() {
    }

    public static CameraPermissionsUtil getInstance() {
        if (instance == null) {
            instance = new CameraPermissionsUtil();
        }
        return instance;
    }

    public void init(PermissionsRegistry permissionsRegistry) {
        this.permissionsRegistry = permissionsRegistry;
    }

    public void requestPermissions(ResultCallback callback) {
        if (ongoing) {
            callback.onResult(
                    CAMERA_PERMISSIONS_REQUEST_ONGOING, CAMERA_PERMISSIONS_REQUEST_ONGOING_MESSAGE);
            return;
        }
        Activity activity = ContextUtil.getActivity();
        if (activity == null) {
            callback.onResult(CAMERA_ACTIVITY_MISSING, CAMERA_ACTIVITY_MISSING_MESSAGE);
            return;
        }
        if (!hasCameraPermission()) {
            if (permissionsRegistry == null) {
                callback.onResult(CAMERA_ACTIVITY_MISSING, CAMERA_ACTIVITY_MISSING_MESSAGE);
                return;
            }
            permissionsRegistry.addListener(
                    new CameraRequestPermissionsListener(
                            (String errorCode, String errorDescription) -> {
                                ongoing = false;
                                callback.onResult(errorCode, errorDescription);
                            }));
            ongoing = true;
            ActivityCompat.requestPermissions(
                    activity,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_REQUEST_ID);
        } else {
            callback.onResult(null, null);
        }
    }

    public boolean hasCameraPermission() {
        Context androidContext = ContextUtil.get();
        if (androidContext == null) {
            return false;
        }
        return ContextCompat.checkSelfPermission(androidContext, permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    @VisibleForTesting
    @SuppressWarnings("deprecation")
    static final class CameraRequestPermissionsListener
            implements io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener {

        boolean alreadyCalled = false;

        final ResultCallback callback;

        @VisibleForTesting
        CameraRequestPermissionsListener(ResultCallback callback) {
            this.callback = callback;
        }

        @Override
        public boolean onRequestPermissionsResult(int id, String[] permissions, int[] grantResults) {
            if (alreadyCalled || id != CAMERA_REQUEST_ID) {
                return false;
            }

            alreadyCalled = true;
            if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                callback.onResult(CAMERA_ACCESS_DENIED, CAMERA_ACCESS_DENIED_MESSAGE);
            } else {
                callback.onResult(null, null);
            }

            return true;
        }
    }
}
