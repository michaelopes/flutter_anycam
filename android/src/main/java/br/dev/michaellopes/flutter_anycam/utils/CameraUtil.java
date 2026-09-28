package br.dev.michaellopes.flutter_anycam.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.hardware.usb.UsbInterface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Range;

import androidx.camera.camera2.internal.Camera2CameraInfoImpl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.ExposureState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;


@SuppressLint("RestrictedApi")
public class CameraUtil {

    private static final CameraUtil instance = new CameraUtil();

    private CameraUtil() {
    }

    public static CameraUtil getInstance() {
        return instance;
    }

    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;

    private ProcessCameraProvider provider;

    /** CameraX cameras, enumerated once per init. */
    private final List<CameraItem> cameras = new ArrayList<>();

    /** Re-enumerated on every {@link #availableCameras} call: USB devices come and go. */
    private final List<CameraItem> usbCameras = new ArrayList<>();

    private final List<CamerasCallback> pendingCallbacks = new ArrayList<>();

    private boolean initStarted = false;

    private static final long AVAILABLE_CAMERAS_TIMEOUT_MS = 15000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Runnable availableCamerasTimeoutRunnable;

    public void availableCameras(CamerasCallback callback) {
        synchronized (instance) {
            if (provider != null) {
                callback.onResult(buildCameraList());
                return;
            }

            pendingCallbacks.add(callback);

            Context context = ContextUtil.get();
            if (!initStarted && context != null) {
                init(context);
            }

            if (initStarted) {
                scheduleAvailableCamerasTimeout();
            } else {
                Log.w("CameraUtil", "availableCameras called before init; returning empty list");
                pendingCallbacks.remove(callback);
                callback.onResult(new ArrayList<>());
            }
        }
    }

    private List<Map<String, Object>> buildCameraList() {
        refreshUsbCameras();
        List<Map<String, Object>> list = new ArrayList<>();
        for (CameraItem item : cameras) {
            list.add(item.cameraMap);
        }
        for (CameraItem item : usbCameras) {
            list.add(item.cameraMap);
        }
        return list;
    }

