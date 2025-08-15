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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import br.dev.michaellopes.flutter_anycam.model.ViewCameraSelector;

public class DeviceCameraUtils {

    private DeviceCameraUtils() {
    }

    List<ConcurrentCamera.SingleCameraConfig> configs = new ArrayList<>();
    private final ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(ContextUtil.get());

    private ProcessCameraProvider cameraProvider;

    private static DeviceCameraUtils instance;

    private ConcurrentCamera.SingleCameraConfig backCamera = null;

    private ConcurrentCamera.SingleCameraConfig frontCamera = null;

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

    @SuppressLint("RestrictedApi")
    public CameraSelector getCameraSelectorByCameraId(String cameraId) throws ExecutionException, InterruptedException {
        int counter = getCameraProvider().getAvailableCameraInfos().size();
        CameraSelector cameraSelector = null;
        for (int i = 0; i < counter; i++) {
            CameraInfo availableCameraInfo = cameraProvider.getAvailableCameraInfos().get(i);
            Camera2CameraInfoImpl camera2CameraInfo = (Camera2CameraInfoImpl) availableCameraInfo;
            String id = camera2CameraInfo.getCameraId();
            if (id.equals(cameraId)) {
                cameraSelector = availableCameraInfo.getCameraSelector();
            }
        }
        return cameraSelector;
    }

    @SuppressLint("RestrictedApi")
    public synchronized void bind(CameraSelector cameraSelector, Preview preview, ImageAnalysis imageAnalysis) {
        Integer lensFacing = cameraSelector.getLensFacing();
        if (cameraProvider != null) {
            LifecycleOwner lifecycleOwner = (LifecycleOwner) ContextUtil.get();
            if (lensFacing == null || lensFacing == CameraSelector.LENS_FACING_BACK || lensFacing == CameraSelector.LENS_FACING_UNKNOWN) {
                UseCaseGroup.Builder usecase = new UseCaseGroup.Builder();
                usecase.addUseCase(preview);
                usecase.addUseCase(imageAnalysis);
                backCamera = new ConcurrentCamera.SingleCameraConfig(
                        cameraSelector,
                        usecase.build(),
                        lifecycleOwner
                );


                configs.add(backCamera);
            } else if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                UseCaseGroup.Builder usecase = new UseCaseGroup.Builder();
                usecase.addUseCase(preview);
                usecase.addUseCase(imageAnalysis);
                frontCamera = new ConcurrentCamera.SingleCameraConfig(
                        cameraSelector,
                        usecase.build(),
                        lifecycleOwner
                );
                configs.add(frontCamera);
            }

            updateLifecycle();
        }
    }

    private void updateLifecycle() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            if (!configs.isEmpty()) {
                if (configs.size() == 1) {
                    LifecycleOwner lifecycleOwner = (LifecycleOwner) ContextUtil.get();
                    UseCaseGroup usecaseGroup = configs.get(0).getUseCaseGroup();
                    CameraSelector cameraSelector = configs.get(0).getCameraSelector();
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, usecaseGroup);
                } else {
                    cameraProvider.bindToLifecycle(configs);
                }
            }
        }
    }

    public void dispose(ViewCameraSelector cameraSelector) {
        if(backCamera != null && (cameraSelector.getLensFacing().equals("back") || cameraSelector.getLensFacing().equals("unknown"))) {
            configs.remove(backCamera);
            backCamera = null;
        } else if(frontCamera != null && cameraSelector.getLensFacing().equals("front")) {
            configs.remove(frontCamera);
            frontCamera = null;
        }
        updateLifecycle();
    }

}
