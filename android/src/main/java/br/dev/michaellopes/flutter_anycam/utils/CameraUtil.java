package br.dev.michaellopes.flutter_anycam.utils;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.usb.UsbDevice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.hardware.usb.UsbInterface;
import android.util.Log;

import com.jiangdg.usb.USBMonitor;


public class CameraUtil {
   private static List<Map<String, Object>> cameras = new ArrayList<>();

    public static List<Map<String, Object>> availableCameras() {
        return cameras;
    }

    public static void init(Context context) {

        CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        try {
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);

                Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

                String lensDirection;
                if (lensFacing != null) {
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
                } else {
                    lensDirection = "unknown";
                }

                Map<String, Object> cameraInfo = new HashMap<>();
                cameraInfo.put("id", cameraId);
                cameraInfo.put("name", lensDirection + " camera");
                cameraInfo.put("lensFacing", lensDirection);
                cameraInfo.put("sensorOrientation", sensorOrientation != null ? sensorOrientation : 0);

                cameras.add(cameraInfo);
            }
            USBMonitor mUSBMonitor = new USBMonitor(context, new USBMonitor.OnDeviceConnectListener() {
                @Override
                public void onAttach(UsbDevice device) {
                }

                @Override
                public void onDetach(UsbDevice device) {
                }

                @Override
                public void onConnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
                }

                @Override
                public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
                }

                @Override
                public void onCancel(UsbDevice device) {
                }
            });

            List<UsbDevice> deviceList = mUSBMonitor.getDeviceList();
            for (UsbDevice device : deviceList) {
                Log.d("USB", "Device: " + device.getDeviceName());
                if(isUsbCamera(device)) {
                    Map<String, Object> cameraInfo = new HashMap<>();
                    cameraInfo.put("id", String.valueOf(device.getDeviceId()));
                    cameraInfo.put("name", device.getDeviceName());
                    cameraInfo.put("lensFacing", "usb");
                    cameraInfo.put("sensorOrientation", 0);
                    cameras.add(cameraInfo);
                }
            }
            if(mUSBMonitor.isRegistered()) {
                mUSBMonitor.unregister();
                mUSBMonitor = null;
            }

        } catch (Exception e) {
            e.printStackTrace();
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
}
