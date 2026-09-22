package br.dev.michaellopes.flutter_anycam.utils;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.util.Range;


import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.camera2.internal.Camera2CameraInfoImpl;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.Camera;

import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ConcurrentCamera;
import androidx.camera.core.ExposureState;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ZoomState;
import androidx.camera.core.impl.utils.futures.FutureCallback;
import androidx.camera.core.impl.utils.futures.Futures;
import androidx.camera.lifecycle.ProcessCameraProvider;

import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;


import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


import br.dev.michaellopes.flutter_anycam.model.ViewCameraSelector;
import io.flutter.Log;

@SuppressLint("RestrictedApi")
public class DeviceCameraUtils {

    private DeviceCameraUtils() {
    }

    private static DeviceCameraUtils instance;
    private final List<CameraRef> binds = new ArrayList<>();
    private  List<Camera> cameras = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingLifecycleUpdate;
    private static final long LIFECYCLE_DEBOUNCE_MS = 500;
    private int activityWaitRetries = 0;
    private static final int MAX_ACTIVITY_WAIT_RETRIES = 20;

    public interface BindCallback {
        void onCameraXBound(Camera2CameraInfoImpl cameraInfo);
        void onUseCamera2Direct();
        void onBindFailed(String message);
    }

    private final java.util.Map<String, BindCallback> bindCallbacks = new java.util.HashMap<>();

    public static synchronized DeviceCameraUtils getInstance() {
        if (instance == null) instance = new DeviceCameraUtils();
        return instance;
    }

    public synchronized Camera2CameraInfoImpl bind(
            String cameraId,
            Preview preview,
            ImageAnalysis imageAnalysis,
            BindCallback callback
    ) {
        CameraUtil.CameraItem camera = CameraUtil.getInstance().getCameraById(cameraId);
        if (camera != null) {
            CameraRef existingCamera = getCameraIfExistsById(cameraId);
            if (existingCamera != null) {
                binds.remove(existingCamera);
            }
            CameraSelector selector = buildSelectorForCameraId(cameraId);
            binds.add(new CameraRef(cameraId, selector, preview, imageAnalysis));
            if (callback != null) {
                bindCallbacks.put(cameraId, callback);
            }
            scheduleLifecycleUpdate();
            return camera.getCameraInfo();
        }
        return null;
    }

