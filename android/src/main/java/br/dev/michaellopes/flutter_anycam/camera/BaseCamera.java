package br.dev.michaellopes.flutter_anycam.camera;

import android.util.Size;
import android.view.Surface;

import androidx.annotation.CallSuper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import br.dev.michaellopes.flutter_anycam.model.ViewCameraSelector;
import br.dev.michaellopes.flutter_anycam.utils.CameraPermissionsUtil;
import br.dev.michaellopes.flutter_anycam.utils.ImageAnalysisUtil;
import io.flutter.view.TextureRegistry;

public abstract class BaseCamera {

    private boolean isInitied = false;
    protected final TextureRegistry.SurfaceTextureEntry texture;

    protected ViewCameraSelector cameraSelector;

    protected Size preferredSize = new Size(640, 480);

    protected final Map<String, Object> params;

    protected final ImageAnalysisUtil imageAnalysisUtil = new ImageAnalysisUtil();

    private final List<CameraBridge> bridges = new ArrayList<>();

    private ActionCall lastAction = null;

    public BaseCamera(TextureRegistry.SurfaceTextureEntry texture, Map<String, Object> params) {
        this.texture = texture;
        if (params.get("cameraSelector") != null) {
            final Map<String, Object> cs = (Map<String, Object>) params.get("cameraSelector");
            cameraSelector = ViewCameraSelector.fromMap(cs);
        }
        if (params.get("preferredSize") != null) {
            final Map<String, Object> cs = (Map<String, Object>) params.get("preferredSize");
            preferredSize = new Size((int)cs.get("width"), (int)cs.get("height"));;
        }
        this.params = params;
    }

    public String getCameraId() {
        return cameraSelector.getId();
    }

    public boolean isRtsp() {
        return  cameraSelector.getCameraSelectorRTSP() != null;
    }

    public long getTextureId() {
        return texture.id();
    }

    public CameraBridge getBridgeByViewId(int viewId) {
        Object[] filter = bridges.stream().filter(item -> item.viewId == viewId).toArray();
        if(filter.length >= 1) {
            return (CameraBridge) filter[0];
        }
        return null;
    }

    public boolean containsBridgeByViewId(int viewId) {
      return  getBridgeByViewId(viewId) != null;
    }

    protected Surface getSurface() {
        return new Surface(texture.surfaceTexture());
    }

    public void onConnected(Map<String, Object> data) {
        data.put("textureId", getTextureId());
        for (CameraBridge bridge: bridges) {
            bridge.onConnected(data);
        }
        lastAction = new BaseCamera.ActionCall( "onConnected", data);
    }

    public void onDisconnected() {
        for (CameraBridge bridge: bridges) {
            bridge.onDisconnected();
        }

        lastAction = new BaseCamera.ActionCall( "onDisconnected");
    }

    public void onUnauthorized() {
        for (CameraBridge bridge: bridges) {
            bridge.onUnauthorized();
        }
        lastAction = new BaseCamera.ActionCall( "onUnauthorized");
    }

    public void onFailed(String message) {
        for (CameraBridge bridge: bridges) {
            bridge.onFailed(message);
        }
        lastAction = new BaseCamera.ActionCall( "onFailed", message);
    }

    public void  onVideoFrameReceived(Map<String, Object> imageData) {
        for (CameraBridge bridge: bridges) {
            bridge.onVideoFrameReceived(imageData);
        }
    }

    public boolean existsBridge() {
       return !bridges.isEmpty();
    }

    public void removeBridge(CameraBridge bridge) {
        bridges.remove(bridge);
    }

    public void addBridge(CameraBridge bridge) {
        boolean runCall = !bridges.isEmpty();
        Object[] filter = bridges.stream().filter(item -> item.viewId == bridge.viewId).toArray();
        if(filter.length == 0) {
            bridges.add(bridge);
        }
        if(runCall && lastAction != null) {
            switch (lastAction.method) {
                case "onDisconnected":
                    bridge.onDisconnected();
                    break;
                case "onUnauthorized":
                    bridge.onUnauthorized();
                    break;
                case "onFailed":
                    bridge.onFailed((String)lastAction.data);
                    break;
                default:
                    bridge.onConnected((Map<String, Object>)lastAction.data);
                    break;
            }
        }
    }

    protected int getFps() {
        if (params.get("fps") != null) {
            return (int) params.get("fps");
        }
        return 5;
    }

    public synchronized void run() {
        if (cameraSelector.getCameraSelectorRTSP() != null) {
            init();
            isInitied = true;
        } else if (CameraPermissionsUtil.getInstance().hasCameraPermission() && !isInitied) {
            init();
            isInitied = true;
        } else {
           onUnauthorized();
            isInitied = false;
        }
    }

    protected abstract void init();

    @CallSuper
    public void dispose() {
        bridges.clear();
        texture.release();
    }

   static class ActionCall {
        final String method;
        final Object data;


       ActionCall(String method, Object data) {
           this.method = method;
           this.data = data;
       }

       ActionCall(String method) {
           this.method = method;
           this.data = null;
       }
   }
}
