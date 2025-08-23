package br.dev.michaellopes.flutter_anycam.integration;

import android.content.Context;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import androidx.annotation.Nullable;

import br.dev.michaellopes.flutter_anycam.camera.BaseCamera;
import br.dev.michaellopes.flutter_anycam.camera.CameraFactory;

import java.util.Map;

import io.flutter.plugin.platform.PlatformView;

public class FlutterView implements PlatformView {
    private final SurfaceView surfaceView;
    private final SurfaceHolder surfaceHolder;
    private final int viewId;
    private final BaseCamera camera;

    public FlutterView(Context context, int vId, Map<String, Object> params) {

        final Integer customViewId = (Integer) params.get("viewId");
        if(customViewId != null) {
            this.viewId = customViewId;
        } else {
            this.viewId = vId;
        }
        this.camera = CameraFactory.getInstance().getCamera(viewId, params);

        surfaceView = camera.createSurfaceView(context);
        surfaceHolder = surfaceView.getHolder();

        surfaceHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                camera.run();
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                camera.run();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                camera.dispose();
            }
        });

        camera.setSurfaceHolder(surfaceHolder);

    }


    @Nullable
    @Override
    public View getView() {
        return surfaceView;
    }

    @Override
    public void dispose() {
        if(camera != null) {
            camera.dispose();
        }
    }
}