    /** @deprecated Use {@link #bind(String, Preview, ImageAnalysis, BindCallback)} */
    public synchronized Camera2CameraInfoImpl bind(String cameraId, Preview preview, ImageAnalysis imageAnalysis) {
        return bind(cameraId, preview, imageAnalysis, null);
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private CameraSelector buildSelectorForCameraId(String cameraId) {
        return new CameraSelector.Builder()
                .addCameraFilter(cameraInfos -> {
                    List<CameraInfo> filtered = new ArrayList<>();
                    for (CameraInfo info : cameraInfos) {
                        String id = Camera2CameraInfo.from(info).getCameraId();
                        if (cameraId.equals(id)) {
                            filtered.add(info);
                        }
                    }
                    return filtered;
                })
                .build();
    }

    public synchronized int getActiveBindCount() {
        return binds.size();
    }

    /** Called when Activity becomes available again (attach / config change). */
    public synchronized void onActivityReady() {
        if (!binds.isEmpty()) {
            activityWaitRetries = 0;
            scheduleLifecycleUpdate();
        }
    }

    private void scheduleLifecycleUpdate() {
        if (pendingLifecycleUpdate != null) {
            mainHandler.removeCallbacks(pendingLifecycleUpdate);
        }
        pendingLifecycleUpdate = this::performLifecycleUpdate;
        // First cold bind: no debounce (faster preview on low-end).
        // Rebinds / multi-cam: debounce to coalesce rapid create/dispose from Dart lifecycle.
        boolean coldSingleBind = binds.size() == 1 && cameras.isEmpty();
        long delay = coldSingleBind ? 0L : LIFECYCLE_DEBOUNCE_MS;
        mainHandler.postDelayed(pendingLifecycleUpdate, delay);
    }


    private Camera getBackCamera() {
        for (Camera camera: cameras) {
            if(camera.getCameraInfo().getCameraSelector().getLensFacing() ==
                    CameraSelector.LENS_FACING_BACK ) {
             return  camera;
            }
        }
        return  null;
    }


    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private Camera getCameraById(String id) {
        for (Camera camera: cameras) {
            String cameraId = Camera2CameraInfo.from(camera.getCameraInfo()).getCameraId();
            if(cameraId.equals(id)) {
                return camera;
            }
        }
        return  null;
    }
    public void setFlash(boolean value) {
        if(getBackCamera() != null) {
            if (getBackCamera().getCameraInfo().hasFlashUnit()) {
                getBackCamera().getCameraControl().enableTorch(value);
            }
        }
    }

    public void setZoom(float value, String cameraId) {
        Camera camera = getCameraById(cameraId);
        if(camera != null) {
            try {
                ZoomState zoomState = camera.getCameraInfo().getZoomState().getValue();
                float maxZoom = zoomState.getMaxZoomRatio();
                if (maxZoom >= value) {
                    camera.getCameraControl().setZoomRatio(value);
                }
            } catch (Exception ignored) {}
        }
    }

    public void setExposureCompensation(int value, String cameraId) {
        Camera camera = getCameraById(cameraId);
        if (camera == null) {
            Log.e("Camera", "Camera null para id: " + cameraId);
            return;
        }

        try {
            CameraInfo cameraInfo = camera.getCameraInfo();
            ExposureState exposureState = cameraInfo.getExposureState();

            Log.d("Camera", "isExposureCompensationSupported: " + exposureState.isExposureCompensationSupported());

            Range<Integer> range = exposureState.getExposureCompensationRange();
            Log.d("Camera", "Range: " + range.getLower() + " to " + range.getUpper());
            Log.d("Camera", "Exposure atual: " + exposureState.getExposureCompensationIndex());
            Log.d("Camera", "Valor recebido: " + value);

            if (!exposureState.isExposureCompensationSupported()) {
                Log.w("Camera", "Dispositivo não suporta exposure compensation");
                return;
            }

            CameraControl cameraControl = camera.getCameraControl();
            int clamped = Math.max(range.getLower(), Math.min(range.getUpper(), value));
            Log.d("Camera", "Clamped value: " + clamped);

            ListenableFuture<Integer> future = cameraControl.setExposureCompensationIndex(clamped);

            Futures.addCallback(future, new FutureCallback<Integer>() {
                @Override
                public void onSuccess(Integer result) {
                    Log.d("Camera", "Exposure aplicado: " + result);
                }

                @Override
                public void onFailure(@NonNull Throwable t) {
                    Log.e("Camera", "Exposure falhou: " + t.getClass().getSimpleName() + " - " + t.getMessage());
                }
            }, ContextCompat.getMainExecutor(ContextUtil.get()));

        } catch (Exception e) {
            Log.e("Camera", "Exceção: " + e.getMessage());
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

    private void performLifecycleUpdate() {
        pendingLifecycleUpdate = null;
        ProcessCameraProvider cameraProvider = CameraUtil.getInstance().getProvider();
        if (cameraProvider != null) {
            cameras.clear();
            cameraProvider.unbindAll();
            if (!binds.isEmpty()) {
                LifecycleOwner lifecycleOwner = ContextUtil.getLifecycleOwner();
                if (lifecycleOwner == null) {
                    // Activity often attaches slightly after engine; keep callbacks and retry.
                    if (activityWaitRetries++ < MAX_ACTIVITY_WAIT_RETRIES) {
                        Log.w("DeviceCameraUtils", "LifecycleOwner missing; retry "
                                + activityWaitRetries + "/" + MAX_ACTIVITY_WAIT_RETRIES);
                        mainHandler.postDelayed(this::scheduleLifecycleUpdate, 100);
                    } else {
                        activityWaitRetries = 0;
                        Log.e("DeviceCameraUtils", "No LifecycleOwner after retries; failing binds");
                        for (CameraRef bind : new ArrayList<>(binds)) {
                            notifyBindFailed(bind.getCameraId(), "Activity not attached");
                        }
                        // Drop orphan refs so a later onActivityReady() cannot rebind
                        // without the callbacks that were already notified as failed.
                        binds.clear();
                    }
                    return;
                }
                activityWaitRetries = 0;
                try {
                    if (binds.size() == 1) {
                        bindSingleCamera(cameraProvider, lifecycleOwner, binds.get(0));
                        notifyCameraXBound(binds.get(0));
                    } else if (!isConcurrentPairSupported(cameraProvider)) {
                        Log.i("DeviceCameraUtils", "Concurrent not supported — using Camera2 direct for "
                                + binds.get(0).getCameraId() + "+" + binds.get(1).getCameraId());
                        notifyUseCamera2Direct();
                    } else {
                        bindConcurrentCameras(cameraProvider, lifecycleOwner);
                        if (cameras.size() < binds.size()) {
                            Log.w("DeviceCameraUtils", "Concurrent bind opened "
                                    + cameras.size() + "/" + binds.size()
                                    + " cameras — falling back to Camera2");
                            cameraProvider.unbindAll();
                            cameras.clear();
                            notifyUseCamera2Direct();
                        } else {
                            for (CameraRef bind : binds) {
                                notifyCameraXBound(bind);
                            }
                        }
                    }
                } catch (Exception e) {
                    // Analysis-only drops Preview → black Texture + null resolution races.
                    // Dual preview must use Camera2 when CameraX concurrent fails.
                    Log.e("DeviceCameraUtils", "Concurrent CameraX failed — using Camera2 direct", e);
                    try {
                        cameraProvider.unbindAll();
                    } catch (Exception ignored) {
                    }
                    cameras.clear();
                    notifyUseCamera2Direct();
                }
            }
        }
    }

    private void notifyCameraXBound(CameraRef bind) {
        BindCallback callback = bindCallbacks.remove(bind.getCameraId());
        if (callback != null) {
            CameraUtil.CameraItem camera = CameraUtil.getInstance().getCameraById(bind.getCameraId());
            if (camera != null) {
                mainHandler.post(() -> callback.onCameraXBound(camera.getCameraInfo()));
            }
        }
    }

    private void notifyUseCamera2Direct() {
        List<BindCallback> callbacks = new ArrayList<>(bindCallbacks.values());
        bindCallbacks.clear();
        for (BindCallback callback : callbacks) {
            mainHandler.post(callback::onUseCamera2Direct);
        }
    }

    private void notifyBindFailed(String cameraId, String message) {
        BindCallback callback = bindCallbacks.remove(cameraId);
        if (callback != null) {
            mainHandler.post(() -> callback.onBindFailed(message));
        }
    }

    private void bindSingleCamera(
            ProcessCameraProvider cameraProvider,
            LifecycleOwner lifecycleOwner,
            CameraRef bind
    ) {
        UseCaseGroup useCaseGroup = buildUseCaseGroup(bind);
        Camera currentCamera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                bind.cameraSelector,
                useCaseGroup
        );
        cameras.add(currentCamera);
        Log.i("DeviceCameraUtils", "Bound single camera id=" + bind.getCameraId());
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private void bindConcurrentCamerasAnalysisOnly(
            ProcessCameraProvider cameraProvider,
            LifecycleOwner lifecycleOwner
    ) {
        Log.i("DeviceCameraUtils", "Retrying concurrent bind analysis-only");
        List<ConcurrentCamera.SingleCameraConfig> configs = new ArrayList<>();
        for (CameraRef bind : binds) {
            ConcurrentCamera.SingleCameraConfig config = new ConcurrentCamera.SingleCameraConfig(
                    bind.cameraSelector,
                    buildUseCaseGroup(bind, true),
                    lifecycleOwner
            );
            configs.add(config);
        }
        ConcurrentCamera concurrentCamera = cameraProvider.bindToLifecycle(configs);
        cameras.addAll(concurrentCamera.getCameras());
        Log.i("DeviceCameraUtils", "Bound concurrent analysis-only count=" + cameras.size());
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private void bindConcurrentCameras(
            ProcessCameraProvider cameraProvider,
            LifecycleOwner lifecycleOwner
    ) {
        boolean supported = isConcurrentPairSupported(cameraProvider);
        Log.i("DeviceCameraUtils", "Concurrent mode supported=" + supported
                + " cameras=" + binds.get(0).getCameraId() + "+" + binds.get(1).getCameraId());

        List<ConcurrentCamera.SingleCameraConfig> configs = new ArrayList<>();
        for (CameraRef bind : binds) {
            ConcurrentCamera.SingleCameraConfig config = new ConcurrentCamera.SingleCameraConfig(
                    bind.cameraSelector,
                    buildUseCaseGroup(bind, false),
                    lifecycleOwner
            );
            configs.add(config);
        }
        ConcurrentCamera concurrentCamera = cameraProvider.bindToLifecycle(configs);
        cameras.addAll(concurrentCamera.getCameras());
        Log.i("DeviceCameraUtils", "Bound concurrent cameras count=" + cameras.size());
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private boolean isConcurrentPairSupported(ProcessCameraProvider cameraProvider) {
        List<List<CameraInfo>> concurrentSets = cameraProvider.getAvailableConcurrentCameraInfos();
        if (concurrentSets.isEmpty()) {
            return false;
        }

        String id0 = binds.get(0).getCameraId();
        String id1 = binds.get(1).getCameraId();

        for (List<CameraInfo> set : concurrentSets) {
            boolean has0 = false;
            boolean has1 = false;
            for (CameraInfo info : set) {
                String id = Camera2CameraInfo.from(info).getCameraId();
                if (id0.equals(id)) has0 = true;
                if (id1.equals(id)) has1 = true;
            }
            if (has0 && has1) {
                return true;
            }
        }
        return false;
    }

    private UseCaseGroup buildUseCaseGroup(CameraRef bind) {
        return buildUseCaseGroup(bind, false);
    }

    private UseCaseGroup buildUseCaseGroup(CameraRef bind, boolean analysisOnly) {
        UseCaseGroup.Builder usecase = new UseCaseGroup.Builder();
        if (!analysisOnly && bind.preview != null) {
            usecase.addUseCase(bind.preview);
        }
        usecase.addUseCase(bind.imageAnalysis);
        return usecase.build();
    }

    public void dispose(ViewCameraSelector cameraSelector) {
        setFlash(false);
        CameraRef existingCamera = getCameraIfExistsById(cameraSelector.getId());
        if (existingCamera != null) {
            binds.remove(existingCamera);
            disposeCameraRef(existingCamera);
            bindCallbacks.remove(cameraSelector.getId());
            scheduleLifecycleUpdate();
        }
    }

    public synchronized void removeBind(String cameraId) {
        CameraRef existingCamera = getCameraIfExistsById(cameraId);
        if (existingCamera != null) {
            binds.remove(existingCamera);
            bindCallbacks.remove(cameraId);
        }
    }


    private void disposeCameraRef(CameraRef cameraRef) {
        if (cameraRef != null) {
            if (cameraRef.preview != null) {
                cameraRef.preview.setSurfaceProvider(null);
            }

            if (cameraRef.imageAnalysis != null) {
                cameraRef.imageAnalysis.clearAnalyzer();
            }
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
