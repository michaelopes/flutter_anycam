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
import androidx.camera.core.UseCase;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ZoomState;
import androidx.camera.core.impl.utils.futures.FutureCallback;
import androidx.camera.core.impl.utils.futures.Futures;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import br.dev.michaellopes.flutter_anycam.model.ViewCameraSelector;
import io.flutter.Log;

/**
 * Owns every CameraX bind of the process.
 *
 * <p>Invariants:
 * <ul>
 *   <li>A registered camera is either CameraX-managed ({@link #binds}) or Camera2-direct
 *   ({@link #camera2Direct}), never both.</li>
 *   <li>While any Camera2-direct camera is alive, new cameras also go Camera2-direct: CameraX
 *   cannot share hardware with an externally opened camera, and switching back would force a
 *   black-frame transition on the cameras already streaming.</li>
 *   <li>Callbacks stay registered until the camera is disposed, so every rebind (activity
 *   recreation, second camera joining) reaches every camera that lost its use cases.</li>
 * </ul>
 * All mutating methods run on the main thread (CameraX requirement) and are synchronized so the
 * analyzer threads can safely read {@link #cameras}.
 */
@SuppressLint("RestrictedApi")
public class DeviceCameraUtils {

    private static final String TAG = "DeviceCameraUtils";

    private DeviceCameraUtils() {
    }

    private static DeviceCameraUtils instance;

    private final List<CameraRef> binds = new ArrayList<>();
    private final Set<String> camera2Direct = new LinkedHashSet<>();
    private final Map<String, BindCallback> bindCallbacks = new HashMap<>();
    private final List<Camera> cameras = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Runnable pendingLifecycleUpdate;
    private boolean concurrentBound = false;

    private static final long LIFECYCLE_DEBOUNCE_MS = 500;
    private int activityWaitRetries = 0;
    private static final int MAX_ACTIVITY_WAIT_RETRIES = 20;
    private int providerWaitRetries = 0;
    private static final int MAX_PROVIDER_WAIT_RETRIES = 3;

    public interface BindCallback {
        /** Called after every successful CameraX (re)bind of this camera. */
        void onCameraXBound(Camera2CameraInfoImpl cameraInfo);

        /** CameraX released this camera; the owner must open it through Camera2 directly. */
        void onUseCamera2Direct();

        void onBindFailed(String message);
    }

    public static synchronized DeviceCameraUtils getInstance() {
        if (instance == null) instance = new DeviceCameraUtils();
        return instance;
    }

    public synchronized void bind(
            String cameraId,
            Preview preview,
            ImageAnalysis imageAnalysis,
            BindCallback callback
    ) {
        unregister(cameraId);

        if (callback != null) {
            bindCallbacks.put(cameraId, callback);
        }

        if (CameraUtil.getInstance().getProvider() != null
                && CameraUtil.getInstance().getCameraById(cameraId) == null) {
            notifyBindFailed(cameraId, "Camera not found: " + cameraId);
            bindCallbacks.remove(cameraId);
            return;
        }

        if (!camera2Direct.isEmpty()) {
            camera2Direct.add(cameraId);
            notifyUseCamera2Direct(cameraId);
            return;
        }

        binds.add(new CameraRef(cameraId, buildSelectorForCameraId(cameraId), preview, imageAnalysis));
        scheduleLifecycleUpdate();
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
        return binds.size() + camera2Direct.size();
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
        // Cold single bind: no debounce (faster first preview on low-end).
        // Rebinds / multi-cam: debounce to coalesce rapid create/dispose from Dart.
        boolean coldSingleBind = binds.size() == 1 && cameras.isEmpty();
        long delay = coldSingleBind ? 0L : LIFECYCLE_DEBOUNCE_MS;
        mainHandler.postDelayed(pendingLifecycleUpdate, delay);
    }

