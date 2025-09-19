package br.dev.michaellopes.flutter_anycam.camera;

import android.content.Context;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbDevice;

import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usb.UVCParam;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;


import br.dev.michaellopes.flutter_anycam.integration.FlutterEventChannel;
import br.dev.michaellopes.flutter_anycam.utils.ContextUtil;
import br.dev.michaellopes.flutter_anycam.utils.FrameRateLimiterUtil;

public class UsbCamera extends BaseCamera implements IFrameCallback, USBMonitor.OnDeviceConnectListener{

    private final USBMonitor mUSBMonitor;
    private UVCCamera mUVCCamera;
    private boolean isAttached = false;

    private boolean frameWait = false;

    private final Executor executor = Executors.newSingleThreadExecutor();

    private Size size;

    FrameRateLimiterUtil<ByteBuffer> limiter = new FrameRateLimiterUtil<ByteBuffer>(getFps()) {
        @Override
        protected void onFrameLimited(ByteBuffer frame) {
            if(!frameWait ) {
                frameWait = true;
                executor.execute(()-> {
                    Map<String, Object> imageData = imageAnalysisUtil.usbFrameToFlutterResult(frame, size.width, size.height, getCustomRotationDegrees());
                    FlutterEventChannel.getInstance().send(viewId, "onVideoFrameReceived", imageData);
                    frameWait = false;
                });

            }
        }
    };


    private Integer getCustomRotationDegrees() {
        if(cameraSelector.isForceSensorOrientation()) {
            return cameraSelector.getSensorOrientation();
        }
        return  null;
    }

    public UsbCamera(int viewId, Map<String, Object> params) {
        super(viewId, params);

        mUSBMonitor = new USBMonitor(ContextUtil.get(), this);
    }

    @Override
    public void init() {
        mUSBMonitor.register();
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

    public static boolean isUsbCameraConnected(Context context) {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        for (UsbDevice device : deviceList.values()) {
            if (isUsbCamera(device)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onFrame(ByteBuffer frame) {
        limiter.onNewFrame(frame);
    }

    @Override
    public void onAttach(UsbDevice device) {
        String deviceId = String.valueOf(device.getDeviceId());
        if (isUsbCamera(device) && deviceId.equals(cameraSelector.getId())) {
            mUSBMonitor.requestPermission(device);
            if (mUSBMonitor.hasPermission(device)) {
                if (!isAttached) {
                    isAttached = true;
                }
            } else {
                FlutterEventChannel.getInstance().send(viewId, "onUnauthorized", new HashMap());
            }
        }
    }

    @Override
    public void onDetach(UsbDevice device) {
        isAttached = false;
    }

    @Override
    public void onDeviceOpen(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
        String deviceId = String.valueOf(device.getDeviceId());
        if (deviceId.equals(cameraSelector.getId())) {

            if (surfaceHolder != null) {
                try {

                    UVCParam param = new UVCParam();
                    mUVCCamera = new UVCCamera(param);
                    mUVCCamera.open(ctrlBlock);
                    size = getClosestSize(mUVCCamera.getSupportedSizeList());
                    mUVCCamera.setPreviewSize(size);
                    mUVCCamera.setFrameCallback(UsbCamera.this, UVCCamera.PIXEL_FORMAT_NV21);
                    mUVCCamera.setPreviewDisplay(surfaceHolder);
                    mUVCCamera.startPreview();

                    final Map<String, Object> result = new HashMap<>();
                    result.put("width",size.width);
                    result.put("height", size.height);
                    result.put("isPortrait", false);
                    result.put("rotation", 0);

                    FlutterEventChannel.getInstance().send(viewId, "onConnected", result);
                } catch (Exception e) {
                    FlutterEventChannel.getInstance().send(viewId, "onFailed", new HashMap() {{
                        put("message", e.getMessage());
                    }});
                    e.printStackTrace();
                }

            }
        }
    }

    @Override
    public void onDeviceClose(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
        if (mUVCCamera != null) {
            mUVCCamera.close();
        }
        FlutterEventChannel.getInstance().send(viewId, "onDisconnected", new HashMap());
    }



    @Override
    public void onCancel(UsbDevice device) {
        if (mUVCCamera != null) {
            mUVCCamera.close();
        }
        FlutterEventChannel.getInstance().send(viewId, "onDisconnected", new HashMap());
    }

    @Override
    public void onError(UsbDevice device, USBMonitor.USBException e) {
        USBMonitor.OnDeviceConnectListener.super.onError(device, e);
    }
    private Size getClosestSize(List<Size> sizes) {
        if (sizes == null || sizes.isEmpty()) return null;

        Size closest = sizes.get(0);
        int targetWidth = preferredSize.getWidth();
        int targetHeight = preferredSize.getHeight();
        int minDiff = Math.abs(closest.width - targetWidth) + Math.abs(closest.height - targetHeight);

        for (Size s : sizes) {
            if(s.type == 7) {
                int diff = Math.abs(s.width - targetWidth) + Math.abs(s.height - targetHeight);
                if (diff < minDiff) {
                    closest = s;
                    minDiff = diff;
                }
            }
        }
        return closest;
    }

    @Override
    public void dispose() {
        if (mUSBMonitor != null) {
            if (mUSBMonitor.isRegistered()) {
                mUSBMonitor.unregister();
            }
        }
        if (mUVCCamera != null) {
            mUVCCamera.close();
        }
        mUVCCamera = null;
    }



}
