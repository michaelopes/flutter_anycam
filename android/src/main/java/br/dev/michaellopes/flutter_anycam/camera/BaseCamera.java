package br.dev.michaellopes.flutter_anycam.camera;

import android.content.Context;
import android.util.Size;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.HashMap;
import java.util.Map;

import br.dev.michaellopes.flutter_anycam.integration.FlutterEventChannel;
import br.dev.michaellopes.flutter_anycam.model.ViewCameraSelector;
import br.dev.michaellopes.flutter_anycam.utils.CameraPermissionsUtil;
import br.dev.michaellopes.flutter_anycam.utils.ImageAnalysisUtil;

public abstract class BaseCamera {
    protected int viewId;

    protected SurfaceHolder surfaceHolder;
    protected SurfaceView surfaceView;

    protected ViewCameraSelector cameraSelector;

    protected Size preferredSize = new Size(640, 480);

    protected final Map<String, Object> params;

    protected final ImageAnalysisUtil imageAnalysisUtil = new ImageAnalysisUtil();


    public BaseCamera(int viewId, Map<String, Object> params) {
        if (params.get("cameraSelector") != null) {
            final Map<String, Object> cs = (Map<String, Object>) params.get("cameraSelector");
            cameraSelector = ViewCameraSelector.fromMap(cs);
        }
        if (params.get("preferredSize") != null) {
            final Map<String, Object> cs = (Map<String, Object>) params.get("preferredSize");
            preferredSize = new Size((int)cs.get("width"), (int)cs.get("height"));;
        }
        this.params = params;
        this.viewId = viewId;
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
        } else if (CameraPermissionsUtil.getInstance().hasCameraPermission()) {
            init();
        } else {
            FlutterEventChannel.getInstance().send(viewId, "onUnauthorized", new HashMap());
        }
    }

    protected abstract void init();

    public void setSurfaceHolder(SurfaceHolder surfaceHolder) {
        this.surfaceHolder = surfaceHolder;
    }


    public SurfaceView createSurfaceView(Context context) {
        if (surfaceView == null) {
            surfaceView = new SurfaceView(context);
        }
        return surfaceView;
    }

    public abstract void dispose();
}
