package br.dev.michaellopes.flutter_anycam.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.usb.UsbDevice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import android.hardware.usb.UsbInterface;
import android.os.Build;
import android.util.Log;

import androidx.camera.camera2.internal.Camera2CameraInfoImpl;
import androidx.camera.core.CameraInfo;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.serenegiant.usb.USBMonitor;


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

    private final List<CameraItem> cameras = new ArrayList<>();

    public void availableCameras(CamerasCallback callback) {
        synchronized (instance) {
            if(provider != null) {
                List<Map<String, Object>> list = new ArrayList<>();
                for (CameraItem item : cameras) {
                    list.add(item.cameraMap);
                }
                callback.onResult(list);
            } else {
                cameraProviderFuture.addListener(() -> {
                    List<Map<String, Object>> list = new ArrayList<>();
                    for (CameraItem item : cameras) {
                        list.add(item.cameraMap);
                    }
                    callback.onResult(list);
                }, ContextCompat.getMainExecutor(ContextUtil.get()));
            }
        }
    }

    public ProcessCameraProvider getProvider() {
        return provider;
    }

    public CameraItem getCameraById(String id) {
        Object[] filter = cameras.stream().filter(item -> item.getId().equals(id)).toArray();
        if (filter.length >= 1) {
            return (CameraItem) filter[0];
        }
        return null;
    }

    public void init(Context context) {
        synchronized (instance) {

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


                        Map<String, Object> cameraMap = new HashMap<>();
                        cameraMap.put("id", camera2CameraInfo.getCameraId());
                        cameraMap.put("name", lensDirection + " camera");
                        cameraMap.put("lensFacing", lensDirection);
                        cameraMap.put("sensorOrientation", sensorOrientation);

                        cameras.add(new CameraItem(camera2CameraInfo, cameraMap));
                    }


                    USBMonitor mUSBMonitor = new USBMonitor(context, new USBMonitor.OnDeviceConnectListener() {
                        @Override
                        public void onAttach(UsbDevice device) {
                        }

                        @Override
                        public void onDetach(UsbDevice device) {
                        }

                        @Override
                        public void onDeviceOpen(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
                        }

                        @Override
                        public void onDeviceClose(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
                        }

                        @Override
                        public void onCancel(UsbDevice device) {
                        }
                    });

                    List<UsbDevice> deviceList = mUSBMonitor.getDeviceList();
                    for (UsbDevice device : deviceList) {
                        Log.d("USB", "Device: " + device.getDeviceName());
                        if (isUsbCamera(device)) {
                            Map<String, Object> cameraInfo = new HashMap<>();
                            cameraInfo.put("id", String.valueOf(device.getDeviceId()));
                            cameraInfo.put("name", device.getDeviceName());
                            cameraInfo.put("lensFacing", "usb");
                            cameraInfo.put("sensorOrientation", 0);
                            cameras.add(new CameraItem(null, cameraInfo));
                        }
                    }
                    if (mUSBMonitor.isRegistered()) {
                        mUSBMonitor.unregister();
                        mUSBMonitor = null;
                    }

                    this.provider = provider;
                } catch (Exception e) {
                    e.printStackTrace();
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
