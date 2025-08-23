package br.dev.michaellopes.flutter_anycam.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.camera2.CameraManager;

import androidx.annotation.NonNull;
import androidx.camera.camera2.internal.Camera2CameraInfoImpl;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraFilter;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ConcurrentCamera;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import br.dev.michaellopes.flutter_anycam.model.ViewCameraSelector;
@SuppressLint("RestrictedApi")
public class DeviceCameraUtils {

    private DeviceCameraUtils() {
    }

    private static DeviceCameraUtils instance;
    private final List<CameraRef> binds = new ArrayList<>();
    private  Camera currentCamera;

    public static synchronized DeviceCameraUtils getInstance() {
        if (instance == null) instance = new DeviceCameraUtils();
        return instance;

    }

    public synchronized void bind(String cameraId, Preview preview, ImageAnalysis imageAnalysis) {
        CameraUtil.CameraItem camera = CameraUtil.getInstance().getCameraById(cameraId);
        if(camera != null) {
            CameraRef existingCamera = getCameraIfExistsById(cameraId);
            if (existingCamera != null) {
                binds.remove(existingCamera);
            }
            binds.add(new CameraRef(cameraId, camera.getCameraInfo().getCameraSelector(), preview, imageAnalysis));
            updateLifecycle();
        }
    }


    public void setFlash(boolean value) {
        if(currentCamera != null) {
            if (currentCamera.getCameraInfo().hasFlashUnit()) {
                currentCamera.getCameraControl().enableTorch(value);
            }
        }
    }

    @SuppressLint("RestrictedApi")
    private CameraRef getCameraIfExists(CameraRef cameraRef) {
        Object[] list = binds.stream().filter(camera -> Objects.equals(camera.getCameraId(), cameraRef.cameraId)).toArray();
        if (list.length > 0) {
            return (CameraRef) list[0];
        }
        return null;
    }


    private CameraRef getCameraIfExistsById(String cameraId) {
        Object[] list = binds.stream().filter(camera -> Objects.equals(camera.getCameraId(), cameraId)).toArray();
        if (list.length > 0) {
            return (CameraRef) list[0];
        }
        return null;
    }

    private void updateLifecycle() {
        ProcessCameraProvider cameraProvider = CameraUtil.getInstance().getProvider();
        if (cameraProvider != null) {
            currentCamera = null;
            cameraProvider.unbindAll();
            if(!binds.isEmpty()) {
                LifecycleOwner lifecycleOwner = (LifecycleOwner) ContextUtil.get();
                if (binds.size() == 1) {
                    CameraRef bind = binds.get(0);
                    UseCaseGroup.Builder usecase = new UseCaseGroup.Builder();
                    usecase.addUseCase(bind.preview);
                    usecase.addUseCase(bind.imageAnalysis);
                    CameraSelector cameraSelector = bind.cameraSelector;
                    currentCamera =  cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, usecase.build());
                } else {
                    List<ConcurrentCamera.SingleCameraConfig> configs = new ArrayList<>();
                    for (CameraRef bind : binds) {
                        UseCaseGroup.Builder usecase = new UseCaseGroup.Builder();
                        usecase.addUseCase(bind.preview);
                        usecase.addUseCase(bind.imageAnalysis);
                        CameraSelector cameraSelector = bind.cameraSelector;
                        ConcurrentCamera.SingleCameraConfig config = new ConcurrentCamera.SingleCameraConfig(
                                cameraSelector,
                                usecase.build(),
                                lifecycleOwner
                        );
                        configs.add(config);
                    }
                    ConcurrentCamera cCamera = cameraProvider.bindToLifecycle(configs);
                    for (Camera item:
                    cCamera.getCameras()) {
                        if(item.getCameraInfo().getCameraSelector().getLensFacing() ==
                        CameraSelector.LENS_FACING_BACK ) {
                            currentCamera = item;
                        }
                    }

                }
            }
        }
    }

    public void dispose(ViewCameraSelector cameraSelector) {
        setFlash(false);
        CameraRef existingCamera = getCameraIfExistsById(cameraSelector.getId());
        if (existingCamera != null) {
            binds.remove(existingCamera);
            updateLifecycle();
        }
    }

    public static class CameraRef {

        private  final String cameraId;

       private final CameraSelector cameraSelector;
        private final  Preview preview;
        private final  ImageAnalysis imageAnalysis;

        private String getCameraId() {
            return cameraId;
        }

        private CameraRef(String cameraId, CameraSelector cameraSelector, Preview preview, ImageAnalysis imageAnalysis) {
            this.cameraId = cameraId;
            this.cameraSelector = cameraSelector;
            this.preview = preview;
            this.imageAnalysis = imageAnalysis;
        }
    }

}
