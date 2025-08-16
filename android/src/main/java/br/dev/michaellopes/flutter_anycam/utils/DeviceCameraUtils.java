package br.dev.michaellopes.flutter_anycam.utils;

import android.annotation.SuppressLint;

import androidx.camera.camera2.internal.Camera2CameraInfoImpl;
import androidx.camera.core.Camera;
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

    private final ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(ContextUtil.get());
    private ProcessCameraProvider cameraProvider;
    private static DeviceCameraUtils instance;
    private final List<CameraRef> binds = new ArrayList<>();

    public static synchronized DeviceCameraUtils getInstance() {
        if (instance == null) instance = new DeviceCameraUtils();
        return instance;

    }

    public ListenableFuture<ProcessCameraProvider> getCameraProviderFuture() {
        return cameraProviderFuture;
    }

    private ProcessCameraProvider getCameraProvider() throws ExecutionException, InterruptedException {
        if (cameraProvider == null) {
            cameraProvider = cameraProviderFuture.get();
        }
        return cameraProvider;
    }

    public Camera2CameraInfoImpl getCameraInfoById(String cameraId) throws ExecutionException, InterruptedException {
        int counter = getCameraProvider().getAvailableCameraInfos().size();
        for (int i = 0; i < counter; i++) {
            CameraInfo availableCameraInfo = cameraProvider.getAvailableCameraInfos().get(i);
            Camera2CameraInfoImpl camera2CameraInfo = (Camera2CameraInfoImpl) availableCameraInfo;
            String id = camera2CameraInfo.getCameraId();
            if (id.equals(cameraId)) {
               return camera2CameraInfo;
            }
        }
        return null;
    }

    public synchronized void bind(Camera2CameraInfoImpl cameraInfo, Preview preview, ImageAnalysis imageAnalysis) {

        if (cameraProvider != null) {
            CameraRef existingCamera = getCameraIfExists(cameraInfo);
            if (existingCamera != null) {
                binds.remove(existingCamera);
            }
            binds.add(new CameraRef(cameraInfo, preview, imageAnalysis));
            updateLifecycle();
        }

    }

    @SuppressLint("RestrictedApi")
    private CameraRef getCameraIfExists(Camera2CameraInfoImpl cameraInfo) {
        Object[] list = binds.stream().filter(camera -> Objects.equals(camera.getCameraId(), cameraInfo.getCameraId())).toArray();
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
        if (cameraProvider != null && !binds.isEmpty()) {
            cameraProvider.unbindAll();
            LifecycleOwner lifecycleOwner = (LifecycleOwner) ContextUtil.get();
            if(binds.size() == 1) {
                CameraRef bind = binds.get(0);
                UseCaseGroup.Builder usecase = new UseCaseGroup.Builder();
                usecase.addUseCase(bind.preview);
                usecase.addUseCase(bind.imageAnalysis);
                CameraSelector cameraSelector = bind.cameraInfo.getCameraSelector();
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, usecase.build());
            } else {
                List<ConcurrentCamera.SingleCameraConfig> configs = new ArrayList<>();
                for (CameraRef bind : binds) {
                    UseCaseGroup.Builder usecase = new UseCaseGroup.Builder();
                    usecase.addUseCase(bind.preview);
                    usecase.addUseCase(bind.imageAnalysis);
                    CameraSelector cameraSelector = bind.cameraInfo.getCameraSelector();
                    ConcurrentCamera.SingleCameraConfig config = new ConcurrentCamera.SingleCameraConfig(
                            cameraSelector,
                            usecase.build(),
                            lifecycleOwner
                    );
                    configs.add(config);
                }
                cameraProvider.bindToLifecycle(configs);
            }
        }
    }

    public void dispose(ViewCameraSelector cameraSelector) {
        CameraRef existingCamera = getCameraIfExistsById(cameraSelector.getId());
        if (existingCamera != null) {
            binds.remove(existingCamera);
            updateLifecycle();
        }
    }

    private static class CameraRef {
       private final Camera2CameraInfoImpl cameraInfo;
        private final  Preview preview;
        private final  ImageAnalysis imageAnalysis;

        private String getCameraId() {
            return cameraInfo.getCameraId();
        }

        private CameraRef(Camera2CameraInfoImpl cameraInfo, Preview preview, ImageAnalysis imageAnalysis) {
            this.cameraInfo = cameraInfo;
            this.preview = preview;
            this.imageAnalysis = imageAnalysis;
        }
    }

}