    private synchronized Camera getBackCamera() {
        for (Camera camera : cameras) {
            if (camera.getCameraInfo().getLensFacing() == CameraSelector.LENS_FACING_BACK) {
                return camera;
            }
        }
        return null;
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private synchronized Camera getCameraById(String id) {
        for (Camera camera : cameras) {
            String cameraId = Camera2CameraInfo.from(camera.getCameraInfo()).getCameraId();
            if (cameraId.equals(id)) {
                return camera;
            }
        }
        return null;
    }

    public void setFlash(boolean value) {
        Camera back = getBackCamera();
        if (back == null) return;
        try {
            if (back.getCameraInfo().hasFlashUnit()) {
                back.getCameraControl().enableTorch(value);
            }
        } catch (Exception e) {
            Log.w(TAG, "setFlash failed: " + e.getMessage());
        }
    }

    public void setZoom(float value, String cameraId) {
        Camera camera = getCameraById(cameraId);
        if (camera == null) return;
        try {
            ZoomState zoomState = camera.getCameraInfo().getZoomState().getValue();
            if (zoomState == null) return;
            float clamped = Math.max(zoomState.getMinZoomRatio(),
                    Math.min(zoomState.getMaxZoomRatio(), value));
            camera.getCameraControl().setZoomRatio(clamped);
        } catch (Exception ignored) {
        }
    }

    public void setExposureCompensation(int value, String cameraId) {
        Camera camera = getCameraById(cameraId);
        if (camera == null) {
            Log.w(TAG, "setExposureCompensation: camera not bound: " + cameraId);
            return;
        }
        try {
            ExposureState exposureState = camera.getCameraInfo().getExposureState();
            if (!exposureState.isExposureCompensationSupported()) {
                return;
            }
            Range<Integer> range = exposureState.getExposureCompensationRange();
            CameraControl cameraControl = camera.getCameraControl();
            int clamped = Math.max(range.getLower(), Math.min(range.getUpper(), value));
            ListenableFuture<Integer> future = cameraControl.setExposureCompensationIndex(clamped);
            Futures.addCallback(future, new FutureCallback<Integer>() {
                @Override
                public void onSuccess(Integer result) {
                }

                @Override
                public void onFailure(@NonNull Throwable t) {
                    Log.w(TAG, "Exposure failed: " + t.getMessage());
                }
            }, ContextCompat.getMainExecutor(ContextUtil.get()));
        } catch (Exception e) {
            Log.w(TAG, "setExposureCompensation failed: " + e.getMessage());
        }
    }

    private CameraRef findBind(String cameraId) {
        for (CameraRef ref : binds) {
            if (ref.cameraId.equals(cameraId)) return ref;
        }
        return null;
    }

    private synchronized void performLifecycleUpdate() {
        pendingLifecycleUpdate = null;

        ProcessCameraProvider cameraProvider = CameraUtil.getInstance().getProvider();
        if (cameraProvider == null) {
            waitForProvider();
            return;
        }
        providerWaitRetries = 0;

        safeUnbindAll(cameraProvider);
        if (binds.isEmpty()) {
            return;
        }

        LifecycleOwner lifecycleOwner = ContextUtil.getLifecycleOwner();
        if (lifecycleOwner == null) {
            // Activity often attaches slightly after the engine; keep callbacks and retry.
            if (activityWaitRetries++ < MAX_ACTIVITY_WAIT_RETRIES) {
                Log.w(TAG, "LifecycleOwner missing; retry "
                        + activityWaitRetries + "/" + MAX_ACTIVITY_WAIT_RETRIES);
                mainHandler.postDelayed(this::retryLifecycleUpdate, 100);
            } else {
                activityWaitRetries = 0;
                Log.e(TAG, "No LifecycleOwner after retries; failing binds");
                failAllBinds("Activity not attached");
            }
            return;
        }
        activityWaitRetries = 0;

        try {
            if (binds.size() == 1) {
                bindSingleCamera(cameraProvider, lifecycleOwner, binds.get(0));
                notifyCameraXBound(binds.get(0));
                return;
            }
            if (binds.size() == 2 && isConcurrentPairSupported(cameraProvider)) {
                bindConcurrentCameras(cameraProvider, lifecycleOwner);
                if (cameras.size() >= binds.size()) {
                    for (CameraRef bind : binds) {
                        notifyCameraXBound(bind);
                    }
                    return;
                }
                Log.w(TAG, "Concurrent bind opened " + cameras.size() + "/" + binds.size()
                        + " cameras — falling back to Camera2");
            } else {
                Log.i(TAG, "Concurrent CameraX unavailable for " + binds.size()
                        + " cameras — using Camera2 direct");
            }
        } catch (Exception e) {
            Log.e(TAG, "CameraX bind failed — using Camera2 direct", e);
        }
        moveAllToCamera2(cameraProvider);
    }

    private synchronized void retryLifecycleUpdate() {
        if (!binds.isEmpty()) {
            scheduleLifecycleUpdate();
        }
    }

    private void waitForProvider() {
        if (binds.isEmpty()) return;
        if (providerWaitRetries++ >= MAX_PROVIDER_WAIT_RETRIES) {
            providerWaitRetries = 0;
            Log.e(TAG, "ProcessCameraProvider unavailable; failing binds");
            failAllBinds("Camera provider unavailable");
            return;
        }
        CameraUtil.getInstance().availableCameras(list -> mainHandler.post(this::retryLifecycleUpdate));
    }

    private void failAllBinds(String message) {
        // Drop refs so a later onActivityReady() cannot rebind cameras whose
        // owners were already told the bind failed.
        for (CameraRef bind : new ArrayList<>(binds)) {
            notifyBindFailed(bind.cameraId, message);
            bindCallbacks.remove(bind.cameraId);
        }
        binds.clear();
    }

    private void moveAllToCamera2(ProcessCameraProvider cameraProvider) {
        safeUnbindAll(cameraProvider);
        for (CameraRef bind : new ArrayList<>(binds)) {
            detachUseCases(bind);
            camera2Direct.add(bind.cameraId);
            notifyUseCamera2Direct(bind.cameraId);
        }
        binds.clear();
    }

    private void safeUnbindAll(ProcessCameraProvider cameraProvider) {
        cameras.clear();
        concurrentBound = false;
        try {
            cameraProvider.unbindAll();
        } catch (Exception e) {
            Log.w(TAG, "unbindAll failed: " + e.getMessage());
        }
    }

    private void notifyCameraXBound(CameraRef bind) {
        BindCallback callback = bindCallbacks.get(bind.cameraId);
        CameraUtil.CameraItem camera = CameraUtil.getInstance().getCameraById(bind.cameraId);
        if (callback == null || camera == null || camera.getCameraInfo() == null) return;
        Camera2CameraInfoImpl info = camera.getCameraInfo();
        mainHandler.post(() -> {
            if (isCurrentCallback(bind.cameraId, callback)) {
                callback.onCameraXBound(info);
            }
        });
    }

    private void notifyUseCamera2Direct(String cameraId) {
        BindCallback callback = bindCallbacks.get(cameraId);
        if (callback == null) return;
        mainHandler.post(() -> {
            if (isCurrentCallback(cameraId, callback)) {
                callback.onUseCamera2Direct();
            }
        });
    }

    private void notifyBindFailed(String cameraId, String message) {
        BindCallback callback = bindCallbacks.get(cameraId);
        if (callback == null) return;
        mainHandler.post(() -> callback.onBindFailed(message));
    }

    private synchronized boolean isCurrentCallback(String cameraId, BindCallback callback) {
        return bindCallbacks.get(cameraId) == callback;
    }

    private void bindSingleCamera(
            ProcessCameraProvider cameraProvider,
            LifecycleOwner lifecycleOwner,
            CameraRef bind
    ) {
        Camera currentCamera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                bind.cameraSelector,
                buildUseCaseGroup(bind)
        );
        cameras.add(currentCamera);
        Log.i(TAG, "Bound single camera id=" + bind.cameraId);
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private void bindConcurrentCameras(
            ProcessCameraProvider cameraProvider,
            LifecycleOwner lifecycleOwner
    ) {
        List<ConcurrentCamera.SingleCameraConfig> configs = new ArrayList<>();
        for (CameraRef bind : binds) {
            configs.add(new ConcurrentCamera.SingleCameraConfig(
                    bind.cameraSelector,
                    buildUseCaseGroup(bind),
                    lifecycleOwner
            ));
        }
        ConcurrentCamera concurrentCamera = cameraProvider.bindToLifecycle(configs);
        cameras.addAll(concurrentCamera.getCameras());
        concurrentBound = true;
        Log.i(TAG, "Bound concurrent cameras count=" + cameras.size());
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private boolean isConcurrentPairSupported(ProcessCameraProvider cameraProvider) {
        List<List<CameraInfo>> concurrentSets = cameraProvider.getAvailableConcurrentCameraInfos();
        if (concurrentSets.isEmpty()) {
            return false;
        }
        String id0 = binds.get(0).cameraId;
        String id1 = binds.get(1).cameraId;
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
        UseCaseGroup.Builder usecase = new UseCaseGroup.Builder();
        if (bind.preview != null) {
            usecase.addUseCase(bind.preview);
        }
        usecase.addUseCase(bind.imageAnalysis);
        return usecase.build();
    }

    /**
     * Unbinds this camera's use cases right away: the owner releases its SurfaceTexture right
     * after this call, and CameraX must not keep rendering into it until the debounced rebind.
     */
    public synchronized void dispose(ViewCameraSelector cameraSelector) {
        unregister(cameraSelector.getId());
    }

    public synchronized void removeBind(String cameraId) {
        unregister(cameraId);
    }

    private void unregister(String cameraId) {
        bindCallbacks.remove(cameraId);
        camera2Direct.remove(cameraId);

        CameraRef ref = findBind(cameraId);
        if (ref == null) return;
        binds.remove(ref);

        boolean wasBound = getCameraByIdLocked(cameraId) != null;
        detachUseCases(ref);

        ProcessCameraProvider provider = CameraUtil.getInstance().getProvider();
        if (provider != null && wasBound) {
            List<UseCase> useCases = new ArrayList<>();
            if (ref.preview != null) useCases.add(ref.preview);
            if (ref.imageAnalysis != null) useCases.add(ref.imageAnalysis);
            try {
                provider.unbind(useCases.toArray(new UseCase[0]));
            } catch (Exception e) {
                Log.w(TAG, "unbind failed for " + cameraId + ": " + e.getMessage());
            }
            removeCamera(cameraId);
        }
        if (cameras.isEmpty()) {
            concurrentBound = false;
        }

        // Survivors of a concurrent pair must be rebound as a single camera; a camera
        // that was never bound only needs the pending update (if any) to run.
        if (!binds.isEmpty() && (concurrentBound || pendingLifecycleUpdate != null)) {
            scheduleLifecycleUpdate();
        } else if (binds.isEmpty() && pendingLifecycleUpdate != null) {
            mainHandler.removeCallbacks(pendingLifecycleUpdate);
            pendingLifecycleUpdate = null;
        }
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private Camera getCameraByIdLocked(String id) {
        for (Camera camera : cameras) {
            if (id.equals(Camera2CameraInfo.from(camera.getCameraInfo()).getCameraId())) {
                return camera;
            }
        }
        return null;
    }

    private void removeCamera(String id) {
        Camera camera = getCameraByIdLocked(id);
        if (camera != null) cameras.remove(camera);
    }

    private void detachUseCases(CameraRef cameraRef) {
        if (cameraRef.preview != null) {
            cameraRef.preview.setSurfaceProvider(null);
        }
        if (cameraRef.imageAnalysis != null) {
            cameraRef.imageAnalysis.clearAnalyzer();
        }
    }

    public static class CameraRef {
        private final String cameraId;
        private final CameraSelector cameraSelector;
        private final Preview preview;
        private final ImageAnalysis imageAnalysis;

        private CameraRef(String cameraId, CameraSelector cameraSelector, Preview preview, ImageAnalysis imageAnalysis) {
            this.cameraId = cameraId;
            this.cameraSelector = cameraSelector;
            this.preview = preview;
            this.imageAnalysis = imageAnalysis;
        }
    }
}