    private void refreshUsbCameras() {
        usbCameras.clear();
        Context context = ContextUtil.get();
        UsbManager usbManager = context != null
                ? (UsbManager) context.getSystemService(Context.USB_SERVICE)
                : null;
        if (usbManager == null) {
            return;
        }
        List<UsbDevice> devices = new ArrayList<>();
        List<UsbCameraId.Device> identities = new ArrayList<>();
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (isUsbCamera(device)) {
                devices.add(device);
                identities.add(UsbCameraId.of(device));
            }
        }
        List<String> ids = UsbCameraId.assign(identities);
        for (int i = 0; i < devices.size(); i++) {
            UsbDevice device = devices.get(i);
            Map<String, Object> cameraInfo = new HashMap<>();
            cameraInfo.put("id", ids.get(i));
            cameraInfo.put("name", device.getDeviceName());
            cameraInfo.put("lensFacing", "usb");
            cameraInfo.put("sensorOrientation", 0);
            usbCameras.add(new CameraItem(null, cameraInfo));
        }
    }

    private void flushPendingCallbacks() {
        synchronized (instance) {
            cancelAvailableCamerasTimeout();
            List<Map<String, Object>> list = buildCameraList();
            for (CamerasCallback callback : pendingCallbacks) {
                callback.onResult(list);
            }
            pendingCallbacks.clear();
        }
    }

    private void scheduleAvailableCamerasTimeout() {
        if (availableCamerasTimeoutRunnable != null) {
            return;
        }
        availableCamerasTimeoutRunnable = () -> {
            Log.w("CameraUtil", "availableCameras timed out after " + AVAILABLE_CAMERAS_TIMEOUT_MS + "ms");
            flushPendingCallbacks();
        };
        mainHandler.postDelayed(availableCamerasTimeoutRunnable, AVAILABLE_CAMERAS_TIMEOUT_MS);
    }

    private void cancelAvailableCamerasTimeout() {
        if (availableCamerasTimeoutRunnable != null) {
            mainHandler.removeCallbacks(availableCamerasTimeoutRunnable);
            availableCamerasTimeoutRunnable = null;
        }
    }

    public void reset() {
        synchronized (instance) {
            cancelAvailableCamerasTimeout();
            for (CamerasCallback callback : pendingCallbacks) {
                callback.onResult(new ArrayList<>());
            }
            pendingCallbacks.clear();
            cameras.clear();
            usbCameras.clear();
            provider = null;
            cameraProviderFuture = null;
            initStarted = false;
        }
    }

    public ProcessCameraProvider getProvider() {
        return provider;
    }

    public CameraItem getCameraById(String id) {
        synchronized (instance) {
            for (CameraItem item : cameras) {
                if (item.getId().equals(id)) return item;
            }
            for (CameraItem item : usbCameras) {
                if (item.getId().equals(id)) return item;
            }
            return null;
        }
    }

    public void init(Context context) {
        synchronized (instance) {
            if (initStarted) {
                return;
            }
            initStarted = true;
            cameras.clear();
            provider = null;

            io.flutter.Log.i("REQUEST_MAX_NUM_OUTPUT_STREAMS", String.valueOf(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE));

            cameraProviderFuture = ProcessCameraProvider.getInstance(context);
            cameraProviderFuture.addListener(() -> {
                try {
                    ProcessCameraProvider provider = cameraProviderFuture.get();
                    int counter = provider.getAvailableCameraInfos().size();
                    for (int i = 0; i < counter; i++) {
                        CameraInfo availableCameraInfo = provider.getAvailableCameraInfos().get(i);
                        Camera2CameraInfoImpl camera2CameraInfo = (Camera2CameraInfoImpl) availableCameraInfo;




                        Integer lensFacing = camera2CameraInfo.getLensFacing();
                        Integer sensorOrientation = camera2CameraInfo.getSensorRotationDegrees();

                        String lensDirection;
                        String cameraType = "unknown";
                        float focal = -1f;
                        float minFocus = -1f;

                        Map<String, Object> cameraMap = new HashMap<>();
                        switch (lensFacing) {
                            case CameraCharacteristics.LENS_FACING_FRONT:
                                lensDirection = "front";
                                break;
                            case CameraCharacteristics.LENS_FACING_BACK:
                                lensDirection = "back";
                                break;
                            default:
                                lensDirection = "unknown";
                        }


                        Object obj =
                                camera2CameraInfo.getCameraCharacteristics();
                        if (obj instanceof CameraCharacteristics) {
                            CameraCharacteristics characteristics = (CameraCharacteristics) obj;
                            float[] focalLengths = characteristics.get(
                                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                            Float minFcs = characteristics.get(
                                    CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                            if(minFcs != null) {
                                minFocus = minFcs;
                            }
                            if (focalLengths != null) {
                                 focal = focalLengths[0];
                                 System.out.println("focalLength: " + focal + " lensDirection:" + lensDirection);
                                if (focal > 6f) {
                                    cameraType = "telephoto";
                                } else if (focal < 2.5f) {
                                    cameraType = "ultraWide";
                                } else {
                                    cameraType = "wide";
                                }

                            }
                        }
                        ExposureState exposureState = availableCameraInfo.getExposureState();
                        Range<Integer> range = exposureState.getExposureCompensationRange();
                        io.flutter.Log.d("Camera", "Range: " + range.getLower() + " to " + range.getUpper());

                        cameraMap.put("id", camera2CameraInfo.getCameraId());
                        cameraMap.put("name", lensDirection + " camera");
                        cameraMap.put("lensFacing", lensDirection);
                        cameraMap.put("sensorOrientation", sensorOrientation);
                        cameraMap.put("cameraType", cameraType);
                        cameraMap.put("focalLength", focal);


                        cameraMap.put("minFocusDistance", minFocus);

                        if(exposureState.isExposureCompensationSupported()) {
                            cameraMap.put("minExposureValue", range.getLower());
                            cameraMap.put("maxExposureValue", range.getUpper());
                        }

                        cameras.add(new CameraItem(camera2CameraInfo, cameraMap));
                    }

                    this.provider = provider;
                } catch (Exception e) {
                    Log.e("CameraUtil", "Failed to initialize ProcessCameraProvider", e);
                } finally {
                    flushPendingCallbacks();
                }
            }, ContextCompat.getMainExecutor(context));
        }
    }


    private static boolean isUsbCamera(UsbDevice device) {
        final int USB_CLASS_VIDEO = 14;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface usbInterface = device.getInterface(i);
            if (usbInterface.getInterfaceClass() == USB_CLASS_VIDEO) {
                return true;
            }
        }
        return false;
    }


    public interface CamerasCallback {
        public void onResult(List<Map<String, Object>> cameras);
    }

    public static class CameraItem {
        private final Camera2CameraInfoImpl cameraInfo;
        private final Map<String, Object> cameraMap;

        public CameraItem(Camera2CameraInfoImpl cameraInfo, Map<String, Object> cameraMap) {
            this.cameraInfo = cameraInfo;
            this.cameraMap = cameraMap;
        }

        public String getId() {
            return (String) cameraMap.get("id");
        }

        public boolean isUsb() {
            return cameraInfo == null;
        }

        public Camera2CameraInfoImpl getCameraInfo() {
            return cameraInfo;
        }

        public Map<String, Object> getCameraMap() {
            return cameraMap;
        }
    }
}
