package br.dev.michaellopes.flutter_anycam.camera;

import android.content.Context;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbDevice;

import androidx.annotation.CallSuper;

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


import br.dev.michaellopes.flutter_anycam.utils.ContextUtil;
import br.dev.michaellopes.flutter_anycam.utils.FrameRateLimiterUtil;
import io.flutter.view.TextureRegistry;

public class UsbCamera extends BaseCamera implements IFrameCallback, USBMonitor.OnDeviceConnectListener{

    private USBMonitor mUSBMonitor;
    private UVCCamera mUVCCamera;
    private boolean isAttached = false;

    private boolean frameWait = false;

    private final Executor executor = Executors.newSingleThreadExecutor();

    private android.util.Size size;

    FrameRateLimiterUtil<ByteBuffer> limiter = new FrameRateLimiterUtil<ByteBuffer>(getFps()) {
        @Override
        protected void onFrameLimited(ByteBuffer frame) {
            if(!frameWait && size != null) {
                frameWait = true;
                executor.execute(()-> {
                    Map<String, Object> imageData = imageAnalysisUtil.usbFrameToFlutterResult(frame, size.getWidth(), size.getHeight(), getCustomRotationDegrees());
                    onVideoFrameReceived(imageData);
                    frameWait = false;
                });

            }
        }
    };

    public UsbCamera(TextureRegistry.SurfaceTextureEntry texture, Map<String, Object> params) {
        super(texture, params);

    }


    private Integer getCustomRotationDegrees() {
        if(cameraSelector.isForceSensorOrientation()) {
            return cameraSelector.getSensorOrientation();
        }
        return  null;
    }



    @Override
    public void init() {
        mUSBMonitor = new USBMonitor(ContextUtil.get(), this);
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
                onUnauthorized();
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
                try {

                    UVCParam param = new UVCParam();
                    mUVCCamera = new UVCCamera(param);
                    mUVCCamera.open(ctrlBlock);
                     Size camSize = getClosestSize(mUVCCamera.getSupportedSizeList());
                     if(camSize != null) {
                         mUVCCamera.setPreviewSize(camSize);
                         size = new android.util.Size(camSize.width, camSize.height);
                     } else {
                         size = new android.util.Size(640, 480);
                     }
                    mUVCCamera.setFrameCallback(UsbCamera.this, UVCCamera.PIXEL_FORMAT_NV21);
                    mUVCCamera.setPreviewDisplay(getSurface());
                    mUVCCamera.startPreview();

                    texture.surfaceTexture().setDefaultBufferSize(size.getWidth(),  size.getHeight());

                    final Map<String, Object> result = new HashMap<>();
                    result.put("width", size.getWidth());
                    result.put("height", size.getHeight());
                    onConnected(result);

                } catch (Exception e) {
                    onFailed(e.getMessage());
                    e.printStackTrace();
                }
        }
    }

    @Override
    public void onDeviceClose(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
        size = null;
        if (mUVCCamera != null) {
            mUVCCamera.close();
        }
      onDisconnected();
    }

    @Override
    public void onCancel(UsbDevice device) {
        size = null;
        if (mUVCCamera != null) {
            mUVCCamera.close();
        }
        onDisconnected();
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
        size = null;
        mUVCCamera = null;
        mUSBMonitor = null;
        super.dispose();
    }



}
