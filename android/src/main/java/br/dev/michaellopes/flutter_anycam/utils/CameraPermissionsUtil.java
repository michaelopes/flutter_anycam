package br.dev.michaellopes.flutter_anycam.utils;

import android.Manifest;
import android.Manifest.permission;
import android.app.Activity;
import android.content.pm.PackageManager;
import androidx.annotation.VisibleForTesting;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public final class CameraPermissionsUtil {
    private static CameraPermissionsUtil INSTANCE;

  public interface PermissionsRegistry {
        @SuppressWarnings("deprecation")
        void addListener(
                io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener handler);
    }

    public interface ResultCallback {
        void onResult(String errorCode, String errorDescription);
    }

    /**
     * Camera access permission errors handled when camera is created. See {@code MethodChannelCamera}
     * in {@code camera/camera_platform_interface} for details.
     */
    private static final String CAMERA_PERMISSIONS_REQUEST_ONGOING =
            "CameraPermissionsRequestOngoing";

    private static final String CAMERA_PERMISSIONS_REQUEST_ONGOING_MESSAGE =
            "Another request is ongoing and multiple requests cannot be handled at once.";
    private static final String CAMERA_ACCESS_DENIED = "CameraAccessDenied";
    private static final String CAMERA_ACCESS_DENIED_MESSAGE = "Camera access permission was denied.";

    private static final int CAMERA_REQUEST_ID = 9796;
    @VisibleForTesting boolean ongoing = false;
    private PermissionsRegistry permissionsRegistry;

    CameraPermissionsUtil() {
    }

    public static CameraPermissionsUtil getINSTANCE() {
        if(INSTANCE == null) {
            INSTANCE = new CameraPermissionsUtil();
        }
        return INSTANCE;
    }


    public void init(
                     PermissionsRegistry permissionsRegistry) {
        this.permissionsRegistry = permissionsRegistry;
    }

    private Activity getActivity() {
        return (Activity) ContextUtil.get();
    }
  public  void requestPermissions(
            ResultCallback callback) {
        if (ongoing) {
            callback.onResult(
                    CAMERA_PERMISSIONS_REQUEST_ONGOING, CAMERA_PERMISSIONS_REQUEST_ONGOING_MESSAGE);
            return;
        }
        if (!hasCameraPermission(getActivity())) {
            permissionsRegistry.addListener(
                    new CameraRequestPermissionsListener(
                            (String errorCode, String errorDescription) -> {
                                ongoing = false;
                                callback.onResult(errorCode, errorDescription);
                            }));
            ongoing = true;
            ActivityCompat.requestPermissions(
                    getActivity(),
                    new String[] { Manifest.permission.CAMERA, "android.permission.USB_PERMISSION"},
                    CAMERA_REQUEST_ID);
        } else {
            // Permissions already exist. Call the callback with success.
            callback.onResult(null, null);
        }
    }

    private boolean hasCameraPermission(Activity activity) {
        return ContextCompat.checkSelfPermission(activity, permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAudioPermission(Activity activity) {
        return ContextCompat.checkSelfPermission(activity, permission.RECORD_AUDIO)
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
            if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                callback.onResult(CAMERA_ACCESS_DENIED, CAMERA_ACCESS_DENIED_MESSAGE);
            } else {
                callback.onResult(null, null);
            }

            return true;
        }
    }
}
